"""The ten read-only tools the model may call (platform contract 8.6).

This registry is the *whole* surface. There is no dynamic registration, no
plugin hook and no way to add a tool at runtime: the set of things a model can
ask the platform is fixed at import time and reviewable in one screen.

Two invariants are enforced in code, not by convention:

* ``ToolSpec.method`` may only ever be ``GET``. The constructor refuses anything
  else, so a write tool cannot be added by editing a single string.
* Every path is built under ``/api/v1/ai-tools``. A spec whose path escapes that
  prefix fails at import.

The same specs serve three consumers: ``GET /v1/tools`` (the manifest the UI and
operators read), the Gemini function declarations, and ``ToolExecutor`` dispatch.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

TOOLS_BASE_PATH = "/api/v1/ai-tools"
_ALLOWED_METHOD = "GET"
_PATH_PARAM_RE = re.compile(r"\{([A-Za-z0-9_]+)\}")
# Path and query values are platform identifiers; anything else is a red flag.
_SAFE_VALUE_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class ToolSpecError(ValueError):
    """A tool definition violates the read-only tool contract."""


@dataclass(frozen=True)
class ToolParameter:
    name: str
    description: str
    required: bool = True
    enum: tuple[str, ...] = ()

    def to_json_schema(self) -> dict[str, Any]:
        schema: dict[str, Any] = {"type": "string", "description": self.description}
        if self.enum:
            schema["enum"] = list(self.enum)
        return schema


@dataclass(frozen=True)
class ToolSpec:
    """One read-only tool: a name the model uses and the GET route behind it."""

    name: str
    description: str
    path: str
    returns: str
    path_params: tuple[ToolParameter, ...] = ()
    query_params: tuple[ToolParameter, ...] = ()
    method: str = _ALLOWED_METHOD
    _placeholders: tuple[str, ...] = field(default=(), init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        if self.method != _ALLOWED_METHOD:
            raise ToolSpecError(
                f"tool {self.name!r} declares method {self.method!r}; the AI tool layer is "
                "read-only by construction and only GET is permitted (platform contract 8.6)"
            )
        if not self.path.startswith(TOOLS_BASE_PATH + "/"):
            raise ToolSpecError(
                f"tool {self.name!r} path {self.path!r} escapes {TOOLS_BASE_PATH}"
            )
        placeholders = tuple(_PATH_PARAM_RE.findall(self.path))
        declared = tuple(param.name for param in self.path_params)
        if set(placeholders) != set(declared):
            raise ToolSpecError(
                f"tool {self.name!r} path placeholders {placeholders} do not match "
                f"declared path params {declared}"
            )
        object.__setattr__(self, "_placeholders", placeholders)

    # --- argument handling --------------------------------------------------

    @property
    def parameters(self) -> tuple[ToolParameter, ...]:
        return self.path_params + self.query_params

    def required_parameter_names(self) -> tuple[str, ...]:
        return tuple(param.name for param in self.parameters if param.required)

    def validate_arguments(self, arguments: dict[str, Any]) -> dict[str, str]:
        """Check and normalise arguments; raises ``ToolArgumentError`` on anything wrong."""
        known = {param.name: param for param in self.parameters}
        unknown = sorted(set(arguments) - set(known))
        if unknown:
            raise ToolArgumentError(f"tool {self.name!r} received unknown arguments: {unknown}")

        cleaned: dict[str, str] = {}
        for name, param in known.items():
            raw = arguments.get(name)
            if raw is None or (isinstance(raw, str) and not raw.strip()):
                if param.required:
                    raise ToolArgumentError(f"tool {self.name!r} requires argument {name!r}")
                continue
            value = str(raw).strip()
            if not _SAFE_VALUE_RE.match(value):
                raise ToolArgumentError(
                    f"tool {self.name!r} argument {name!r} has an unacceptable value; "
                    "identifiers only"
                )
            if param.enum and value not in param.enum:
                raise ToolArgumentError(
                    f"tool {self.name!r} argument {name!r} must be one of {list(param.enum)}"
                )
            cleaned[name] = value
        return cleaned

    def build_request(self, arguments: dict[str, Any]) -> tuple[str, dict[str, str]]:
        """Return ``(path, query)`` for validated arguments. Never builds a body."""
        cleaned = self.validate_arguments(arguments)
        path = self.path
        for placeholder in self._placeholders:
            path = path.replace("{" + placeholder + "}", cleaned[placeholder])
        query = {
            param.name: cleaned[param.name]
            for param in self.query_params
            if param.name in cleaned
        }
        if not path.startswith(TOOLS_BASE_PATH + "/"):  # pragma: no cover - defence in depth
            raise ToolSpecError(f"built path {path!r} escaped {TOOLS_BASE_PATH}")
        return path, query

    # --- projections --------------------------------------------------------

    def to_manifest_entry(self) -> dict[str, Any]:
        """Declarative form served by ``GET /v1/tools``."""
        return {
            "name": self.name,
            "description": self.description,
            "method": self.method,
            "path": self.path,
            "returns": self.returns,
            "readOnly": True,
            "parameters": [
                {
                    "name": param.name,
                    "in": "path" if param in self.path_params else "query",
                    "required": param.required,
                    "description": param.description,
                    **({"enum": list(param.enum)} if param.enum else {}),
                }
                for param in self.parameters
            ],
        }

    def to_function_declaration(self) -> dict[str, Any]:
        """Gemini function declaration derived from the same spec.

        Plain dicts, not SDK types: the manifest must be renderable without the
        Gemini SDK installed, and ``google.genai`` accepts dicts here.
        """
        properties = {param.name: param.to_json_schema() for param in self.parameters}
        declaration: dict[str, Any] = {
            "name": self.name,
            "description": f"{self.description} Read-only.",
        }
        if properties:
            declaration["parameters"] = {
                "type": "object",
                "properties": properties,
                "required": list(self.required_parameter_names()),
            }
        return declaration


class ToolArgumentError(ValueError):
    """Arguments supplied for a known tool are missing, unknown or malformed."""


class UnknownToolError(KeyError):
    """A tool name that is not in this registry. Never forwarded upstream."""

    def __init__(self, name: str) -> None:
        self.name = name
        super().__init__(name)

    def __str__(self) -> str:
        return (
            f"unknown tool {self.name!r}; the AI tool registry is a closed set of "
            f"{len(TOOLS)} read-only tools"
        )


# ---------------------------------------------------------------------------
# The ten tools of platform contract 8.6, in contract order.
# ---------------------------------------------------------------------------

_TRANSACTION_ID = ToolParameter("transactionId", "Transaction identifier, prefixed TX-.")

TOOL_SPECS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name="getTransaction",
        description="Fetch the canonical facts of one transaction: amount, currency, status, "
        "customer and merchant references.",
        path=f"{TOOLS_BASE_PATH}/transaction/{{id}}",
        path_params=(ToolParameter("id", "Transaction identifier, prefixed TX-."),),
        returns="TransactionFacts",
    ),
    ToolSpec(
        name="getOrder",
        description="Fetch one order with its line items and fulfilment state.",
        path=f"{TOOLS_BASE_PATH}/order/{{id}}",
        path_params=(ToolParameter("id", "Order identifier, prefixed ORD-."),),
        returns="OrderView",
    ),
    ToolSpec(
        name="getShipment",
        description="Fetch one shipment: carrier, tracking reference, dispatch and delivery state.",
        path=f"{TOOLS_BASE_PATH}/shipment/{{id}}",
        path_params=(ToolParameter("id", "Shipment identifier, prefixed SHP-."),),
        returns="ShipmentView",
    ),
    ToolSpec(
        name="getRefund",
        description="Fetch one refund: amount in minor units, currency, status and processed time.",
        path=f"{TOOLS_BASE_PATH}/refund/{{id}}",
        path_params=(ToolParameter("id", "Refund identifier, prefixed REF-."),),
        returns="RefundView",
    ),
    ToolSpec(
        name="getEvidence",
        description="Fetch one evidence item: type, status, version, hash and provenance.",
        path=f"{TOOLS_BASE_PATH}/evidence/{{id}}",
        path_params=(ToolParameter("id", "Evidence identifier, prefixed EV-."),),
        returns="EvidenceView",
    ),
    ToolSpec(
        name="findRelatedEvidence",
        description="List every evidence item linked to a transaction, including superseded and "
        "expired items so their absence can be reasoned about.",
        path=f"{TOOLS_BASE_PATH}/evidence/related",
        query_params=(_TRANSACTION_ID,),
        returns="EvidenceView[]",
    ),
    ToolSpec(
        name="findContradictions",
        description="List contradictions the deterministic detector found between evidence items "
        "for a transaction.",
        path=f"{TOOLS_BASE_PATH}/contradictions",
        query_params=(_TRANSACTION_ID,),
        returns="ContradictionView[]",
    ),
    ToolSpec(
        name="getApplicablePolicy",
        description="Fetch the merchant policy version that applies to a dispute reason code, "
        "including its confidence and contradiction limits.",
        path=f"{TOOLS_BASE_PATH}/policy/applicable",
        query_params=(
            ToolParameter("merchantId", "Merchant identifier, prefixed MER-."),
            ToolParameter("reasonCode", "Dispute reason code, e.g. GOODS_NOT_RECEIVED."),
        ),
        returns="PolicyView",
    ),
    ToolSpec(
        name="getEvidenceRequirements",
        description="List the evidence requirements for a dispute reason code with their "
        "strengths (MANDATORY, RECOMMENDED, OPTIONAL, PROHIBITED).",
        path=f"{TOOLS_BASE_PATH}/requirements",
        query_params=(
            ToolParameter("reasonCode", "Dispute reason code, e.g. GOODS_NOT_RECEIVED."),
        ),
        returns="RequirementView[]",
    ),
    ToolSpec(
        name="getTimeline",
        description="Fetch the unified event and evidence timeline for a transaction in "
        "occurrence order.",
        path=f"{TOOLS_BASE_PATH}/timeline/{{transactionId}}",
        path_params=(_TRANSACTION_ID,),
        returns="TimelineEntry[]",
    ),
)

TOOLS: dict[str, ToolSpec] = {spec.name: spec for spec in TOOL_SPECS}

# Contract 8.6 lists exactly ten tools. If this fires, the contract and the code
# have diverged and one of them is wrong - fix it before shipping.
assert len(TOOLS) == 10, f"expected 10 read-only tools, registry has {len(TOOLS)}"
assert all(spec.method == _ALLOWED_METHOD for spec in TOOL_SPECS), "non-GET tool in registry"


def get(name: str) -> ToolSpec:
    """Look up a tool by name, raising ``UnknownToolError`` for anything else."""
    try:
        return TOOLS[name]
    except KeyError:
        raise UnknownToolError(name) from None


def has(name: str) -> bool:
    return name in TOOLS


def all_specs() -> tuple[ToolSpec, ...]:
    return TOOL_SPECS


def manifest() -> dict[str, Any]:
    """Payload of ``GET /v1/tools``."""
    return {
        "basePath": TOOLS_BASE_PATH,
        "readOnly": True,
        "authHeader": "X-PDEI-Service-Token",
        "count": len(TOOL_SPECS),
        "note": "Every tool is GET. There is no tool that writes financial state, "
        "modifies evidence, changes a transaction, or submits a dispute.",
        "tools": [spec.to_manifest_entry() for spec in TOOL_SPECS],
    }


def gemini_function_declarations() -> list[dict[str, Any]]:
    """Function declarations for the Gemini tool-calling phase."""
    return [spec.to_function_declaration() for spec in TOOL_SPECS]
