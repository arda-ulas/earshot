#!/usr/bin/env python3
"""Negative and positive fixtures for check_manifest.py (SR-20). Run: python3 scripts/test_check_manifest.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_manifest import problems  # noqa: E402

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "manifests")
failures = []
if problems(os.path.join(D, "base.xml")):
    failures.append("base.xml should pass")
for f in sorted(os.listdir(D)):
    if f.startswith("bad-") and not problems(os.path.join(D, f)):
        failures.append(f"{f} should fail")
for f in failures:
    print("FAIL:", f)
if failures:
    sys.exit(1)
print(f"OK: base passes, {sum(f.startswith('bad-') for f in os.listdir(D))} bad fixtures fail")
