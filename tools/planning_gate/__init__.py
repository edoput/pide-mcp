"""Machine-readable planning and verification gates.

The package is import-safe: command-line parsing lives exclusively in
``tools.planning_gate.__main__``.
"""

from .document import (
    Claim,
    DocumentError,
    PlanDocument,
    PlanFormat,
    load_plan,
    load_repository,
)

__all__ = [
    "Claim",
    "DocumentError",
    "PlanDocument",
    "PlanFormat",
    "load_plan",
    "load_repository",
]
