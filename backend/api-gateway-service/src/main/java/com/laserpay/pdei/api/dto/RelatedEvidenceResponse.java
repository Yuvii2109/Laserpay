package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.EvidenceView;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /ai-tools/evidence/related?transactionId=...}.
 *
 * <p>Every artifact linked to the transaction is returned, including superseded and invalidated
 * ones, with {@code usableCount} separating what may actually be cited from what merely exists. The
 * model needs to see the whole set: "there is a delivery proof, but it was invalidated" is a
 * materially different fact from "there is no delivery proof", and hiding the invalidated row would
 * make those two indistinguishable.</p>
 */
public record RelatedEvidenceResponse(
        String transactionId,
        List<EvidenceView> evidence,
        int count,
        int usableCount,
        Instant generatedAt) {

    public RelatedEvidenceResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static RelatedEvidenceResponse of(String transactionId, List<EvidenceView> evidence, Instant at) {
        List<EvidenceView> safe = evidence == null ? List.of() : List.copyOf(evidence);
        int usable = (int) safe.stream().filter(EvidenceView::isUsable).count();
        return new RelatedEvidenceResponse(transactionId, safe, safe.size(), usable, at);
    }
}
