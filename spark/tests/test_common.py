"""Smoke test za common.py — verify env loading."""
import os
import sys
import pytest

# Add jobs/ to sys.path so we can import common.py directly
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "jobs"))

from common import get_env  # noqa: E402


def test_get_env_returns_value():
    os.environ["TEST_VAR"] = "hello"
    assert get_env("TEST_VAR") == "hello"


def test_get_env_default():
    # Ensure variable is unset
    os.environ.pop("NONEXISTENT_VAR", None)
    assert get_env("NONEXISTENT_VAR", default="default") == "default"


def test_get_env_required_raises():
    os.environ.pop("MISSING_REQUIRED", None)
    with pytest.raises(RuntimeError, match="Required env var"):
        get_env("MISSING_REQUIRED", required=True)
