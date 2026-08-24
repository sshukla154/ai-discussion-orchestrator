-- Baseline for the persistence spike. Column shapes mirror what the real schema will use, so
-- the answers transfer rather than being facts about a toy table.
--
-- The important lesson is in the type names. SQLite is dynamically typed and would accept
-- TEXT and INTEGER for all of these -- storage is identical either way, since it applies type
-- affinity rather than enforcing declared types. But ddl-auto=validate compares the
-- *declared* type name against what the Hibernate dialect expects for the Java type, and
-- fails startup on a mismatch. So the migration has to be written in the dialect's vocabulary,
-- not SQLite's:
--
--   Java type                        declare as    validate rejects
--   ------------------------------   -----------   ---------------------------------------
--   String                           VARCHAR       --
--   long                             BIGINT        INTEGER ("expecting [bigint]")
--   boolean                          BOOLEAN       INTEGER ("expecting [boolean]")
--   Instant via AttributeConverter   VARCHAR       (converted to String, so varchar)
--
-- Each mismatch is reported one at a time, so a schema written in SQLite primitives fails
-- repeatedly, one column per startup, rather than listing them all at once.
CREATE TABLE spike_record (
    -- Application-assigned UUID: the id must be known before insert, so no generated key.
    id          VARCHAR NOT NULL PRIMARY KEY,
    label       VARCHAR NOT NULL,
    -- Explicit ordering. Timestamps are not keys -- two rows can share a millisecond.
    seq         BIGINT  NOT NULL,
    -- Enum stored by name. Ordinals make the database unreadable and every reorder a migration.
    kind        VARCHAR NOT NULL,
    flagged     BOOLEAN NOT NULL,
    -- Fixed-width ISO-8601 UTC, 24 characters. See UtcInstantConverter for why the width is
    -- not cosmetic: unpadded milliseconds break lexicographic ordering silently.
    created_at  VARCHAR NOT NULL,
    version     BIGINT  NOT NULL
);

CREATE UNIQUE INDEX uq_spike_seq ON spike_record (seq);
CREATE INDEX idx_spike_created ON spike_record (created_at);
