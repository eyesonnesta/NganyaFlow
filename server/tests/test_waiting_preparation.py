import copy

import pytest

from nganyaflow.prepare_waiting import prepare
from nganyaflow.schemas import Catalog
from nganyaflow.synthetic import CATALOG, generate


def test_preparation_uses_mobile_wait_target_and_keeps_provenance():
    arrivals, rows = prepare(generate(), Catalog.model_validate(CATALOG))
    assert arrivals and rows
    assert all(r["wait_minutes"] > 0 for r in rows)
    assert {r["data_kind"] for r in rows} == {"synthetic"}
    assert not any("ridership" in r for r in rows)
    assert len({r["day"] for r in rows}) == 14


def test_preparation_rejects_mixed_provenance():
    bundle = copy.deepcopy(generate())
    bundle["sessions"][0]["data_kind"] = "field"
    with pytest.raises(ValueError, match="provenance"):
        prepare(bundle, Catalog.model_validate(CATALOG))


def test_preparation_rejects_schedule_or_ridership_tables():
    with pytest.raises(ValueError):
        prepare({"ridership": [10, 20]}, Catalog.model_validate(CATALOG))
