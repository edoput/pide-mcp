from __future__ import annotations

from pathlib import Path

import pytest

from tools.planning_gate.legacy import (
    LegacyDebtError,
    compare,
    generate_baseline,
    parse_baseline,
    render_baseline,
    require_accepted,
)
from tools.planning_gate.matrix import MatrixResult, MissingCoverage
from tools.planning_gate.tooling import spec_test


REVISION = "1" * 40


def result(*missing: MissingCoverage, producers: tuple[str, ...] = ()) -> MatrixResult:
    return MatrixResult((), producers, tuple(missing))


def gap(ident: str = "example#T1", relation: str = "covers") -> MissingCoverage:
    return MissingCoverage(ident, relation, "tooling-unit")


@spec_test(covers=("verification_matrix#T6",))
def test_legacy_ratchet_allows_exact_reviewed_debt_and_rejects_new_rows() -> None:
    baseline = parse_baseline(render_baseline([gap()], REVISION))

    assert compare(result(gap()), baseline).accepted

    changed = compare(result(gap(), gap("other#A1", "verifies")), baseline)
    assert [row.plan for row in changed.unexpected] == ["other"]
    with pytest.raises(LegacyDebtError, match="new unreviewed legacy debt"):
        require_accepted(changed)


@spec_test(covers=("verification_matrix#T6",))
def test_legacy_ratchet_requires_resolved_rows_to_be_removed() -> None:
    baseline = parse_baseline(render_baseline([gap()], REVISION))

    changed = compare(result(), baseline)

    assert [row.id for row in changed.resolved] == ["T1"]
    with pytest.raises(LegacyDebtError, match="remove resolved"):
        require_accepted(changed)


@spec_test(covers=("verification_matrix#T6",))
def test_legacy_baseline_is_one_time_and_requires_every_producer(tmp_path: Path) -> None:
    path = tmp_path / "legacy_unlinked.csv"
    with pytest.raises(LegacyDebtError, match="producers are missing"):
        generate_baseline(path, result(producers=("fixture/missing",)), REVISION)

    assert generate_baseline(path, result(gap()), REVISION) == 1
    with pytest.raises(LegacyDebtError, match="generated once"):
        generate_baseline(path, result(gap()), REVISION)


def test_legacy_csv_rejects_malformed_relation_duplicates_and_order() -> None:
    header = "plan,id,relation,missing_layer,baseline_revision\n"
    with pytest.raises(LegacyDebtError, match="requires relation"):
        parse_baseline(header + f"example,A1,covers,tooling-unit,{REVISION}\n")
    with pytest.raises(LegacyDebtError, match="duplicate"):
        parse_baseline(
            header
            + f"example,T1,covers,tooling-unit,{REVISION}\n"
            + f"example,T1,covers,tooling-unit,{REVISION}\n"
        )
    with pytest.raises(LegacyDebtError, match="sorted"):
        parse_baseline(
            header
            + f"zeta,T1,covers,tooling-unit,{REVISION}\n"
            + f"alpha,T1,covers,tooling-unit,{REVISION}\n"
        )
