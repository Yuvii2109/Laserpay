package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.policy.DefaultPolicyMatrix;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;

import java.util.List;
import java.util.Optional;

/**
 * Production {@link ReadinessDataProvider}: reads the transaction, its evidence and the applicable
 * policy through the SPI ports.
 *
 * <p>When no reason code is supplied it builds the merchant baseline profile (contract 7) rather
 * than picking an arbitrary reason code.</p>
 */
public class DefaultReadinessDataProvider implements ReadinessDataProvider {

    private final TransactionRepositoryPort transactions;
    private final EvidenceRepositoryPort evidence;
    private final PolicyEngine policyEngine;

    public DefaultReadinessDataProvider(TransactionRepositoryPort transactions,
                                        EvidenceRepositoryPort evidence,
                                        PolicyEngine policyEngine) {
        this.transactions = transactions;
        this.evidence = evidence;
        this.policyEngine = policyEngine;
    }

    @Override
    public Optional<String> merchantIdFor(String transactionId) {
        return transactions.findMerchantId(transactionId);
    }

    @Override
    public List<EvidenceView> evidenceFor(String transactionId) {
        return evidence.findByTransactionId(transactionId);
    }

    @Override
    public Optional<TransactionFacts> factsFor(String transactionId) {
        return transactions.findFacts(transactionId);
    }

    @Override
    public PolicyView policyFor(String merchantId, DisputeReasonCode reasonCode) {
        PolicyView policy = policyEngine.applicablePolicy(merchantId, reasonCode);
        if (reasonCode != null || !policy.requirements().isEmpty()) {
            return policy;
        }
        // Baseline profile: the union of MANDATORY requirements across the merchant's top reason codes.
        return new PolicyView(policy.policyId(), policy.policyVersionId(), policy.version(), merchantId,
                null, policyEngine.baselineRequirements(merchantId), policy.permittedActions(),
                policy.prohibitedEvidenceTypes(), policy.autoPrepareMinConfidence(),
                policy.maxContradictions(), policy.minReadinessScoreForAutoPrepare(),
                policy.humanReviewAboveAmountMinor(), policy.currency(), policy.autoSubmitEnabled(),
                policy.responseWindowDays() <= 0
                        ? DefaultPolicyMatrix.DEFAULT_RESPONSE_WINDOW_DAYS : policy.responseWindowDays(),
                policy.expiringSoonDays() <= 0
                        ? DefaultPolicyMatrix.DEFAULT_EXPIRING_SOON_DAYS : policy.expiringSoonDays(),
                policy.createdBy(), policy.checksum(), policy.effectiveFrom(), policy.effectiveTo(),
                policy.defaultPolicy());
    }
}
