"""Static plan-link metadata for host-side pytest cases."""

from __future__ import annotations

from collections.abc import Callable, Iterable
import re
from typing import Any, TypeVar


PLAN_LINK_RE = re.compile(r"^[a-z][a-z0-9_]*#[AIT][0-9]+$")
F = TypeVar("F", bound=Callable[..., Any])


def _links(relation: str, values: Iterable[str]) -> tuple[str, ...]:
    result = tuple(values)
    if len(result) != len(set(result)):
        raise ValueError(f"duplicate {relation} links")
    for value in result:
        if not PLAN_LINK_RE.fullmatch(value):
            raise ValueError(f"malformed {relation} plan link {value!r}")
        label = value.rsplit("#", 1)[1]
        if relation == "verifies" and label[0] not in "AI":
            raise ValueError(f"verifies cannot target {value!r}")
        if relation == "covers" and label[0] != "T":
            raise ValueError(f"covers cannot target {value!r}")
    return result


def spec_test(
    *, verifies: Iterable[str] = (), covers: Iterable[str] = ()
) -> Callable[[F], F]:
    """Attach links for runtime introspection; AST discovery remains authoritative."""
    checked_verifies = _links("verifies", verifies)
    checked_covers = _links("covers", covers)

    def decorate(function: F) -> F:
        setattr(function, "__plan_verifies__", checked_verifies)
        setattr(function, "__plan_covers__", checked_covers)
        return function

    return decorate
