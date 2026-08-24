package io.github.sshukla154.aido.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpikeRecordRepository extends JpaRepository<SpikeRecord, String> {

    /**
     * Ordered by the text timestamp on purpose. This is the query that catches a
     * variable-width timestamp format: with unpadded milliseconds the lexicographic order
     * diverges from chronological order and nothing throws.
     */
    List<SpikeRecord> findAllByOrderByCreatedAtAsc();

    List<SpikeRecord> findAllByOrderBySeqAsc();
}
