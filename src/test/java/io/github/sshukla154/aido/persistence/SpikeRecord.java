package io.github.sshukla154.aido.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Test-scoped entity for the persistence spike.
 *
 * <p>Lives in test sources deliberately. The spike exists to answer whether this stack works at
 * all -- Hibernate community dialect, {@code ddl-auto=validate}, a text-backed
 * {@code Instant} converter, optimistic locking, Flyway -- before any of the real schema is
 * committed. Putting the probe entity in main sources would leave dead production code behind
 * once the real entities arrive.
 *
 * <p>The column shapes mirror what the real schema will use, so the answers transfer.
 */
@Entity
@Table(name = "spike_record")
public class SpikeRecord {

    /** Application-assigned UUID text, not a generated key: the id must be known before insert. */
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "label", nullable = false)
    private String label;

    /** Explicit ordering. Timestamps are not keys -- two rows can share a millisecond. */
    @Column(name = "seq", nullable = false)
    private long seq;

    /** Stored by name. Ordinals make the database unreadable and every reorder a migration. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private Kind kind;

    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    /** Converted to fixed-width ISO-8601 text; the converter is applied automatically. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SpikeRecord() {
        // required by JPA
    }

    public SpikeRecord(String id, String label, long seq, Kind kind, boolean flagged, Instant createdAt) {
        this.id = id;
        this.label = label;
        this.seq = seq;
        this.kind = kind;
        this.flagged = flagged;
        this.createdAt = createdAt;
    }

    public enum Kind {
        ALPHA, BETA
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public long getSeq() {
        return seq;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
