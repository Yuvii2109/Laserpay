package com.laserpay.pdei.orchestrator.listener;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.orchestrator.persistence.CaseRow;
import com.laserpay.pdei.orchestrator.persistence.CaseWriter;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Turns a dispute id into the one case id that dispute may ever have.
 *
 * <p>The workflow id is {@code case-{caseId}} (PLATFORM-CONTRACT section 10), and Temporal uses it
 * to reject a second start. That only protects against duplicates if the caseId is a <b>function of
 * the dispute</b> rather than a fresh random id - otherwise two deliveries of the same
 * {@code DisputeCreated} would produce two different workflow ids and two competing cases.</p>
 *
 * <p>So: {@code CASE-} + the first twelve hex characters of {@code sha256(disputeId)}, upper-cased.
 * Deterministic, satisfies the {@code case_id LIKE 'CASE-%'} check constraint of
 * {@code V5__disputes.sql}, and short enough for the {@code VARCHAR(64)} column. Collision risk over
 * 48 bits is negligible for this workload, and a collision would be caught by the unique constraint
 * rather than corrupting a case.</p>
 *
 * <p>A case row that already exists for the dispute always wins: an operator or an earlier run may
 * have created one with a different id, and adopting it is more correct than opening a rival.</p>
 */
@Component
public class CaseIdResolver {

    /** 12 hex characters = 48 bits of the dispute-id digest. */
    public static final int DIGEST_LENGTH = 12;

    private final CaseWriter caseWriter;

    public CaseIdResolver(CaseWriter caseWriter) {
        this.caseWriter = caseWriter;
    }

    /** The case id to use for this dispute: the existing one if there is one, else the derived one. */
    public String resolve(String disputeId) {
        Optional<CaseRow> existing = caseWriter.findByDispute(disputeId);
        return existing.map(CaseRow::caseId).orElseGet(() -> derive(disputeId));
    }

    /** The case id this dispute would get if no case existed. Pure function, no database access. */
    public static String derive(String disputeId) {
        if (disputeId == null || disputeId.isBlank()) {
            throw new IllegalArgumentException("disputeId is required to derive a case id");
        }
        return IdPrefix.CASE + Hashes.sha256Hex(disputeId)
                .substring(0, DIGEST_LENGTH)
                .toUpperCase(Locale.ROOT);
    }
}
