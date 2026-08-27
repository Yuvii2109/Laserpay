package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.policy.PolicyView;

import java.util.List;
import java.util.Optional;

/**
 * The injected data port that lets {@link ReadinessEngine#compute} run against a database while
 * {@link ReadinessEngine#score} stays a pure function.
 *
 * <p>Tests supply a hand-built implementation; production uses {@code DefaultReadinessDataProvider},
 * which delegates to the SPI ports.</p>
 */
public interface ReadinessDataProvider {

    /** Owning merchant of a transaction. */
    Optional<String> merchantIdFor(String transactionId);

    /** Every evidence artifact attached to the transaction, whatever its status. */
    List<EvidenceView> evidenceFor(String transactionId);

    /** Financial facts used for contradiction detection. */
    Optional<TransactionFacts> factsFor(String transactionId);

    /**
     * The policy in force. When {@code reasonCode} is null the implementation must return the
     * merchant baseline profile (contract 7).
     */
    PolicyView policyFor(String merchantId, DisputeReasonCode reasonCode);
}
