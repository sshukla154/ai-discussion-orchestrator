package io.github.sshukla154.aido.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.sshukla154.aido.common.time.UtcInstantConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answers the open question that gates the whole persistence layer: does Spring Boot 4.1.1 with
 * Hibernate 7.4.5, the community SQLite dialect, {@code ddl-auto=validate}, Flyway and a
 * text-backed {@code Instant} converter actually work together?
 *
 * <p>This was the probe most likely to cost half a day if discovered late, because SQLite cannot
 * alter a column type or a constraint without rebuilding the entire table. Finding out after the
 * real schema is committed is materially more expensive than finding out now.
 *
 * <p>Kept as a permanent test rather than deleted once answered. It is cheap, and it fails
 * loudly if a dependency bump ever breaks the dialect pairing -- otherwise that surfaces at
 * runtime as a {@code NoSuchMethodError} during bootstrap.
 *
 * <p>Uses {@code @SpringBootTest} rather than a test slice on purpose. Boot 4 split the slice
 * annotations into per-technology modules, and {@code @DataJpaTest} is not on the classpath that
 * {@code spring-boot-starter-test} brings. Booting the real context also exercises the real
 * autoconfiguration path, which is what the spike is actually asking about.
 *
 * <p>{@code ddl-auto=validate} carries most of the value here: it compares the entity mapping
 * against the schema Flyway created and fails startup on a mismatch, so drift is caught at boot
 * rather than by the first query to touch a bad column.
 */
@SpringBootTest
class SqliteDialectSpikeTest {

    /**
     * A build directory rather than {@code @TempDir}.
     *
     * <p>JUnit deletes a temp directory once the class finishes, but the connection pool still
     * holds the database file and its two write-ahead-log sidecars open at that point, so the
     * deletion fails and reports as a spurious error after every otherwise-passing run. Using
     * {@code target/} sidesteps the race entirely: {@code mvn clean} owns the cleanup, and the
     * directory is already ignored by git.
     */
    private static final Path DATABASE_DIR = Path.of("target", "spike-db");

    @Autowired
    private SpikeRecordRepository repository;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(DATABASE_DIR);
        // A fresh database each run. Otherwise a schema change would collide with the
        // already-applied migration recorded in the previous run.
        for (String name : new String[]{"spike.db", "spike.db-wal", "spike.db-shm"}) {
            Files.deleteIfExists(DATABASE_DIR.resolve(name));
        }

        Path db = DATABASE_DIR.resolve("spike.db").toAbsolutePath();
        registry.add("spring.datasource.url", () ->
                "jdbc:sqlite:" + db
                        + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&synchronous=NORMAL");
        // The spike entity lives in test sources, so it needs its own migration rather than
        // the (currently empty) production one.
        registry.add("spring.flyway.locations", () -> "classpath:db/spike");
    }

    @Test
    @DisplayName("the context starts, which means Flyway ran and ddl-auto=validate accepted the mapping")
    void contextStartsWithValidateAgainstFlywaySchema() {
        // Reaching this line is the assertion. A dialect mismatch, a missing
        // community-dialects artifact, or an entity disagreeing with the migration all fail
        // before any test body runs.
        assertThat(repository).isNotNull();
        assertThat(repository.count()).isNotNegative();
    }

    @Test
    @DisplayName("a round trip preserves the converted timestamp, the enum and the boolean")
    void roundTripsAllColumnShapes() {
        Instant created = Instant.parse("2026-08-24T11:16:43.007Z");
        SpikeRecord saved = repository.saveAndFlush(new SpikeRecord(
                UUID.randomUUID().toString(), "first", nextSeq(), SpikeRecord.Kind.BETA, true, created));

        SpikeRecord found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getCreatedAt()).isEqualTo(created);
        assertThat(found.getKind()).isEqualTo(SpikeRecord.Kind.BETA);
        assertThat(found.isFlagged()).isTrue();
        assertThat(found.getLabel()).isEqualTo("first");
    }

    @Test
    @DisplayName("the timestamp is stored as fixed-width text, and the enum by name not ordinal")
    void storesTextRepresentationsTheDatabaseViewerCanRead() throws Exception {
        // A value ending in a zero millisecond is the case that exposes an unpadded
        // formatter: ISO_INSTANT renders .700 as .7, which is both unreadable and unsortable.
        // Asserting the raw column is the only way to catch it.
        String id = UUID.randomUUID().toString();
        repository.saveAndFlush(new SpikeRecord(id, "raw", nextSeq(),
                SpikeRecord.Kind.ALPHA, false, Instant.parse("2026-08-24T11:16:43.700Z")));

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT created_at, kind, flagged FROM spike_record WHERE id = '" + id + "'");
            assertThat(rs.next()).isTrue();
            String storedTimestamp = rs.getString("created_at");

            assertThat(storedTimestamp).isEqualTo("2026-08-24T11:16:43.700Z");
            assertThat(storedTimestamp).hasSize(UtcInstantConverter.WIDTH);
            assertThat(rs.getString("kind")).isEqualTo("ALPHA");
            assertThat(rs.getInt("flagged")).isZero();
        }
    }

    @Test
    @DisplayName("lexicographic order of the stored text matches chronological order")
    void fixedWidthTimestampsSortCorrectly() {
        // The regression this guards: with unpadded milliseconds ".9Z" sorts after ".10Z", so
        // every ORDER BY over the column is quietly wrong and nothing throws. These two values
        // are chosen so a variable-width format would return them in the wrong order.
        // Scoped to the two rows this test creates. Asserting on the whole table would make the
        // result depend on which other tests had run first, and would have needed a deleteAll
        // that could remove another test's rows mid-flight.
        String marker = "order-" + UUID.randomUUID();
        repository.saveAndFlush(new SpikeRecord(UUID.randomUUID().toString(), marker + "-later",
                nextSeq(), SpikeRecord.Kind.ALPHA, false, Instant.parse("2026-08-24T11:16:43.100Z")));
        repository.saveAndFlush(new SpikeRecord(UUID.randomUUID().toString(), marker + "-earlier",
                nextSeq(), SpikeRecord.Kind.ALPHA, false, Instant.parse("2026-08-24T11:16:43.090Z")));

        List<String> byTimestamp = repository.findAllByOrderByCreatedAtAsc().stream()
                .map(SpikeRecord::getLabel)
                .filter(l -> l.startsWith(marker))
                .toList();

        assertThat(byTimestamp).containsExactly(marker + "-earlier", marker + "-later");
    }

    @Test
    @DisplayName("@Version increments on update, so optimistic locking works on SQLite")
    void optimisticLockingVersionIncrements() {
        SpikeRecord saved = repository.saveAndFlush(new SpikeRecord(UUID.randomUUID().toString(),
                "v0", nextSeq(), SpikeRecord.Kind.ALPHA, false, Instant.now()));
        long initialVersion = saved.getVersion();

        SpikeRecord reloaded = repository.findById(saved.getId()).orElseThrow();
        reloaded.setLabel("v1");
        repository.saveAndFlush(reloaded);

        SpikeRecord after = repository.findById(saved.getId()).orElseThrow();
        assertThat(after.getLabel()).isEqualTo("v1");
        assertThat(after.getVersion())
                .describedAs("a version that never moves means optimistic locking is inert")
                .isGreaterThan(initialVersion);
    }

    @Test
    @DisplayName("the write-ahead log and foreign keys are actually on, not merely requested")
    void urlPragmasTakeEffect() throws Exception {
        // Asking for a pragma in the URL and having it applied are different things. A
        // silently-ignored journal_mode would only show up later as lock contention.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet journal = s.executeQuery("PRAGMA journal_mode");
            assertThat(journal.next()).isTrue();
            assertThat(journal.getString(1)).isEqualToIgnoringCase("wal");

            ResultSet fk = s.executeQuery("PRAGMA foreign_keys");
            assertThat(fk.next()).isTrue();
            assertThat(fk.getInt(1)).describedAs("foreign key enforcement must be on").isEqualTo(1);
        }
    }

    /** The seq column is uniquely indexed, and these tests share one database file. */
    private long nextSeq() {
        return System.nanoTime();
    }
}
