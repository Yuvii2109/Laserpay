package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RequirementStrength;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPolicyMatrixTest {

    @ParameterizedTest
    @EnumSource(DisputeReasonCode.class)
    @DisplayName("every reason code has a seeded requirement set with at least one mandatory type")
    void everyReasonCodeIsCovered(DisputeReasonCode reasonCode) {
        List<RequirementSpec> requirements = DefaultPolicyMatrix.requirements(reasonCode);

        assertThat(requirements).isNotEmpty();
        assertThat(requirements).anyMatch(RequirementSpec::isMandatory);
        assertThat(requirements).allMatch(spec -> spec.type() != null && spec.strength() != null);
        assertThat(requirements).extracting(RequirementSpec::type).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("GOODS_NOT_RECEIVED requires payment proof, invoice, shipping, delivery and policy")
    void goodsNotReceivedMatrix() {
        List<EvidenceType> mandatory = DefaultPolicyMatrix.requirements(
                        DisputeReasonCode.GOODS_NOT_RECEIVED).stream()
                .filter(RequirementSpec::isMandatory)
                .map(RequirementSpec::type)
                .toList();

        assertThat(mandatory).containsExactlyInAnyOrder(
                EvidenceType.PAYMENT_PROOF,
                EvidenceType.INVOICE,
                EvidenceType.SHIPPING_RECORD,
                EvidenceType.DELIVERY_PROOF,
                EvidenceType.MERCHANT_POLICY);
    }

    @Test
    @DisplayName("FRAUDULENT_TRANSACTION requires the card-present risk signals")
    void fraudulentTransactionMatrix() {
        List<EvidenceType> mandatory = DefaultPolicyMatrix.requirements(
                        DisputeReasonCode.FRAUDULENT_TRANSACTION).stream()
                .filter(RequirementSpec::isMandatory)
                .map(RequirementSpec::type)
                .toList();

        assertThat(mandatory).contains(EvidenceType.AVS_CVV_RESULT, EvidenceType.DEVICE_FINGERPRINT);
    }

    @Test
    @DisplayName("the baseline profile is the union of the mandatory requirements of the top codes")
    void baselineProfileIsAUnion() {
        List<RequirementSpec> baseline = DefaultPolicyMatrix.baselineRequirements(List.of(
                DisputeReasonCode.GOODS_NOT_RECEIVED, DisputeReasonCode.CREDIT_NOT_PROCESSED));

        List<EvidenceType> mandatory = baseline.stream()
                .filter(RequirementSpec::isMandatory)
                .map(RequirementSpec::type)
                .toList();

        assertThat(mandatory).contains(EvidenceType.PAYMENT_PROOF, EvidenceType.DELIVERY_PROOF,
                EvidenceType.REFUND_RECEIPT, EvidenceType.MERCHANT_POLICY);
        assertThat(mandatory).doesNotHaveDuplicates();
        // A type that is mandatory somewhere must not also appear as recommended in the union.
        assertThat(baseline).filteredOn(RequirementSpec::isRecommended)
                .extracting(RequirementSpec::type)
                .doesNotContainAnyElementsOf(mandatory);
    }

    @Test
    @DisplayName("requirement weights follow the strength ladder 3 / 2 / 1 / 0")
    void weightsFollowStrength() {
        assertThat(RequirementSpec.of(EvidenceType.INVOICE, RequirementStrength.MANDATORY)
                .effectiveWeight()).isEqualTo(3);
        assertThat(RequirementSpec.of(EvidenceType.INVOICE, RequirementStrength.RECOMMENDED)
                .effectiveWeight()).isEqualTo(2);
        assertThat(RequirementSpec.of(EvidenceType.INVOICE, RequirementStrength.OPTIONAL)
                .effectiveWeight()).isEqualTo(1);
        assertThat(RequirementSpec.of(EvidenceType.INVOICE, RequirementStrength.PROHIBITED)
                .effectiveWeight()).isZero();
    }

    @Test
    @DisplayName("expiry defaults: risk signals go stale in months, financial records last for years")
    void expiryDefaults() {
        assertThat(DefaultPolicyMatrix.defaultMaxAgeDays(EvidenceType.AVS_CVV_RESULT)).isEqualTo(180);
        assertThat(DefaultPolicyMatrix.defaultMaxAgeDays(EvidenceType.DELIVERY_PROOF)).isEqualTo(540);
        assertThat(DefaultPolicyMatrix.defaultMaxAgeDays(EvidenceType.PAYMENT_PROOF)).isEqualTo(3650);
    }

    @Test
    @DisplayName("the default policy carries the contract automation thresholds")
    void defaultPolicyThresholds() {
        PolicyView policy = DefaultPolicyMatrix.defaultPolicy("MER-0001",
                DisputeReasonCode.GOODS_NOT_RECEIVED, null);

        assertThat(policy.autoPrepareMinConfidence()).isEqualTo(0.90d);
        assertThat(policy.maxContradictions()).isZero();
        assertThat(policy.prohibitedEvidenceTypes()).isEmpty();
        assertThat(policy.defaultPolicy()).isTrue();
        assertThat(policy.expiringSoonDays()).isEqualTo(7);
        assertThat(policy.toConstraints().autoPrepareMinConfidence()).isEqualTo(0.90d);
    }
}
