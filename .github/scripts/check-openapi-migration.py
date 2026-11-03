#!/usr/bin/env python3
"""Gate for an explicitly reviewed OpenAPI migration.

The normal compatibility checker remains the source of findings. This helper
does not change CI and cannot approve a migration on the Owner's behalf.
"""

import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlparse


SCRIPT = Path(__file__).with_name("check-openapi-compatibility.py")
SPEC = importlib.util.spec_from_file_location("openapi_compatibility", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)
CHECKER_SHA256 = hashlib.sha256(SCRIPT.read_bytes()).hexdigest()
DEFAULT_MANIFEST = "docs/openapi/migrations/v2.0.0.json"
SNAPSHOT = "docs/openapi/openapi.json"


class MigrationError(ValueError):
    pass


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise MigrationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _document(raw, label):
    try:
        value = json.loads(raw, object_pairs_hook=_unique_object)
    except (ValueError, UnicodeError) as error:
        raise MigrationError(f"invalid {label}: {error}") from error
    if not isinstance(value, dict):
        raise MigrationError(f"{label} must be an object")
    return value


def _keys(value, expected, label):
    if not isinstance(value, dict) or set(value) != set(expected):
        raise MigrationError(f"invalid {label} fields")


def _digest(value, length, label):
    if not isinstance(value, str) or not re.fullmatch(rf"[0-9a-f]{{{length}}}", value):
        raise MigrationError(f"invalid {label}")


def validate_manifest(raw):
    manifest = _document(raw, "migration manifest")
    _keys(manifest, {"schema_version", "checker_sha256", "candidate", "approval", "baselines"},
          "manifest")
    if type(manifest["schema_version"]) is not int or manifest["schema_version"] != 1:
        raise MigrationError("unsupported migration manifest schema version")
    _digest(manifest["checker_sha256"], 64, "checker SHA256")
    candidate = manifest["candidate"]
    _keys(candidate, {"version", "sha256"}, "candidate")
    if not isinstance(candidate["version"], str) or not candidate["version"].strip():
        raise MigrationError("invalid candidate version")
    _digest(candidate["sha256"], 64, "candidate SHA256")

    approval = manifest["approval"]
    _keys(approval, {"status", "owner", "decision_url", "decided_at"}, "approval")
    if approval["status"] not in ("proposed", "reviewed"):
        raise MigrationError("invalid approval status")
    if approval["status"] == "proposed":
        if any(approval[name] is not None for name in ("owner", "decision_url", "decided_at")):
            raise MigrationError("proposed migration cannot contain approval claims")
    else:
        for name in ("owner", "decision_url", "decided_at"):
            if not isinstance(approval[name], str) or not approval[name].strip():
                raise MigrationError(f"reviewed migration requires Owner evidence: {name}")
        decision_url = urlparse(approval["decision_url"])
        if decision_url.scheme != "https" or not decision_url.netloc:
            raise MigrationError("Owner decision must have an HTTPS evidence URL")
        try:
            decided_at = datetime.datetime.fromisoformat(approval["decided_at"])
        except ValueError as error:
            raise MigrationError("invalid Owner decision timestamp") from error
        if decided_at.tzinfo is None:
            raise MigrationError("Owner decision timestamp must include a timezone")

    baselines = manifest["baselines"]
    if not isinstance(baselines, list) or not baselines:
        raise MigrationError("migration requires explicit baselines")
    refs = set()
    for baseline in baselines:
        _keys(baseline, {"ref", "commit", "sha256", "findings"}, "baseline")
        ref = baseline["ref"]
        if not isinstance(ref, str) or not ref.strip() or ref in refs:
            raise MigrationError("baseline refs must be nonempty and unique")
        refs.add(ref)
        _digest(baseline["commit"], 40, "baseline commit")
        _digest(baseline["sha256"], 64, "baseline SHA256")
        findings = baseline["findings"]
        if (not isinstance(findings, list) or not findings
                or any(not isinstance(value, str) or not value for value in findings)
                or findings != sorted(findings)):
            raise MigrationError("baseline findings must be a nonempty sorted list")
    return manifest


def evaluate(base_raw, candidate_raw, base_ref, base_commit, manifest_raw=None):
    """Return (allowed, reason, findings); every comparison uses the strict checker.

    Compatible comparisons do not consume a historical migration allowance.
    Findings retain duplicates, so an additional occurrence is a new delta.
    """
    base = _document(base_raw, "base snapshot")
    candidate = _document(candidate_raw, "candidate snapshot")
    findings = sorted(CHECKER.check_compatibility(base, candidate))
    if not findings:
        return True, "normal compatibility checker PASS", findings
    if manifest_raw is None:
        return False, "breaking changes require a reviewed migration manifest", findings
    manifest = validate_manifest(manifest_raw)
    if manifest["checker_sha256"] != CHECKER_SHA256:
        return False, "compatibility checker differs from migration evidence", findings
    if manifest["candidate"]["version"] != candidate.get("info", {}).get("version"):
        return False, "candidate version differs from migration evidence", findings
    if manifest["candidate"]["sha256"] != sha256(candidate_raw):
        return False, "candidate SHA256 differs from migration evidence", findings
    baseline = next((value for value in manifest["baselines"] if value["ref"] == base_ref), None)
    if baseline is None:
        return False, "base ref is not listed in migration evidence", findings
    if baseline["commit"] != base_commit:
        return False, "base commit differs from migration evidence", findings
    if baseline["sha256"] != sha256(base_raw):
        return False, "base SHA256 differs from migration evidence", findings
    if baseline["findings"] != findings:
        return False, "breaking-change findings differ from migration evidence", findings
    if manifest["approval"]["status"] != "reviewed":
        return False, "migration is proposed; explicit Owner review remains required", findings
    return True, "exact reviewed migration matched; backward compatibility is not claimed", findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", default=os.environ.get("OPENAPI_BASE_REF", "origin/develop"))
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    try:
        commit = subprocess.check_output(
            ["git", "rev-parse", "--verify", "--end-of-options", f"{args.base_ref}^{{commit}}"],
            text=True, stderr=subprocess.PIPE).strip()
        _digest(commit, 40, "resolved base commit")
        base_raw = subprocess.check_output(
            ["git", "show", f"{commit}:{SNAPSHOT}"], stderr=subprocess.PIPE)
        manifest_path = Path(args.manifest)
        manifest_raw = manifest_path.read_bytes() if manifest_path.exists() else None
        allowed, reason, findings = evaluate(
            base_raw, Path(SNAPSHOT).read_bytes(), args.base_ref, commit, manifest_raw)
    except (MigrationError, OSError, subprocess.CalledProcessError, TypeError,
            AttributeError, KeyError) as error:
        print(f"OpenAPI migration gate ERROR: {error}", file=sys.stderr)
        return 2
    print(f"OpenAPI migration gate {'PASS' if allowed else 'FAIL'} against {args.base_ref}: {reason}")
    print(f"Original compatibility checker findings: {len(findings)}")
    if not allowed:
        print("\n".join(f"- {finding}" for finding in findings))
    return 0 if allowed else 1


if __name__ == "__main__":
    sys.exit(main())
