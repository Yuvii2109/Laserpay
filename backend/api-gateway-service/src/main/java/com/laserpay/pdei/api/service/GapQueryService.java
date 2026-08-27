package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.support.Paging;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.spi.ReadinessRepositoryPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /gaps?merchantId&type&severity}: the at-risk feed on the control tower.
 *
 * <p>Only unresolved gaps are returned; the repository filters resolved rows out. Results are sorted
 * by severity descending so the feed opens on what is actually blocking, not on whatever the index
 * happened to return first.</p>
 */
@Service
@Transactional(readOnly = true)
public class GapQueryService {

    /**
     * CRITICAL first, then HIGH, MEDIUM, LOW; within a severity, most recently detected first.
     *
     * <p>{@code GapSeverity} is declared LOW, MEDIUM, HIGH, CRITICAL, so ordinal ascending is
     * severity ascending and the comparator is reversed. A gap with no severity sorts last rather
     * than throwing.</p>
     */
    private static final Comparator<Instant> NEWEST_FIRST =
            Comparator.nullsLast(Comparator.<Instant>reverseOrder());

    private static final Comparator<ReadinessGap> BY_SEVERITY_THEN_RECENCY =
            Comparator.comparingInt((ReadinessGap gap) ->
                            gap.severity() == null ? -1 : gap.severity().ordinal())
                    .reversed()
                    .thenComparing(ReadinessGap::detectedAt, NEWEST_FIRST);

    private final ReadinessRepositoryPort readiness;

    public GapQueryService(ReadinessRepositoryPort readiness) {
        this.readiness = readiness;
    }

    public PageResponse<ReadinessGap> find(String merchantId, GapType type, GapSeverity severity,
                                           int page, int size) {
        if (merchantId == null || merchantId.isBlank()) {
            throw ValidationException.field("merchantId", "is required");
        }
        // Validated before the port sees them: a negative page becomes a negative OFFSET in SQL.
        int safePage = Paging.page(page);
        int safeSize = Paging.size(size, Paging.MAX_SIZE);
        List<ReadinessGap> slice =
                readiness.findGaps(merchantId, type, severity, safePage, safeSize).stream()
                        .sorted(BY_SEVERITY_THEN_RECENCY)
                        .toList();
        return PageResponse.ofSlice(slice, safePage, safeSize);
    }

    /** Gaps for a single transaction: the drill-down from the feed. */
    public List<ReadinessGap> forTransaction(String transactionId) {
        return readiness.findGapsForTransaction(transactionId).stream()
                .sorted(BY_SEVERITY_THEN_RECENCY)
                .toList();
    }
}
