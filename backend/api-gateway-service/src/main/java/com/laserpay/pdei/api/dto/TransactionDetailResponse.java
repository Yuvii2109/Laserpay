package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.TransactionFacts;
import java.util.List;

/**
 * {@code GET /transactions/{transactionId}}: the row plus everything the detail page needs above the
 * fold.
 *
 * <p>{@code facts} is the evidence-core projection of the linked payments, orders, shipments,
 * deliveries, refunds and communications, reused verbatim rather than re-flattened here, so the
 * transaction detail page and the contradiction detector are looking at the identical shape.</p>
 *
 * <p>The heavy panels (timeline, graph, full evidence list) stay on their own routes: this response
 * carries the {@code readiness} snapshot and the evidence summary, and the page fetches the rest as
 * the operator opens each tab.</p>
 */
public record TransactionDetailResponse(
        TransactionResponse transaction,
        TransactionFacts facts,
        ReadinessSnapshot readiness,
        List<EvidenceView> evidence,
        int evidenceCount,
        int openGapCount) {

    public TransactionDetailResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
