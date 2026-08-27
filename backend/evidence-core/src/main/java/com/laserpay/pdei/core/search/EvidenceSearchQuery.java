package com.laserpay.pdei.core.search;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;

/**
 * Filters for {@code GET /api/v1/evidence} and the evidence explorer screen.
 *
 * @param q free text; turned into a Postgres {@code tsquery} by the service
 */
public record EvidenceSearchQuery(
        String merchantId,
        String q,
        EvidenceType type,
        EvidenceStatus status,
        String transactionId,
        int page,
        int size) {

    public static final int MAX_SIZE = 200;

    public EvidenceSearchQuery {
        page = Math.max(0, page);
        size = size <= 0 ? 25 : Math.min(size, MAX_SIZE);
    }

    public static EvidenceSearchQuery of(String merchantId, String q) {
        return new EvidenceSearchQuery(merchantId, q, null, null, null, 0, 25);
    }
}
