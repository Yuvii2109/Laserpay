"""Model validation: money, time, evidence ids, confidence and enum spelling.

These tests exist to catch the class of bug that silently corrupts financial
data: a float amount that rounds, a naive timestamp that shifts by a timezone,
an enum spelled differently from Java.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from pdei_ai.models.common import Money, utc_now
from pdei_ai.models.enums import (
    CaseStatus,
    ChaosType,
    DisputeReasonCode,
    DisputeStatus,
    EvidenceSource,
    EvidenceStatus,
    EvidenceType,
    GapSeverity,
    GapType,
    InvestigationClassification,
    ReadinessBand,
    RecommendedAction,
    RequirementStrength,
    SafetyDecision,
)
from pdei_ai.models.events import CanonicalEvent
from pdei_ai.models.investigation import (
    Citation,
    ContradictionRef,
    EvidenceRef,
    GapRef,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
)

# --- money ------------------------------------------------------------------


def test_money_rejects_float_amounts() -> None:
    with pytest.raises(ValidationError) as exc:
        Money(amountMinor=12999.5, currency="INR")
    assert "float" in str(exc.value).lower()


def test_money_rejects_bool_amount() -> None:
    with pytest.raises(ValidationError):
        Money(amountMinor=True, currency="INR")


def test_money_normalises_currency_and_formats_for_display_only() -> None:
    money = Money(amountMinor=1_299_900, currency="inr")
    assert money.currency == "INR"
    assert money.amountMinor == 1_299_900
    assert money.to_display_string() == "INR 12,999.00"


def test_money_rejects_bad_currency_codes() -> None:
    for bad in ("IN", "RUPEE", "1NR", ""):
        with pytest.raises(ValidationError):
            Money(amountMinor=100, currency=bad)


def test_money_arithmetic_stays_in_minor_units() -> None:
    a = Money(amountMinor=1000, currency="INR")
    b = Money(amountMinor=250, currency="INR")
    assert a.plus(b).amountMinor == 1250
    assert a.minus(b).amountMinor == 750
    assert a.multiply(3).amountMinor == 3000
    with pytest.raises(ValueError):
        a.multiply(1.5)  # type: ignore[arg-type]


def test_money_refuses_cross_currency_arithmetic() -> None:
    with pytest.raises(ValueError, match="currency mismatch"):
        Money(amountMinor=100, currency="INR").plus(Money(amountMinor=100, currency="USD"))


def test_money_serialises_as_two_fields() -> None:
    wire = Money(amountMinor=500, currency="USD").to_wire()
    assert wire == {"amountMinor": 500, "currency": "USD"}


# --- time -------------------------------------------------------------------


def test_naive_datetimes_are_rejected() -> None:
    with pytest.raises(ValidationError, match="naive datetime"):
        EvidenceRef(
            evidenceId="EV-1",
            type=EvidenceType.INVOICE,
            status=EvidenceStatus.ACTIVE,
            createdAt=datetime(2026, 8, 26, 10, 0, 0),
        )


def test_offset_timestamps_are_normalised_to_utc() -> None:
    item = EvidenceRef(
        evidenceId="EV-1",
        type=EvidenceType.INVOICE,
        status=EvidenceStatus.ACTIVE,
        createdAt="2026-08-26T15:45:00+05:30",
    )
    assert item.createdAt is not None
    assert item.createdAt.tzinfo == UTC
    assert item.createdAt.hour == 10 and item.createdAt.minute == 15


def test_instants_serialise_as_iso8601_utc_with_z() -> None:
    item = EvidenceRef(
        evidenceId="EV-1",
        type=EvidenceType.INVOICE,
        status=EvidenceStatus.ACTIVE,
        createdAt="2026-08-26T10:15:30.123Z",
    )
    assert item.to_wire()["createdAt"] == "2026-08-26T10:15:30.123Z"


# --- evidence ids -----------------------------------------------------------


@pytest.mark.parametrize("bad_id", ["1092", "EVIDENCE-1", "ev-1092", "", "XX-1"])
def test_evidence_ids_must_carry_the_ev_prefix(bad_id: str) -> None:
    with pytest.raises(ValidationError):
        Citation(claim="delivered", evidenceId=bad_id)


def test_valid_evidence_ids_are_accepted() -> None:
    for good in ("EV-1", "EV-1092", "EV-a1b2-c3"):
        assert Citation(claim="delivered", evidenceId=good).evidenceId == good


def test_blank_claims_are_rejected() -> None:
    with pytest.raises(ValidationError):
        Citation(claim="   ", evidenceId="EV-1")


# --- investigation result ---------------------------------------------------


def _metadata() -> ModelMetadata:
    return ModelMetadata(provider="mock", model="m", promptTokens=1, completionTokens=1)


@pytest.mark.parametrize("bad_confidence", [-0.01, 1.01, 97.3])
def test_confidence_must_be_a_probability(bad_confidence: float) -> None:
    with pytest.raises(ValidationError):
        InvestigationResult(
            investigationId="INV-1",
            classification=InvestigationClassification.DEFENDABLE,
            confidence=bad_confidence,
            recommendedAction=RecommendedAction.PREPARE_REPRESENTMENT,
            modelMetadata=_metadata(),
        )


def test_confidence_boundaries_are_inclusive() -> None:
    for value in (0.0, 1.0):
        result = InvestigationResult(
            investigationId="INV-1",
            classification=InvestigationClassification.AMBIGUOUS,
            confidence=value,
            recommendedAction=RecommendedAction.ESCALATE_TO_HUMAN,
            modelMetadata=_metadata(),
        )
        assert result.confidence == value


def test_missing_evidence_holds_types_not_ids() -> None:
    result = InvestigationResult(
        investigationId="INV-1",
        classification=InvestigationClassification.INSUFFICIENT_EVIDENCE,
        confidence=0.4,
        missingEvidence=["DELIVERY_PROOF", "SHIPPING_RECORD"],
        recommendedAction=RecommendedAction.GATHER_MORE_EVIDENCE,
        modelMetadata=_metadata(),
    )
    assert result.missingEvidence == [
        EvidenceType.DELIVERY_PROOF,
        EvidenceType.SHIPPING_RECORD,
    ]
    with pytest.raises(ValidationError):
        InvestigationResult(
            investigationId="INV-1",
            classification=InvestigationClassification.INSUFFICIENT_EVIDENCE,
            confidence=0.4,
            missingEvidence=["EV-1092"],
            recommendedAction=RecommendedAction.GATHER_MORE_EVIDENCE,
            modelMetadata=_metadata(),
        )


def test_supporting_evidence_is_deduped_in_order() -> None:
    result = InvestigationResult(
        investigationId="INV-1",
        classification=InvestigationClassification.DEFENDABLE,
        confidence=0.9,
        supportingEvidence=["EV-2", "EV-1", "EV-2"],
        recommendedAction=RecommendedAction.PREPARE_REPRESENTMENT,
        modelMetadata=_metadata(),
    )
    assert result.supportingEvidence == ["EV-2", "EV-1"]


def test_referenced_evidence_ids_merges_both_sources() -> None:
    result = InvestigationResult(
        investigationId="INV-1",
        classification=InvestigationClassification.DEFENDABLE,
        confidence=0.9,
        supportingEvidence=["EV-1"],
        citations=[Citation(claim="delivered", evidenceId="EV-2")],
        recommendedAction=RecommendedAction.PREPARE_REPRESENTMENT,
        modelMetadata=_metadata(),
    )
    assert result.referenced_evidence_ids() == ["EV-1", "EV-2"]


# --- investigation context --------------------------------------------------


def test_context_ignores_unknown_upstream_fields(defendable_context: InvestigationContext) -> None:
    payload = defendable_context.to_wire()
    payload["someFieldEvidenceCoreAddedLater"] = {"nested": True}
    payload["evidence"][0]["brandNewField"] = 42
    reparsed = InvestigationContext.model_validate(payload)
    assert reparsed.investigationId == defendable_context.investigationId
    assert len(reparsed.evidence) == 3


def test_context_evidence_ids_span_every_source(
    contradictory_context: InvestigationContext,
) -> None:
    ids = contradictory_context.evidence_ids()
    assert {"EV-1092", "EV-8821", "EV-3300"} <= ids


def test_contradictions_may_reference_domain_entity_ids() -> None:
    """ContradictionDetector.ref(...) falls back to the raw entity id when no evidence
    documents the entity, so ``left``/``right`` are not always ``EV-`` ids. Rejecting
    them would 422 the whole investigate call for exactly the contradiction-bearing
    cases admission control is meant to route to the model (contract 9.4)."""
    contradiction = ContradictionRef(
        left="DLV-1",
        right="SHP-1",
        field="deliveredAt",
        detail="delivery record and shipment record disagree",
        severity=GapSeverity.HIGH,
    )
    assert contradiction.left == "DLV-1"
    assert contradiction.right == "SHP-1"

    context = InvestigationContext(
        investigationId="INV-0000009",
        transactionId="TX-77",
        evidence=[
            EvidenceRef(
                evidenceId="EV-3300",
                type=EvidenceType.PAYMENT_PROOF,
                status=EvidenceStatus.ACTIVE,
            )
        ],
        contradictions=[
            contradiction,
            ContradictionRef(left="TX-77", right="TX-77", field="refundAmountMinor"),
        ],
        gaps=[
            GapRef(
                type=GapType.CONTRADICTORY,
                severity=GapSeverity.HIGH,
                evidenceId="DLV-1",
                detail="delivery record and shipment record disagree",
            )
        ],
    )
    # Round-trips over the wire the way HttpAiReasoningClient posts it.
    assert InvestigationContext.model_validate(context.to_wire()).gaps[0].evidenceId == "DLV-1"
    # Non-evidence ids never widen the citable universe.
    assert context.evidence_ids() == {"EV-3300"}


def test_context_deadline_helpers() -> None:
    now = utc_now()
    context = InvestigationContext(investigationId="INV-1", deadlineAt=now + timedelta(hours=10))
    hours = context.hours_until_deadline(now)
    assert hours is not None and 9.9 < hours < 10.1
    assert not context.past_deadline(now)
    assert context.past_deadline(now + timedelta(hours=11))


def test_investigation_id_must_carry_the_inv_prefix() -> None:
    with pytest.raises(ValidationError):
        InvestigationContext(investigationId="1234")


# --- enums ------------------------------------------------------------------


def test_enum_members_match_the_contract_spelling() -> None:
    """Section 6 of the platform contract, transcribed. Do not "fix" these."""
    assert [member.value for member in EvidenceType] == [
        "PAYMENT_PROOF",
        "INVOICE",
        "ORDER_RECORD",
        "SHIPPING_RECORD",
        "DELIVERY_PROOF",
        "REFUND_RECEIPT",
        "CUSTOMER_COMMUNICATION",
        "MERCHANT_POLICY",
        "TERMS_OF_SERVICE",
        "AVS_CVV_RESULT",
        "DEVICE_FINGERPRINT",
        "PRIOR_TRANSACTION_HISTORY",
        "SIGNED_CONTRACT",
    ]
    assert [member.value for member in EvidenceStatus] == [
        "PENDING",
        "ACTIVE",
        "EXPIRING",
        "EXPIRED",
        "INVALIDATED",
        "SUPERSEDED",
    ]
    assert [member.value for member in EvidenceSource] == [
        "PSP_ADAPTER",
        "ORDER_SYSTEM",
        "LOGISTICS",
        "CRM",
        "DOCUMENT_UPLOAD",
        "MERCHANT_PORTAL",
        "SIMULATOR",
        "INTERNAL_DERIVED",
    ]
    assert [member.value for member in DisputeReasonCode] == [
        "GOODS_NOT_RECEIVED",
        "SERVICE_NOT_RENDERED",
        "PRODUCT_NOT_AS_DESCRIBED",
        "DUPLICATE_PROCESSING",
        "CREDIT_NOT_PROCESSED",
        "SUBSCRIPTION_CANCELLED",
        "FRAUDULENT_TRANSACTION",
        "UNRECOGNIZED_TRANSACTION",
        "INCORRECT_AMOUNT",
        "PAID_BY_OTHER_MEANS",
    ]
    assert [member.value for member in DisputeStatus] == [
        "OPEN",
        "EVIDENCE_GATHERING",
        "UNDER_INVESTIGATION",
        "AWAITING_HUMAN_REVIEW",
        "REPRESENTMENT_PREPARED",
        "SUBMITTED",
        "WON",
        "LOST",
        "EXPIRED",
        "WITHDRAWN",
    ]
    assert [member.value for member in CaseStatus] == [
        "CREATED",
        "ASSEMBLING",
        "INVESTIGATING",
        "AWAITING_EVIDENCE",
        "AWAITING_APPROVAL",
        "PREPARED",
        "SUBMITTED",
        "CLOSED",
        "FAILED",
    ]
    assert [member.value for member in RequirementStrength] == [
        "MANDATORY",
        "RECOMMENDED",
        "OPTIONAL",
        "PROHIBITED",
    ]
    assert [member.value for member in GapType] == [
        "MISSING",
        "EXPIRED",
        "EXPIRING_SOON",
        "CONTRADICTORY",
        "UNVERIFIABLE_PROVENANCE",
        "LOW_QUALITY",
        "VERSION_CONFLICT",
    ]
    assert [member.value for member in GapSeverity] == ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    assert [member.value for member in InvestigationClassification] == [
        "DEFENDABLE",
        "WEAK",
        "INDEFENSIBLE",
        "INSUFFICIENT_EVIDENCE",
        "AMBIGUOUS",
    ]
    assert [member.value for member in RecommendedAction] == [
        "PREPARE_REPRESENTMENT",
        "GATHER_MORE_EVIDENCE",
        "ACCEPT_LIABILITY",
        "ESCALATE_TO_HUMAN",
        "REQUEST_POLICY_REVIEW",
    ]
    assert [member.value for member in SafetyDecision] == ["ALLOW", "ALLOW_WITH_REVIEW", "DENY"]
    assert len(list(ChaosType)) == 13


def test_readiness_bands_follow_the_contract_thresholds() -> None:
    assert ReadinessBand.from_score(100) is ReadinessBand.READY
    assert ReadinessBand.from_score(90) is ReadinessBand.READY
    assert ReadinessBand.from_score(89) is ReadinessBand.NEARLY_READY
    assert ReadinessBand.from_score(75) is ReadinessBand.NEARLY_READY
    assert ReadinessBand.from_score(74) is ReadinessBand.AT_RISK
    assert ReadinessBand.from_score(50) is ReadinessBand.AT_RISK
    assert ReadinessBand.from_score(49) is ReadinessBand.NOT_READY


def test_requirement_weights_match_the_contract() -> None:
    assert RequirementStrength.MANDATORY.weight == 3
    assert RequirementStrength.RECOMMENDED.weight == 2
    assert RequirementStrength.OPTIONAL.weight == 1
    assert RequirementStrength.PROHIBITED.weight == 0


def test_enum_parsing_is_exact_match_only() -> None:
    assert EvidenceType.from_wire("DELIVERY_PROOF") is EvidenceType.DELIVERY_PROOF
    with pytest.raises(ValueError, match="unknown EvidenceType"):
        EvidenceType.from_wire("delivery_proof")


# --- canonical event --------------------------------------------------------


def test_canonical_event_partition_key_matches_the_contract() -> None:
    event = CanonicalEvent(
        eventId="11111111-2222-3333-4444-555555555555",
        eventType="PaymentCaptured",
        schemaVersion=1,
        aggregateType="PAYMENT",
        aggregateId="PAY-000123",
        merchantId="MER-0001",
        occurredAt="2026-08-26T10:15:30.123Z",
        observedAt="2026-08-26T10:15:31.004Z",
        source="PSP_ADAPTER",
        idempotencyKey="stable",
        payload={"amountMinor": 1000, "currency": "INR"},
    )
    assert event.partition_key == "MER-0001:PAY-000123"
    assert event.eventType.aggregate_type.value == "PAYMENT"
    assert not event.is_late
