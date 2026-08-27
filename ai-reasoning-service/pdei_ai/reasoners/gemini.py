"""``GeminiReasoner`` - the real provider, and the only file that imports an AI SDK.

Everything provider-specific lives here: the ``google-genai`` client, the
schema-constrained generation config, retries, token accounting, the tool-calling
phase and the repair pass. Nothing above ``EvidenceReasoner`` knows Gemini exists
(platform contract 17, rule 7).

Four decisions worth understanding:

**Schema-constrained output.** The response schema is *derived from the Pydantic
model*, not hand-written, so ``InvestigationResult`` cannot drift away from what
the model is told to produce. ``response_mime_type="application/json"`` plus that
schema means the common failure - prose wrapped around JSON, or a markdown fence
- largely disappears at the source.

**The model does not fill in ``investigationId`` or ``modelMetadata``.** Both are
stripped from the model-facing schema and set by this service afterwards. An id
echoed by a model is an id that can be echoed wrong, and a provider that could
describe its own provenance could describe it falsely.

**Tool calling is a separate phase.** Gemini cannot combine function calling with
a forced response schema, so when tools are enabled the reasoner runs a bounded
gathering phase first (tools on, no schema), then a final constrained call with
the tool results appended as context. Every tool call still goes through
``ToolExecutor``, so the read-only guarantee holds unchanged.

**One repair attempt, then fail.** If the model returns something that will not
parse or will not validate, it is shown its own output and the exact error once.
A provider that cannot produce valid JSON twice will not manage it on the third
try, and the platform has a deterministic fallback waiting.
"""

from __future__ import annotations

import json
import time
from typing import Any

from pdei_ai.models.investigation import (
    Citation,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
)
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import record_repair, record_tokens
from pdei_ai.prompts.system import (
    INVESTIGATION_SYSTEM_PROMPT,
    NARRATIVE_SYSTEM_PROMPT,
    SYSTEM_PROMPT_VERSION,
    TOOL_PHASE_SYSTEM_PROMPT,
)
from pdei_ai.prompts.templates import (
    render_investigation_prompt,
    render_narrative_prompt,
    render_repair_prompt,
    render_tool_phase_prompt,
)
from pdei_ai.reasoners.base import (
    BaseReasoner,
    InvalidModelOutput,
    ReasonerError,
    ReasonerHealth,
    ReasonerUnavailable,
)
from pdei_ai.tools.executor import ToolExecutor
from pdei_ai.tools.registry import gemini_function_declarations

log = get_logger(__name__)

PROVIDER_NAME = "gemini"
DEFAULT_MODEL = "gemini-3.5-flash-lite"

# Fields the service owns; the model is never asked to produce them.
_SERVICE_OWNED_FIELDS = ("investigationId", "modelMetadata")

# JSON Schema keywords Gemini's response schema does not accept.
_UNSUPPORTED_SCHEMA_KEYS = frozenset(
    {
        "$schema",
        "$id",
        "$defs",
        "definitions",
        "additionalProperties",
        "default",
        "const",
        "examples",
        "allOf",
        "oneOf",
        "not",
        "discriminator",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "patternProperties",
        "propertyNames",
        "readOnly",
        "writeOnly",
        "deprecated",
    }
)


# ---------------------------------------------------------------------------
# Pydantic model -> Gemini response schema
# ---------------------------------------------------------------------------


def _inline_refs(node: Any, defs: dict[str, Any], depth: int = 0) -> Any:
    """Resolve ``$ref`` into ``$defs`` inline; Gemini does not follow references."""
    if depth > 12:  # pragma: no cover - our models are far shallower
        return {"type": "string"}
    if isinstance(node, list):
        return [_inline_refs(item, defs, depth + 1) for item in node]
    if not isinstance(node, dict):
        return node

    if "$ref" in node:
        ref = node["$ref"]
        key = ref.rsplit("/", 1)[-1]
        target = defs.get(key, {})
        merged = {**_inline_refs(target, defs, depth + 1)}
        for extra_key, extra_value in node.items():
            if extra_key != "$ref":
                merged[extra_key] = _inline_refs(extra_value, defs, depth + 1)
        return merged

    # anyOf is how Pydantic spells Optional[...]; take the first non-null branch
    # and mark it nullable, which is the shape Gemini understands.
    if "anyOf" in node:
        branches = [b for b in node["anyOf"] if b.get("type") != "null"]
        nullable = len(branches) != len(node["anyOf"])
        chosen = _inline_refs(branches[0], defs, depth + 1) if branches else {"type": "string"}
        if isinstance(chosen, dict):
            if nullable:
                chosen["nullable"] = True
            for extra_key in ("description", "title"):
                if extra_key in node and extra_key not in chosen:
                    chosen[extra_key] = node[extra_key]
        return chosen

    cleaned: dict[str, Any] = {}
    for key, value in node.items():
        if key in _UNSUPPORTED_SCHEMA_KEYS:
            continue
        if key == "title":
            continue  # noise in a response schema; the description carries meaning
        cleaned[key] = _inline_refs(value, defs, depth + 1)
    return cleaned


def build_response_schema(
    model_cls: Any,
    drop_fields: tuple[str, ...] = (),
    extra_required: tuple[str, ...] = (),
) -> dict[str, Any]:
    """Derive a Gemini-compatible response schema from a Pydantic model.

    Generated, never hand-written: the schema and the parser are then guaranteed
    to describe the same type.

    ``extra_required`` promotes fields that have a Python default - and are
    therefore optional to Pydantic - into required model output. Confidence and
    citations must be *stated*, not defaulted: an omitted citations array would
    read as "no claims to support" rather than "the model forgot".
    """
    raw = model_cls.model_json_schema(ref_template="#/$defs/{model}")
    defs = raw.get("$defs", {})
    schema = _inline_refs(raw, defs)

    properties = schema.get("properties", {})
    required = [name for name in schema.get("required", []) if name not in drop_fields]
    for name in drop_fields:
        properties.pop(name, None)
    for name in extra_required:
        if name in properties and name not in required:
            required.append(name)

    schema["properties"] = properties
    schema["required"] = required
    schema["type"] = "object"
    return schema


INVESTIGATION_RESPONSE_SCHEMA = build_response_schema(
    InvestigationResult,
    drop_fields=_SERVICE_OWNED_FIELDS,
    extra_required=(
        "confidence",
        "supportingEvidence",
        "missingEvidence",
        "reasoningSummary",
        "citations",
    ),
)

NARRATIVE_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "narrative": {
            "type": "string",
            "description": "The representment narrative. Factual, cited, no invented details.",
        },
        "citations": {
            "type": "array",
            "description": "One entry per factual sentence.",
            "items": {
                "type": "object",
                "properties": {
                    "claim": {"type": "string"},
                    "evidenceId": {
                        "type": "string",
                        "description": "Must appear in CITABLE EVIDENCE IDS.",
                    },
                },
                "required": ["claim", "evidenceId"],
            },
        },
    },
    "required": ["narrative", "citations"],
}


# ---------------------------------------------------------------------------
# Reasoner
# ---------------------------------------------------------------------------


class GeminiReasoner(BaseReasoner):
    """Schema-constrained Gemini reasoner with tools, retries and a repair pass."""

    name = PROVIDER_NAME

    def __init__(
        self,
        api_key: str,
        model: str = DEFAULT_MODEL,
        temperature: float = 0.1,
        max_output_tokens: int = 4096,
        max_attempts: int = 3,
        tool_executor: ToolExecutor | None = None,
        tools_enabled: bool = True,
        max_tool_calls: int = 8,
        client: Any | None = None,
    ) -> None:
        if not api_key and client is None:
            raise ReasonerUnavailable(
                "GEMINI_API_KEY is not set; the gemini provider cannot be constructed"
            )
        self.model = model
        self.temperature = temperature
        self.max_output_tokens = max_output_tokens
        self.max_attempts = max(1, max_attempts)
        self.tools_enabled = tools_enabled
        self.max_tool_calls = max_tool_calls
        self._tool_executor = tool_executor
        self._client = client or self._build_client(api_key)
        self._types = _genai_types()

    @staticmethod
    def _build_client(api_key: str) -> Any:
        try:
            from google import genai
        except ImportError as exc:  # pragma: no cover - depends on the environment
            raise ReasonerUnavailable(
                "google-genai is not installed; install it or run with PDEI_AI_PROVIDER=mock"
            ) from exc
        return genai.Client(api_key=api_key)

    # --- EvidenceReasoner ---------------------------------------------------

    async def investigate(self, context: InvestigationContext) -> InvestigationResult:
        started = time.perf_counter()
        prompt = render_investigation_prompt(context)
        tool_notes = await self._gather_with_tools(context)
        if tool_notes:
            prompt = f"{prompt}\n\n## TOOL RESULTS\n{tool_notes}"

        text, usage, attempt = await self._generate_json(
            system_prompt=INVESTIGATION_SYSTEM_PROMPT,
            user_prompt=prompt,
            response_schema=INVESTIGATION_RESPONSE_SCHEMA,
        )

        try:
            payload = _parse_json(text)
        except InvalidModelOutput as exc:
            payload, usage, attempt = await self._repair(
                system_prompt=INVESTIGATION_SYSTEM_PROMPT,
                raw_output=exc.raw_output,
                error=str(exc),
                response_schema=INVESTIGATION_RESPONSE_SCHEMA,
                previous_usage=usage,
                attempt=attempt,
            )

        payload["investigationId"] = context.investigationId
        payload["modelMetadata"] = self._metadata(usage, started, attempt).model_dump()

        try:
            return InvestigationResult.model_validate(payload)
        except Exception as exc:
            # A schema-valid but semantically invalid answer (an enum spelled
            # wrong, confidence as a percentage) gets exactly one repair too.
            repaired, usage, attempt = await self._repair(
                system_prompt=INVESTIGATION_SYSTEM_PROMPT,
                raw_output=json.dumps(payload)[:4000],
                error=str(exc),
                response_schema=INVESTIGATION_RESPONSE_SCHEMA,
                previous_usage=usage,
                attempt=attempt,
            )
            repaired["investigationId"] = context.investigationId
            repaired["modelMetadata"] = self._metadata(usage, started, attempt).model_dump()
            try:
                return InvestigationResult.model_validate(repaired)
            except Exception as final_exc:
                raise InvalidModelOutput(
                    f"gemini returned an unusable investigation result: {final_exc}",
                    json.dumps(repaired)[:2000],
                ) from final_exc

    async def narrate(self, request: NarrativeRequest) -> NarrativeResult:
        started = time.perf_counter()
        context = request.context
        prompt = render_narrative_prompt(
            context,
            classification=(
                request.classification.value if request.classification is not None else None
            ),
            tone=request.tone,
            max_words=request.maxWords,
        )

        text, usage, attempt = await self._generate_json(
            system_prompt=NARRATIVE_SYSTEM_PROMPT,
            user_prompt=prompt,
            response_schema=NARRATIVE_RESPONSE_SCHEMA,
        )

        try:
            payload = _parse_json(text)
        except InvalidModelOutput as exc:
            payload, usage, attempt = await self._repair(
                system_prompt=NARRATIVE_SYSTEM_PROMPT,
                raw_output=exc.raw_output,
                error=str(exc),
                response_schema=NARRATIVE_RESPONSE_SCHEMA,
                previous_usage=usage,
                attempt=attempt,
            )

        citations = _parse_citations(payload.get("citations"))
        return NarrativeResult(
            investigationId=context.investigationId,
            narrative=str(payload.get("narrative", "")).strip(),
            citations=citations,
            evidenceIds=[citation.evidenceId for citation in citations],
            modelMetadata=self._metadata(usage, started, attempt),
        )

    async def health(self) -> ReasonerHealth:
        """Cheap reachability check. Never raises - the registry needs an answer."""
        try:
            response = await self._call_model(
                system_prompt="Reply with the single word OK.",
                user_prompt="OK",
                response_schema=None,
                tools=None,
                max_output_tokens=8,
            )
            text = _response_text(response)
            return ReasonerHealth(
                provider=self.name,
                model=self.model,
                healthy=bool(text),
                detail="reachable" if text else "empty response from provider",
            )
        except Exception as exc:
            return ReasonerHealth(
                provider=self.name, model=self.model, healthy=False, detail=str(exc)[:300]
            )

    # --- generation ---------------------------------------------------------

    async def _generate_json(
        self,
        system_prompt: str,
        user_prompt: str,
        response_schema: dict[str, Any] | None,
    ) -> tuple[str, dict[str, int], int]:
        """Call the model with bounded retries and exponential backoff plus jitter."""
        retrying = _tenacity_retrying(self.max_attempts)
        attempt = 0
        last_error: Exception | None = None

        for wait_seconds in retrying:
            attempt += 1
            if wait_seconds:
                await _sleep(wait_seconds)
            try:
                response = await self._call_model(
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    response_schema=response_schema,
                    tools=None,
                    max_output_tokens=self.max_output_tokens,
                )
            except Exception as exc:
                last_error = exc
                log.warning("gemini call failed", attempt=attempt, error=str(exc)[:300])
                continue

            usage = _usage(response)
            record_tokens(self.name, usage["promptTokens"], usage["completionTokens"])
            text = _response_text(response)
            if not text:
                last_error = ReasonerError("gemini returned an empty response")
                continue
            return text, usage, attempt

        raise ReasonerError(
            f"gemini failed after {attempt} attempt(s): {last_error}"
        ) from last_error

    async def _repair(
        self,
        system_prompt: str,
        raw_output: str,
        error: str,
        response_schema: dict[str, Any] | None,
        previous_usage: dict[str, int],
        attempt: int,
    ) -> tuple[dict[str, Any], dict[str, int], int]:
        """One re-prompt showing the model its own output and the exact error."""
        log.warning("gemini output invalid; issuing one repair pass", error=error[:300])
        record_repair(self.name, "attempted")

        schema_hint = json.dumps(response_schema)[:1500] if response_schema else ""
        try:
            response = await self._call_model(
                system_prompt=system_prompt,
                user_prompt=render_repair_prompt(raw_output, error, schema_hint),
                response_schema=response_schema,
                tools=None,
                max_output_tokens=self.max_output_tokens,
            )
        except Exception as exc:
            record_repair(self.name, "call_failed")
            raise InvalidModelOutput(f"repair pass failed: {exc}", raw_output) from exc

        usage = _usage(response)
        record_tokens(self.name, usage["promptTokens"], usage["completionTokens"])
        merged = {
            "promptTokens": previous_usage.get("promptTokens", 0) + usage["promptTokens"],
            "completionTokens": previous_usage.get("completionTokens", 0)
            + usage["completionTokens"],
        }
        text = _response_text(response)
        try:
            payload = _parse_json(text)
        except InvalidModelOutput:
            record_repair(self.name, "failed")
            raise
        record_repair(self.name, "succeeded")
        return payload, merged, attempt + 1

    async def _gather_with_tools(self, context: InvestigationContext) -> str:
        """Bounded tool-calling phase; returns a text digest of what came back.

        Runs before the constrained call because Gemini will not accept function
        declarations and a forced response schema at the same time.
        """
        if not self.tools_enabled or self._tool_executor is None:
            return ""
        if not self._tool_executor.available:
            return ""

        self._tool_executor.reset()
        declarations = gemini_function_declarations()
        contents: list[Any] = [render_tool_phase_prompt(context)]
        digest: list[str] = []

        for _ in range(max(1, self.max_tool_calls)):
            try:
                response = await self._call_model(
                    system_prompt=TOOL_PHASE_SYSTEM_PROMPT,
                    user_prompt=None,
                    response_schema=None,
                    tools=[{"function_declarations": declarations}],
                    max_output_tokens=1024,
                    contents=contents,
                )
            except Exception as exc:
                log.warning(
                    "gemini tool phase failed; continuing from context alone", error=str(exc)[:300]
                )
                break

            calls = _function_calls(response)
            if not calls:
                break

            for call in calls:
                result = await self._tool_executor.execute(call["name"], call.get("args") or {})
                digest.append(
                    f"- {result.tool}({json.dumps(result.arguments, sort_keys=True)}) -> "
                    + (json.dumps(result.data)[:1200] if result.ok else f"ERROR: {result.error}")
                )
                contents.append(json.dumps({"functionResponse": result.to_model_payload()}))

            if self._tool_executor.calls_made >= self.max_tool_calls:
                break

        return "\n".join(digest)

    async def _call_model(
        self,
        system_prompt: str,
        user_prompt: str | None,
        response_schema: dict[str, Any] | None,
        tools: list[Any] | None,
        max_output_tokens: int,
        contents: list[Any] | None = None,
    ) -> Any:
        """The single place the SDK is actually invoked."""
        config: dict[str, Any] = {
            "system_instruction": system_prompt,
            "temperature": self.temperature,
            "max_output_tokens": max_output_tokens,
        }
        if response_schema is not None:
            config["response_mime_type"] = "application/json"
            config["response_schema"] = response_schema
        if tools:
            config["tools"] = tools

        payload = contents if contents is not None else [user_prompt or ""]

        types = self._types
        if types is not None:
            try:
                config_obj: Any = types.GenerateContentConfig(**config)
            except Exception:  # pragma: no cover - SDK version differences
                config_obj = config
        else:
            config_obj = config

        aio = getattr(self._client, "aio", None)
        if aio is not None:
            return await aio.models.generate_content(
                model=self.model, contents=payload, config=config_obj
            )
        # Sync client (used by tests and by older SDK builds).
        return self._client.models.generate_content(
            model=self.model, contents=payload, config=config_obj
        )

    def _metadata(self, usage: dict[str, int], started: float, attempt: int) -> ModelMetadata:
        # ``model`` stays exactly the configured model id (contract 9.2). The
        # prompt version is logged rather than appended, because ModelMetadata
        # has a fixed field set shared with Java and TypeScript.
        log.debug("gemini answer", model=self.model, promptVersion=SYSTEM_PROMPT_VERSION)
        return ModelMetadata(
            provider=self.name,
            model=self.model,
            promptTokens=usage.get("promptTokens", 0),
            completionTokens=usage.get("completionTokens", 0),
            latencyMs=int((time.perf_counter() - started) * 1000),
            attempt=max(1, attempt),
        )


# ---------------------------------------------------------------------------
# SDK-shape helpers, all defensive: the SDK evolves, the platform must not break
# ---------------------------------------------------------------------------


def _genai_types() -> Any | None:
    try:
        from google.genai import types

        return types
    except ImportError:  # pragma: no cover
        return None


def _response_text(response: Any) -> str:
    text = getattr(response, "text", None)
    if isinstance(text, str) and text.strip():
        return text.strip()
    for candidate in getattr(response, "candidates", None) or []:
        content = getattr(candidate, "content", None)
        for part in getattr(content, "parts", None) or []:
            part_text = getattr(part, "text", None)
            if isinstance(part_text, str) and part_text.strip():
                return part_text.strip()
    return ""


def _function_calls(response: Any) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []
    for candidate in getattr(response, "candidates", None) or []:
        content = getattr(candidate, "content", None)
        for part in getattr(content, "parts", None) or []:
            call = getattr(part, "function_call", None)
            if call is None:
                continue
            name = getattr(call, "name", None)
            if not name:
                continue
            args = getattr(call, "args", None) or {}
            calls.append({"name": name, "args": dict(args)})
    return calls


def _parse_citations(raw: Any) -> list[Citation]:
    """Build Citation objects, silently skipping malformed entries.

    A citation with an unparseable evidence id is dropped here rather than
    raising: one bad entry must not lose an otherwise usable narrative, and
    NarrativeService drops anything unsupported a second time regardless.
    """
    citations: list[Citation] = []
    for entry in raw or []:
        if not isinstance(entry, dict):
            continue
        try:
            citations.append(Citation.model_validate(entry))
        except Exception:
            log.warning("dropping a malformed citation from the provider", entry=str(entry)[:200])
    return citations


def _usage(response: Any) -> dict[str, int]:
    metadata = getattr(response, "usage_metadata", None)
    if metadata is None:
        return {"promptTokens": 0, "completionTokens": 0}
    return {
        "promptTokens": int(getattr(metadata, "prompt_token_count", 0) or 0),
        "completionTokens": int(getattr(metadata, "candidates_token_count", 0) or 0),
    }


def _parse_json(text: str) -> dict[str, Any]:
    """Parse model output as JSON, stripping a markdown fence if one slipped through."""
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.split("\n", 1)[-1]
        if cleaned.rstrip().endswith("```"):
            cleaned = cleaned.rstrip()[:-3]
    cleaned = cleaned.strip()
    try:
        payload = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise InvalidModelOutput(f"response was not valid JSON: {exc}", text) from exc
    if not isinstance(payload, dict):
        raise InvalidModelOutput("response JSON was not an object", text)
    return payload


def _tenacity_retrying(max_attempts: int) -> list[float]:
    """Backoff schedule with jitter: ``[0, w1, w2, ...]`` seconds before each attempt.

    tenacity supplies the wait strategy so the numbers match the rest of the
    platform; if it is unavailable the schedule degrades to a fixed backoff
    rather than disabling retries.
    """
    try:
        from tenacity import wait_exponential_jitter

        wait = wait_exponential_jitter(initial=0.5, max=8.0, jitter=0.5)
        schedule = [0.0]
        for attempt in range(1, max_attempts):
            try:
                schedule.append(float(wait(_FakeRetryState(attempt))))  # type: ignore[arg-type]
            except Exception:  # pragma: no cover - tenacity API differences
                schedule.append(min(8.0, 0.5 * (2**attempt)))
        return schedule
    except ImportError:  # pragma: no cover
        return [0.0] + [min(8.0, 0.5 * (2**attempt)) for attempt in range(1, max_attempts)]


class _FakeRetryState:
    """Minimal stand-in for a tenacity ``RetryCallState`` wait computation."""

    def __init__(self, attempt_number: int) -> None:
        self.attempt_number = attempt_number
        self.outcome = None
        self.idle_for = 0.0
        self.seconds_since_start = 0.0


async def _sleep(seconds: float) -> None:
    import asyncio

    await asyncio.sleep(max(0.0, seconds))
