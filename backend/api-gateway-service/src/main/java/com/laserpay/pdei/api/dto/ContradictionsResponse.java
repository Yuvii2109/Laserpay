package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.ContradictionView;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /ai-tools/contradictions?transactionId=...}.
 *
 * <p>Computed fresh from the transaction facts on every call rather than read from a cached
 * snapshot. The model is asking "what conflicts right now"; answering from a stale snapshot would
 * let it reason about a contradiction that a later event already resolved.</p>
 */
public record ContradictionsResponse(
        String transactionId,
        List<ContradictionView> contradictions,
        int count,
        Instant detectedAt) {

    public ContradictionsResponse {
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
    }

    public static ContradictionsResponse of(String transactionId,
                                            List<ContradictionView> contradictions, Instant at) {
        List<ContradictionView> safe = contradictions == null ? List.of() : List.copyOf(contradictions);
        return new ContradictionsResponse(transactionId, safe, safe.size(), at);
    }
}
