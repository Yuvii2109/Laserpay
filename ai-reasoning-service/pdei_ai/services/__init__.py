"""Application services: orchestration, safety filtering and cost control.

Nothing in this package mutates financial state. The services curate what goes
to a provider, verify what comes back against the context that was supplied, and
throttle how often a provider is called at all.
"""

from pdei_ai.services.admission_service import (
    DEFAULT_AMBIGUITY_CAP,
    DEFAULT_FINANCIAL_IMPACT_CAP_MINOR,
    DEFAULT_PRIORITY_THRESHOLD,
    AdmissionService,
    round_half_up,
)
from pdei_ai.services.budget import BUCKET_KEY, BUDGET_KEY_PREFIX, BudgetGate, budget_key
from pdei_ai.services.investigation_service import (
    DEGRADED_CONFIDENCE_CEILING,
    InvestigationService,
    SelfCheckReport,
)
from pdei_ai.services.narrative_service import REDACTION_MARKER, NarrativeService

__all__ = [
    "BUCKET_KEY",
    "BUDGET_KEY_PREFIX",
    "DEFAULT_AMBIGUITY_CAP",
    "DEFAULT_FINANCIAL_IMPACT_CAP_MINOR",
    "DEFAULT_PRIORITY_THRESHOLD",
    "DEGRADED_CONFIDENCE_CEILING",
    "REDACTION_MARKER",
    "AdmissionService",
    "BudgetGate",
    "InvestigationService",
    "NarrativeService",
    "SelfCheckReport",
    "budget_key",
    "round_half_up",
]
