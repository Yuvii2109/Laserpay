package com.laserpay.pdei.core.safety;

import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.policy.PolicyView;

/**
 * Everything {@link SafetyGate} needs beyond the validator: the readiness of the transaction, the
 * money at stake and the policy in force.
 */
public record GateInput(
        String caseId,
        String disputeId,
        String transactionId,
        String merchantId,
        PolicyView policy,
        ReadinessSnapshot readiness,
        Money disputeAmount,
        boolean pastDeadline) {

    public ValidationInput toValidationInput() {
        return new ValidationInput(caseId, disputeId, transactionId, merchantId, policy,
                readiness == null ? java.util.List.of() : readiness.requirements());
    }
}
