"""The read-only tool layer (platform contract 8.6).

Ten GET endpoints on ``api-gateway-service``, a closed registry, and an executor
that refuses anything else. The model can widen its view of one transaction; it
cannot write, and there is no code path by which it could.
"""

from pdei_ai.tools.client import (
    SERVICE_TOKEN_HEADER,
    TOOLS_PATH_PREFIX,
    AiToolsClient,
    ToolPathError,
    ToolTransportError,
)
from pdei_ai.tools.executor import ToolCallResult, ToolExecutor
from pdei_ai.tools.registry import (
    TOOLS,
    TOOLS_BASE_PATH,
    ToolArgumentError,
    ToolParameter,
    ToolSpec,
    ToolSpecError,
    UnknownToolError,
    all_specs,
    gemini_function_declarations,
    get,
    has,
    manifest,
)

__all__ = [
    "SERVICE_TOKEN_HEADER",
    "TOOLS",
    "TOOLS_BASE_PATH",
    "TOOLS_PATH_PREFIX",
    "AiToolsClient",
    "ToolArgumentError",
    "ToolCallResult",
    "ToolExecutor",
    "ToolParameter",
    "ToolPathError",
    "ToolSpec",
    "ToolSpecError",
    "ToolTransportError",
    "UnknownToolError",
    "all_specs",
    "gemini_function_declarations",
    "get",
    "has",
    "manifest",
]
