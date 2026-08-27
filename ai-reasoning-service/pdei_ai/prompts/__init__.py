"""Versioned prompt constants and context templates.

The prompt version travels in ``ModelMetadata`` and into the audit trail, so a
reviewer can always ask which prompt produced a given narrative.
"""

from pdei_ai.prompts.system import (
    ALL_PROMPTS,
    INVESTIGATION_PROMPT_ID,
    INVESTIGATION_SYSTEM_PROMPT,
    NARRATIVE_PROMPT_ID,
    NARRATIVE_SYSTEM_PROMPT,
    SYSTEM_PROMPT_VERSION,
    TOOL_PHASE_PROMPT_ID,
    TOOL_PHASE_SYSTEM_PROMPT,
)
from pdei_ai.prompts.templates import (
    TEMPLATE_VERSION,
    render_context,
    render_investigation_prompt,
    render_narrative_prompt,
    render_repair_prompt,
    render_tool_phase_prompt,
)

__all__ = [
    "ALL_PROMPTS",
    "INVESTIGATION_PROMPT_ID",
    "INVESTIGATION_SYSTEM_PROMPT",
    "NARRATIVE_PROMPT_ID",
    "NARRATIVE_SYSTEM_PROMPT",
    "SYSTEM_PROMPT_VERSION",
    "TEMPLATE_VERSION",
    "TOOL_PHASE_PROMPT_ID",
    "TOOL_PHASE_SYSTEM_PROMPT",
    "render_context",
    "render_investigation_prompt",
    "render_narrative_prompt",
    "render_repair_prompt",
    "render_tool_phase_prompt",
]
