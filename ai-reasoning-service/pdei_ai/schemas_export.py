"""Generate the JSON Schemas in ``/schemas/ai`` from the Pydantic models.

Platform contract 9.2 names ``schemas/ai/investigation-result.schema.json`` as
the referee whenever Java, Python and TypeScript disagree about a type. A
referee that is maintained by hand drifts, so the files are generated from the
models and committed.

Run after any model change::

    uv run pdei-ai-export-schemas
    python -m pdei_ai.schemas_export --check    # CI: fail if the files are stale

The ``--check`` mode is what makes this useful: it turns "someone forgot to
regenerate the schema" from a silent cross-language mismatch into a failed build.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from pdei_ai.models.admission import AdmissionDecision
from pdei_ai.models.investigation import InvestigationContext, InvestigationResult

SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
SCHEMA_BASE_URI = "https://pdei.laserpay.com/schemas/ai"

EXPORTS: tuple[tuple[str, Any, str], ...] = (
    (
        "investigation-result.schema.json",
        InvestigationResult,
        "Schema-constrained AI proposal (platform contract 9.2). A PROPOSAL, not a "
        "decision: every field is re-validated by core.safety.AiResultValidator "
        "against Postgres before any state changes.",
    ),
    (
        "investigation-context.schema.json",
        InvestigationContext,
        "Curated context sent to the AI reasoning service (platform contract 9.1). "
        "This is the complete view of the domain the model receives; it has no "
        "database access.",
    ),
    (
        "admission-decision.schema.json",
        AdmissionDecision,
        "Result of AI admission control (platform contract 9.4). Advisory when "
        "produced by the Python service: core.ai.AdmissionController owns the "
        "authoritative decision.",
    ),
)


def default_output_dir() -> Path:
    """``<repo>/schemas/ai``, resolved from this file's location."""
    return Path(__file__).resolve().parents[2] / "schemas" / "ai"


def build_schema(model_cls: Any, filename: str, description: str) -> dict[str, Any]:
    schema = model_cls.model_json_schema(
        ref_template="#/$defs/{model}", mode="validation"
    )
    ordered: dict[str, Any] = {
        "$schema": SCHEMA_DIALECT,
        "$id": f"{SCHEMA_BASE_URI}/{filename}",
        "title": model_cls.__name__,
        "description": description,
    }
    for key, value in schema.items():
        if key in ordered:
            continue
        ordered[key] = value
    return ordered


def render(schema: dict[str, Any]) -> str:
    """Stable text: sorted keys inside objects would reorder ``properties``, so
    only the top level is ordered explicitly and the rest keeps model order."""
    return json.dumps(schema, indent=2, ensure_ascii=False) + "\n"


def export(output_dir: Path | None = None, check: bool = False) -> int:
    target = output_dir or default_output_dir()
    target.mkdir(parents=True, exist_ok=True)

    stale: list[str] = []
    for filename, model_cls, description in EXPORTS:
        path = target / filename
        content = render(build_schema(model_cls, filename, description))
        if check:
            existing = path.read_text(encoding="utf-8") if path.exists() else ""
            if existing != content:
                stale.append(str(path))
            continue
        path.write_text(content, encoding="utf-8")
        print(f"wrote {path}")

    if check:
        if stale:
            print("stale JSON Schemas (run pdei-ai-export-schemas):", file=sys.stderr)
            for item in stale:
                print(f"  {item}", file=sys.stderr)
            return 1
        print("JSON Schemas are up to date")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Export PDEI AI JSON Schemas.")
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="output directory (default: <repo>/schemas/ai)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the committed schemas match the models; exit 1 if not",
    )
    args = parser.parse_args()
    return export(args.out, check=args.check)


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
