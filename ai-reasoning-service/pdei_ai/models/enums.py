"""Domain enums - spelling is normative (platform contract section 6).

Every member below is spelled exactly as it appears in
``docs/PLATFORM-CONTRACT.md`` section 6 and in ``com.laserpay.pdei.common.domain``.
Never add a synonym, never lowercase a member, never "fix" a spelling here: a
divergence silently breaks Java <-> Python <-> TypeScript parity.

They subclass ``str`` so a member serialises to its own name in JSON and so
``EvidenceType.DELIVERY_PROOF == "DELIVERY_PROOF"`` holds.
"""

from __future__ import annotations

from enum import Enum


class _WireEnum(str, Enum):
    """Base for contract enums: value == member name, exact-match parsing."""

    @classmethod
    def from_wire(cls, raw: str) -> _WireEnum:
        """Exact-name lookup; raises ``ValueError`` on anything else.

        Deliberately case sensitive. Tolerating ``defendable`` today means
        tolerating a model that invented a classification tomorrow.
        """
        try:
            return cls(raw)
        except ValueError as exc:  # pragma: no cover - message only
            allowed = ", ".join(member.value for member in cls)
            raise ValueError(f"unknown {cls.__name__} [{raw}]; allowed: {allowed}") from exc


# --- evidence ---------------------------------------------------------------


class EvidenceType(_WireEnum):
    PAYMENT_PROOF = "PAYMENT_PROOF"
    INVOICE = "INVOICE"
    ORDER_RECORD = "ORDER_RECORD"
    SHIPPING_RECORD = "SHIPPING_RECORD"
    DELIVERY_PROOF = "DELIVERY_PROOF"
    REFUND_RECEIPT = "REFUND_RECEIPT"
    CUSTOMER_COMMUNICATION = "CUSTOMER_COMMUNICATION"
    MERCHANT_POLICY = "MERCHANT_POLICY"
    TERMS_OF_SERVICE = "TERMS_OF_SERVICE"
    AVS_CVV_RESULT = "AVS_CVV_RESULT"
    DEVICE_FINGERPRINT = "DEVICE_FINGERPRINT"
    PRIOR_TRANSACTION_HISTORY = "PRIOR_TRANSACTION_HISTORY"
    SIGNED_CONTRACT = "SIGNED_CONTRACT"


class EvidenceStatus(_WireEnum):
    PENDING = "PENDING"
    ACTIVE = "ACTIVE"
    EXPIRING = "EXPIRING"
    EXPIRED = "EXPIRED"
    INVALIDATED = "INVALIDATED"
    SUPERSEDED = "SUPERSEDED"

    @property
    def is_usable(self) -> bool:
        """ACTIVE and EXPIRING evidence may still be cited; nothing else may."""
        return self in (EvidenceStatus.ACTIVE, EvidenceStatus.EXPIRING)


class EvidenceSource(_WireEnum):
    PSP_ADAPTER = "PSP_ADAPTER"
    ORDER_SYSTEM = "ORDER_SYSTEM"
    LOGISTICS = "LOGISTICS"
    CRM = "CRM"
    DOCUMENT_UPLOAD = "DOCUMENT_UPLOAD"
    MERCHANT_PORTAL = "MERCHANT_PORTAL"
    SIMULATOR = "SIMULATOR"
    INTERNAL_DERIVED = "INTERNAL_DERIVED"


# --- disputes and cases -----------------------------------------------------


class DisputeReasonCode(_WireEnum):
    GOODS_NOT_RECEIVED = "GOODS_NOT_RECEIVED"
    SERVICE_NOT_RENDERED = "SERVICE_NOT_RENDERED"
    PRODUCT_NOT_AS_DESCRIBED = "PRODUCT_NOT_AS_DESCRIBED"
    DUPLICATE_PROCESSING = "DUPLICATE_PROCESSING"
    CREDIT_NOT_PROCESSED = "CREDIT_NOT_PROCESSED"
    SUBSCRIPTION_CANCELLED = "SUBSCRIPTION_CANCELLED"
    FRAUDULENT_TRANSACTION = "FRAUDULENT_TRANSACTION"
    UNRECOGNIZED_TRANSACTION = "UNRECOGNIZED_TRANSACTION"
    INCORRECT_AMOUNT = "INCORRECT_AMOUNT"
    PAID_BY_OTHER_MEANS = "PAID_BY_OTHER_MEANS"


class DisputeStatus(_WireEnum):
    OPEN = "OPEN"
    EVIDENCE_GATHERING = "EVIDENCE_GATHERING"
    UNDER_INVESTIGATION = "UNDER_INVESTIGATION"
    AWAITING_HUMAN_REVIEW = "AWAITING_HUMAN_REVIEW"
    REPRESENTMENT_PREPARED = "REPRESENTMENT_PREPARED"
    SUBMITTED = "SUBMITTED"
    WON = "WON"
    LOST = "LOST"
    EXPIRED = "EXPIRED"
    WITHDRAWN = "WITHDRAWN"


class CaseStatus(_WireEnum):
    CREATED = "CREATED"
    ASSEMBLING = "ASSEMBLING"
    INVESTIGATING = "INVESTIGATING"
    AWAITING_EVIDENCE = "AWAITING_EVIDENCE"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    PREPARED = "PREPARED"
    SUBMITTED = "SUBMITTED"
    CLOSED = "CLOSED"
    FAILED = "FAILED"


# --- readiness --------------------------------------------------------------


class ReadinessBand(_WireEnum):
    READY = "READY"
    NEARLY_READY = "NEARLY_READY"
    AT_RISK = "AT_RISK"
    NOT_READY = "NOT_READY"

    @classmethod
    def from_score(cls, score: int) -> ReadinessBand:
        """Bands from contract section 6: >=90, 75-89, 50-74, <50."""
        if score >= 90:
            return cls.READY
        if score >= 75:
            return cls.NEARLY_READY
        if score >= 50:
            return cls.AT_RISK
        return cls.NOT_READY


class RequirementStrength(_WireEnum):
    MANDATORY = "MANDATORY"
    RECOMMENDED = "RECOMMENDED"
    OPTIONAL = "OPTIONAL"
    PROHIBITED = "PROHIBITED"

    @property
    def weight(self) -> int:
        """Default requirement weights from contract section 7: 3/2/1/0."""
        return _REQUIREMENT_WEIGHTS[self]


class GapType(_WireEnum):
    MISSING = "MISSING"
    EXPIRED = "EXPIRED"
    EXPIRING_SOON = "EXPIRING_SOON"
    CONTRADICTORY = "CONTRADICTORY"
    UNVERIFIABLE_PROVENANCE = "UNVERIFIABLE_PROVENANCE"
    LOW_QUALITY = "LOW_QUALITY"
    VERSION_CONFLICT = "VERSION_CONFLICT"


class GapSeverity(_WireEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"

    @property
    def is_blocking(self) -> bool:
        return self in (GapSeverity.HIGH, GapSeverity.CRITICAL)


# --- AI outcome vocabulary --------------------------------------------------


class InvestigationClassification(_WireEnum):
    DEFENDABLE = "DEFENDABLE"
    WEAK = "WEAK"
    INDEFENSIBLE = "INDEFENSIBLE"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    AMBIGUOUS = "AMBIGUOUS"


class RecommendedAction(_WireEnum):
    PREPARE_REPRESENTMENT = "PREPARE_REPRESENTMENT"
    GATHER_MORE_EVIDENCE = "GATHER_MORE_EVIDENCE"
    ACCEPT_LIABILITY = "ACCEPT_LIABILITY"
    ESCALATE_TO_HUMAN = "ESCALATE_TO_HUMAN"
    REQUEST_POLICY_REVIEW = "REQUEST_POLICY_REVIEW"


class SafetyDecision(_WireEnum):
    ALLOW = "ALLOW"
    ALLOW_WITH_REVIEW = "ALLOW_WITH_REVIEW"
    DENY = "DENY"


class ChaosType(_WireEnum):
    DUPLICATE_EVENT = "DUPLICATE_EVENT"
    DELAYED_EVENT = "DELAYED_EVENT"
    OUT_OF_ORDER_EVENT = "OUT_OF_ORDER_EVENT"
    DROP_EVENT = "DROP_EVENT"
    DELETE_EVIDENCE = "DELETE_EVIDENCE"
    CORRUPT_EVIDENCE_HASH = "CORRUPT_EVIDENCE_HASH"
    EXPIRE_EVIDENCE = "EXPIRE_EVIDENCE"
    CONFLICTING_EVIDENCE = "CONFLICTING_EVIDENCE"
    KILL_WORKER = "KILL_WORKER"
    RESTART_CONSUMER = "RESTART_CONSUMER"
    REPLAY_EVENTS = "REPLAY_EVENTS"
    INJECT_DISPUTE = "INJECT_DISPUTE"
    SLOW_CONSUMER = "SLOW_CONSUMER"


# --- event envelope vocabulary (contract section 3) -------------------------


class AggregateType(_WireEnum):
    MERCHANT = "MERCHANT"
    CUSTOMER = "CUSTOMER"
    TRANSACTION = "TRANSACTION"
    PAYMENT = "PAYMENT"
    ORDER = "ORDER"
    SHIPMENT = "SHIPMENT"
    DELIVERY = "DELIVERY"
    REFUND = "REFUND"
    COMMUNICATION = "COMMUNICATION"
    EVIDENCE = "EVIDENCE"
    POLICY = "POLICY"
    DISPUTE = "DISPUTE"
    CASE = "CASE"


class EventSource(_WireEnum):
    PSP_ADAPTER = "PSP_ADAPTER"
    ORDER_SYSTEM = "ORDER_SYSTEM"
    LOGISTICS = "LOGISTICS"
    CRM = "CRM"
    SIMULATOR = "SIMULATOR"
    INTERNAL = "INTERNAL"
    MERCHANT_PORTAL = "MERCHANT_PORTAL"


class EventType(_WireEnum):
    """Canonical event types (contract 3.1). PascalCase on the wire."""

    PaymentCreated = "PaymentCreated"
    PaymentAuthorized = "PaymentAuthorized"
    PaymentCaptured = "PaymentCaptured"
    PaymentFailed = "PaymentFailed"
    OrderCreated = "OrderCreated"
    OrderFulfilled = "OrderFulfilled"
    OrderCancelled = "OrderCancelled"
    ShipmentCreated = "ShipmentCreated"
    ShipmentDispatched = "ShipmentDispatched"
    ShipmentDelivered = "ShipmentDelivered"
    RefundCreated = "RefundCreated"
    RefundProcessed = "RefundProcessed"
    CommunicationCreated = "CommunicationCreated"
    CommunicationReceived = "CommunicationReceived"
    EvidenceAdded = "EvidenceAdded"
    EvidenceExpired = "EvidenceExpired"
    EvidenceInvalidated = "EvidenceInvalidated"
    DisputeCreated = "DisputeCreated"
    DisputeUpdated = "DisputeUpdated"
    DisputeClosed = "DisputeClosed"
    ReadinessRecomputed = "ReadinessRecomputed"
    ReadinessGapDetected = "ReadinessGapDetected"
    CaseOpened = "CaseOpened"
    CaseEvidenceAttached = "CaseEvidenceAttached"
    CaseInvestigated = "CaseInvestigated"
    CasePrepared = "CasePrepared"
    CaseEscalated = "CaseEscalated"
    CaseSubmitted = "CaseSubmitted"
    CaseClosed = "CaseClosed"
    AuditRecorded = "AuditRecorded"

    @property
    def aggregate_type(self) -> AggregateType:
        return _EVENT_AGGREGATE[self]

    @property
    def is_evidence_event(self) -> bool:
        return self.aggregate_type is AggregateType.EVIDENCE

    @property
    def is_dispute_event(self) -> bool:
        return self.aggregate_type is AggregateType.DISPUTE

    @property
    def is_case_event(self) -> bool:
        return self.aggregate_type is AggregateType.CASE

    @property
    def is_readiness_event(self) -> bool:
        return self in (EventType.ReadinessRecomputed, EventType.ReadinessGapDetected)


_REQUIREMENT_WEIGHTS: dict[RequirementStrength, int] = {
    RequirementStrength.MANDATORY: 3,
    RequirementStrength.RECOMMENDED: 2,
    RequirementStrength.OPTIONAL: 1,
    RequirementStrength.PROHIBITED: 0,
}

_EVENT_AGGREGATE: dict[EventType, AggregateType] = {
    EventType.PaymentCreated: AggregateType.PAYMENT,
    EventType.PaymentAuthorized: AggregateType.PAYMENT,
    EventType.PaymentCaptured: AggregateType.PAYMENT,
    EventType.PaymentFailed: AggregateType.PAYMENT,
    EventType.OrderCreated: AggregateType.ORDER,
    EventType.OrderFulfilled: AggregateType.ORDER,
    EventType.OrderCancelled: AggregateType.ORDER,
    EventType.ShipmentCreated: AggregateType.SHIPMENT,
    EventType.ShipmentDispatched: AggregateType.SHIPMENT,
    EventType.ShipmentDelivered: AggregateType.SHIPMENT,
    EventType.RefundCreated: AggregateType.REFUND,
    EventType.RefundProcessed: AggregateType.REFUND,
    EventType.CommunicationCreated: AggregateType.COMMUNICATION,
    EventType.CommunicationReceived: AggregateType.COMMUNICATION,
    EventType.EvidenceAdded: AggregateType.EVIDENCE,
    EventType.EvidenceExpired: AggregateType.EVIDENCE,
    EventType.EvidenceInvalidated: AggregateType.EVIDENCE,
    EventType.DisputeCreated: AggregateType.DISPUTE,
    EventType.DisputeUpdated: AggregateType.DISPUTE,
    EventType.DisputeClosed: AggregateType.DISPUTE,
    EventType.ReadinessRecomputed: AggregateType.TRANSACTION,
    EventType.ReadinessGapDetected: AggregateType.TRANSACTION,
    EventType.CaseOpened: AggregateType.CASE,
    EventType.CaseEvidenceAttached: AggregateType.CASE,
    EventType.CaseInvestigated: AggregateType.CASE,
    EventType.CasePrepared: AggregateType.CASE,
    EventType.CaseEscalated: AggregateType.CASE,
    EventType.CaseSubmitted: AggregateType.CASE,
    EventType.CaseClosed: AggregateType.CASE,
    EventType.AuditRecorded: AggregateType.MERCHANT,
}
