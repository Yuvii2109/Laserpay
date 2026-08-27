package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.spi.EvidenceVersionRecord;
import java.util.List;

/**
 * {@code GET /evidence/{evidenceId}/versions}.
 *
 * <p>Two different ledgers, both needed, and conflating them would hide a real distinction:</p>
 *
 * <ul>
 *   <li>{@code chain} is the evidence row lineage. Superseding never overwrites: each new version is
 *       a new evidence id whose parent moves to SUPERSEDED, so the chain is what the merchant thinks
 *       of as "version 1, 2, 3" of a document.</li>
 *   <li>{@code storedVersions} is the append-only record of the objects actually written to MinIO,
 *       each with the sha256 of the bytes that were stored. This is what an auditor checks.</li>
 * </ul>
 *
 * @param currentVersion the head of the chain, the version still in force
 */
public record EvidenceVersionsResponse(
        String evidenceId,
        int currentVersion,
        List<EvidenceView> chain,
        List<EvidenceVersionRecord> storedVersions) {

    public EvidenceVersionsResponse {
        chain = chain == null ? List.of() : List.copyOf(chain);
        storedVersions = storedVersions == null ? List.of() : List.copyOf(storedVersions);
    }
}
