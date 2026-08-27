"""Shared fixtures.

Everything here is deterministic and offline. No test touches Redis, Gemini or
the gateway: the mock reasoner covers the provider boundary and respx covers the
tool surface, which is exactly the isolation the platform's "runs locally at
zero cost" requirement asks for.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from pdei_ai.config import Settings
from pdei_ai.models.common import Money, utc_now
from pdei_ai.models.enums import (
    DisputeReasonCode,
    EvidenceSource,
    EvidenceStatus,
    EvidenceType,
    GapSeverity,
    GapType,
    RequirementStrength,
)
from pdei_ai.models.investigation import (
    ContradictionRef,
    EvidenceRef,
    GapRef,
    HistoricalContext,
    InvestigationContext,
    PolicyConstraints,
    RequirementRef,
    TimelineEntry,
)

GATEWAY_BASE_URL = "http://api-gateway-service:8080"


@pytest.fixture
def settings() -> Settings:
    """Offline settings: mock provider, no Redis, tools pointed at a respx target."""
    return Settings(
        provider="mock",
        fallback_chain=("mock",),
        api_base_url=GATEWAY_BASE_URL,
        service_token="test-service-token",
        tools_enabled=True,
        tool_timeout_seconds=1.0,
        tool_max_retries=0,
        max_tool_calls=4,
        redis_url="",  # no budget gate; the budget path has its own tests
        tracing_enabled=False,
        log_level="WARNING",
        require_service_token=False,
    )


def _evidence(
    evidence_id: str,
    evidence_type: EvidenceType,
    status: EvidenceStatus = EvidenceStatus.ACTIVE,
    **extra: Any,
) -> EvidenceRef:
    return EvidenceRef(
        evidenceId=evidence_id,
        type=evidence_type,
        status=status,
        sha256="a" * 64,
        createdAt=utc_now() - timedelta(days=3),
        summary=f"{evidence_type.value} record",
        version=1,
        source=EvidenceSource.LOGISTICS,
        provenanceVerified=True,
        **extra,
    )


@pytest.fixture
def defendable_context() -> InvestigationContext:
    """A clean GOODS_NOT_RECEIVED case: every mandatory requirement satisfied."""
    return InvestigationContext(
        investigationId="INV-0000001",
        caseId="CASE-0000001",
        disputeId="DSP-0000001",
        merchantId="MER-0001",
        transactionId="TX-0000123",
        reasonCode=DisputeReasonCode.GOODS_NOT_RECEIVED,
        disputeAmount=Money(amountMinor=1_299_900, currency="INR"),
        deadlineAt=utc_now() + timedelta(days=10),
        transactionSummary={"status": "CAPTURED", "customerId": "CUS-0009"},
        evidence=[
            _evidence("EV-1092", EvidenceType.DELIVERY_PROOF),
            _evidence("EV-8821", EvidenceType.SHIPPING_RECORD),
            _evidence("EV-3300", EvidenceType.PAYMENT_PROOF),
        ],
        requirements=[
            RequirementRef(
                type=EvidenceType.DELIVERY_PROOF,
                strength=RequirementStrength.MANDATORY,
                satisfied=True,
                satisfyingEvidenceIds=["EV-1092"],
                weight=3,
            ),
            RequirementRef(
                type=EvidenceType.SHIPPING_RECORD,
                strength=RequirementStrength.MANDATORY,
                satisfied=True,
                satisfyingEvidenceIds=["EV-8821"],
                weight=3,
            ),
            RequirementRef(
                type=EvidenceType.CUSTOMER_COMMUNICATION,
                strength=RequirementStrength.RECOMMENDED,
                satisfied=False,
                weight=2,
            ),
        ],
        gaps=[],
        contradictions=[],
        policyConstraints=PolicyConstraints(
            autoPrepareMinConfidence=0.90, maxContradictions=0, prohibitedEvidenceTypes=[]
        ),
        timeline=[
            TimelineEntry(
                at=utc_now() - timedelta(days=6),
                eventType="ShipmentDelivered",
                summary="Parcel delivered and signed for",
            )
        ],
        historicalContext=HistoricalContext(merchantWinRate=0.71, similarCases=14),
    )


@pytest.fixture
def contradictory_context(defendable_context: InvestigationContext) -> InvestigationContext:
    """The same case, with the delivery date disputed between two documents."""
    return defendable_context.model_copy(
        update={
            "investigationId": "INV-0000002",
            "contradictions": [
                ContradictionRef(
                    left="EV-1092",
                    right="EV-8821",
                    field="deliveredAt",
                    detail="delivery proof and shipping record disagree by three days",
                    severity=GapSeverity.HIGH,
                    leftValue="2026-08-14",
                    rightValue="2026-08-17",
                )
            ],
        }
    )


@pytest.fixture
def gapped_context(defendable_context: InvestigationContext) -> InvestigationContext:
    """A case missing mandatory delivery proof."""
    return defendable_context.model_copy(
        update={
            "investigationId": "INV-0000003",
            "evidence": [_evidence("EV-3300", EvidenceType.PAYMENT_PROOF)],
            "requirements": [
                RequirementRef(
                    type=EvidenceType.DELIVERY_PROOF,
                    strength=RequirementStrength.MANDATORY,
                    satisfied=False,
                    weight=3,
                ),
            ],
            "gaps": [
                GapRef(
                    type=GapType.MISSING,
                    evidenceType=EvidenceType.DELIVERY_PROOF,
                    severity=GapSeverity.HIGH,
                    detail="no delivery proof on file",
                )
            ],
        }
    )


@pytest.fixture
def empty_context(defendable_context: InvestigationContext) -> InvestigationContext:
    """A case with no evidence at all."""
    return defendable_context.model_copy(
        update={
            "investigationId": "INV-0000004",
            "evidence": [],
            "requirements": [],
            "gaps": [],
            "contradictions": [],
        }
    )
