"""PDEI ai-reasoning-service.

The only module in the platform allowed to contain AI model code (platform
contract section 17, rule 14). Everything here *proposes*; the Java side
disposes. Nothing in this package may mutate financial state.
"""

__version__ = "0.1.0"
SERVICE_NAME = "ai-reasoning-service"
