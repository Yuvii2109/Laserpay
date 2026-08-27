"""User-message templates, rendered from an ``InvestigationContext``.

The context is rendered as compact, labelled text rather than raw JSON. Two
reasons: labelled text costs fewer tokens than pretty-printed JSON, and an
explicit "CITABLE EVIDENCE IDS" block makes the one rule that matters -
cite only these - impossible to miss.

Every template is versioned with the system prompts in ``system.py``.
"""

from __future__ import annotations

from pdei_ai.models.common import utc_now
from pdei_ai.models.investigation import InvestigationContext
from pdei_ai.prompts.system import SYSTEM_PROMPT_VERSION

TEMPLATE_VERSION = SYSTEM_PROMPT_VERSION

_MAX_TIMELINE_ENTRIES = 40
_MAX_SUMMARY_CHARS = 240


def _truncate(value: object, limit: int = _MAX_SUMMARY_CHARS) -> str:
    text = "" if value is None else str(value)
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 3] + "..."


def render_context(context: InvestigationContext) -> str:
    """Render the curated context as the model-facing user message."""
    lines: list[str] = []

    lines.append("## DISPUTE")
    lines.append(f"investigationId: {context.investigationId}")
    if context.caseId:
        lines.append(f"caseId: {context.caseId}")
    if context.disputeId:
        lines.append(f"disputeId: {context.disputeId}")
    if context.merchantId:
        lines.append(f"merchantId: {context.merchantId}")
    if context.transactionId:
        lines.append(f"transactionId: {context.transactionId}")
    if context.reasonCode:
        lines.append(f"reasonCode: {context.reasonCode.value}")
    if context.disputeAmount:
        lines.append(
            f"disputeAmount: {context.disputeAmount.amountMinor} minor units "
            f"{context.disputeAmount.currency} "
            f"(display only: {context.disputeAmount.to_display_string()})"
        )
    if context.deadlineAt:
        hours = context.hours_until_deadline(utc_now())
        overdue = hours is not None and hours < 0
        remaining = "past deadline" if overdue else f"{hours:.0f}h remaining"
        lines.append(f"deadlineAt: {context.deadlineAt.isoformat()} ({remaining})")

    if context.transactionSummary:
        lines.append("")
        lines.append("## TRANSACTION SUMMARY")
        for key, value in context.transactionSummary.items():
            lines.append(f"- {key}: {_truncate(value)}")

    lines.append("")
    lines.append("## EVIDENCE (the complete citable set)")
    if not context.evidence:
        lines.append("- none: no evidence is attached to this transaction")
    for item in context.evidence:
        parts = [
            f"- {item.evidenceId}",
            f"type={item.type.value}",
            f"status={item.status.value}",
            f"version={item.version}",
        ]
        if item.source:
            parts.append(f"source={item.source.value}")
        if item.createdAt:
            parts.append(f"createdAt={item.createdAt.isoformat()}")
        if item.expiresAt:
            parts.append(f"expiresAt={item.expiresAt.isoformat()}")
        if item.provenanceVerified is not None:
            parts.append(f"provenanceVerified={str(item.provenanceVerified).lower()}")
        if item.sha256:
            parts.append(f"sha256={item.sha256[:12]}...")
        if not item.is_usable:
            parts.append("NOT CURRENT PROOF")
        line = " ".join(parts)
        if item.summary:
            line += f" :: {_truncate(item.summary)}"
        lines.append(line)

    lines.append("")
    lines.append("## REQUIREMENTS FOR THIS REASON CODE")
    if not context.requirements:
        lines.append("- none supplied")
    for requirement in context.requirements:
        state = "SATISFIED" if requirement.satisfied else "UNSATISFIED"
        satisfied_by = (
            f" by {', '.join(requirement.satisfyingEvidenceIds)}"
            if requirement.satisfyingEvidenceIds
            else ""
        )
        note = f" :: {_truncate(requirement.note)}" if requirement.note else ""
        lines.append(
            f"- {requirement.type.value} [{requirement.strength.value}] {state}{satisfied_by}{note}"
        )

    lines.append("")
    lines.append("## GAPS")
    if not context.gaps:
        lines.append("- none detected")
    for gap in context.gaps:
        evidence_type = gap.evidenceType.value if gap.evidenceType else "-"
        detail = f" :: {_truncate(gap.detail)}" if gap.detail else ""
        lines.append(f"- {gap.type.value} {evidence_type} severity={gap.severity.value}{detail}")

    lines.append("")
    lines.append("## CONTRADICTIONS")
    if not context.contradictions:
        lines.append("- none detected")
    for contradiction in context.contradictions:
        left = contradiction.left or "?"
        right = contradiction.right or "?"
        field = contradiction.field or "unspecified field"
        values = ""
        if contradiction.leftValue or contradiction.rightValue:
            values = f" [{contradiction.leftValue} vs {contradiction.rightValue}]"
        detail = f" :: {_truncate(contradiction.detail)}" if contradiction.detail else ""
        lines.append(
            f"- {left} vs {right} on {field} "
            f"severity={contradiction.severity.value}{values}{detail}"
        )

    if context.timeline:
        lines.append("")
        lines.append("## TIMELINE")
        entries = context.timeline[:_MAX_TIMELINE_ENTRIES]
        for entry in entries:
            at = entry.at.isoformat() if entry.at else "unknown time"
            lines.append(f"- {at} {entry.eventType or 'Event'} :: {_truncate(entry.summary)}")
        if len(context.timeline) > _MAX_TIMELINE_ENTRIES:
            omitted = len(context.timeline) - _MAX_TIMELINE_ENTRIES
            lines.append(f"- ... {omitted} earlier entries omitted")

    lines.append("")
    lines.append("## POLICY CONSTRAINTS (enforced deterministically after you answer)")
    constraints = context.policyConstraints
    lines.append(f"- autoPrepareMinConfidence: {constraints.autoPrepareMinConfidence}")
    lines.append(f"- maxContradictions: {constraints.maxContradictions}")
    prohibited = ", ".join(item.value for item in constraints.prohibitedEvidenceTypes) or "none"
    lines.append(f"- prohibitedEvidenceTypes: {prohibited}")

    history = context.historicalContext
    lines.append("")
    lines.append("## HISTORICAL CONTEXT")
    lines.append(f"- merchantWinRate: {history.merchantWinRate}")
    lines.append(f"- similarCases: {history.similarCases}")

    citable = sorted(context.evidence_ids())
    lines.append("")
    lines.append("## CITABLE EVIDENCE IDS")
    lines.append(", ".join(citable) if citable else "(empty - you may not cite any evidence id)")

    return "\n".join(lines)


def render_investigation_prompt(context: InvestigationContext) -> str:
    """Full user message for the investigation call."""
    return (
        f"{render_context(context)}\n\n"
        "## TASK\n"
        "Classify how defendable this dispute is and recommend an action.\n"
        "Cite an evidence id from CITABLE EVIDENCE IDS for every factual claim.\n"
        "Return JSON matching the schema. Do not echo this prompt."
    )


def render_narrative_prompt(
    context: InvestigationContext,
    classification: str | None = None,
    tone: str = "FORMAL",
    max_words: int = 350,
) -> str:
    """Full user message for the narrative call."""
    established = (
        f"The classification already established for this case is {classification}.\n"
        if classification
        else ""
    )
    return (
        f"{render_context(context)}\n\n"
        "## TASK\n"
        f"{established}"
        f"Draft the factual narrative section of the representment. Tone: {tone}. "
        f"At most {max_words} words.\n"
        "Every factual sentence must be paired with an evidence id from CITABLE "
        "EVIDENCE IDS in the citations array.\n"
        "Return JSON matching the schema."
    )


def render_tool_phase_prompt(context: InvestigationContext) -> str:
    """User message for the optional tool-calling phase."""
    return (
        f"{render_context(context)}\n\n"
        "## TASK\n"
        "If, and only if, something you could look up would change your verdict, call the "
        "read-only tools now. Otherwise reply with the single word ENOUGH."
    )


def render_repair_prompt(raw_output: str, error: str, schema_hint: str = "") -> str:
    """Second-chance message after unparseable or schema-invalid model output.

    The model is shown its own output and the exact validation error. One
    attempt only: a provider that cannot produce valid JSON twice in a row is
    not going to on the third try, and the platform has a deterministic
    fallback waiting.
    """
    schema_block = f"\n\n## SCHEMA\n{schema_hint}" if schema_hint else ""
    return (
        "Your previous response could not be used.\n\n"
        f"## YOUR PREVIOUS OUTPUT\n{raw_output[:4000]}\n\n"
        f"## VALIDATION ERROR\n{error[:2000]}"
        f"{schema_block}\n\n"
        "## TASK\n"
        "Return a corrected response as JSON only. No markdown fences, no commentary. "
        "Keep every conclusion you can still support; drop any claim whose evidence id "
        "was not in CITABLE EVIDENCE IDS rather than inventing a replacement."
    )
