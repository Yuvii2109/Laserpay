package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;

import java.util.List;
import java.util.Optional;

/** Read/write port for {@code pdei.readiness_snapshots} and {@code pdei.readiness_gaps}. */
public interface ReadinessRepositoryPort {

    /**
     * Persist a snapshot and replace the gap set for that transaction in one unit of work.
     * Snapshots are append-only; the newest row wins.
     */
    void saveSnapshot(ReadinessSnapshot snapshot);

    Optional<ReadinessSnapshot> findLatest(String transactionId);

    List<ReadinessSnapshot> findLatestForMerchant(String merchantId, int limit);

    /** The at-risk feed behind {@code GET /api/v1/gaps}. */
    List<ReadinessGap> findGaps(String merchantId, GapType type, GapSeverity severity, int page, int size);

    List<ReadinessGap> findGapsForTransaction(String transactionId);
}
