#!/usr/bin/env python3
"""Regression fixtures; reviewed decisions below are synthetic test data only."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("check-openapi-migration.py")
SPEC = importlib.util.spec_from_file_location("openapi_migration", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


def raw(document):
    return json.dumps(document, sort_keys=True).encode()


def document(properties, version="2.0.0"):
    return {"openapi": "3.1.0", "info": {"version": version}, "paths": {},
            "components": {"schemas": {"Item": {
                "type": "object", "properties": {
                    name: {"type": "string"} for name in properties}}}}}


class OpenApiMigrationFixtures(unittest.TestCase):
    def setUp(self):
        self.base = document(["id", "name", "description"])
        self.candidate = document(["id", "name"])
        self.base_ref = "v0.fixture"
        self.commit = "a" * 40
        self.manifest = {
            "schema_version": 1,
            "checker_sha256": GATE.CHECKER_SHA256,
            "candidate": {"version": "2.0.0", "sha256": GATE.sha256(raw(self.candidate))},
            "approval": {"status": "proposed", "owner": None,
                         "decision_url": None, "decided_at": None},
            "baselines": [{
                "ref": self.base_ref, "commit": self.commit,
                "sha256": GATE.sha256(raw(self.base)),
                "findings": sorted(GATE.CHECKER.check_compatibility(self.base, self.candidate)),
            }],
        }

    def review_fixture(self):
        self.manifest["approval"] = {
            "status": "reviewed", "owner": "Synthetic fixture Owner",
            "decision_url": "https://example.invalid/synthetic-owner-decision",
            "decided_at": "2026-11-02T00:00:00+00:00",
        }

    def evaluate(self, *, base=None, candidate=None, base_ref=None, commit=None, manifest=None):
        return GATE.evaluate(
            raw(self.base if base is None else base),
            raw(self.candidate if candidate is None else candidate),
            self.base_ref if base_ref is None else base_ref,
            self.commit if commit is None else commit,
            raw(self.manifest if manifest is None else manifest))

    def test_proposed_exact_migration_fails(self):
        allowed, reason, findings = self.evaluate()
        self.assertFalse(allowed)
        self.assertIn("proposed", reason)
        self.assertEqual(["component Item: property removed: description"], findings)

    def test_reviewed_exact_migration_passes(self):
        self.review_fixture()
        allowed, reason, findings = self.evaluate()
        self.assertTrue(allowed)
        self.assertIn("exact reviewed migration", reason)
        self.assertEqual(self.manifest["baselines"][0]["findings"], findings)

    def test_status_change_alone_is_not_review_evidence(self):
        self.manifest["approval"]["status"] = "reviewed"
        with self.assertRaisesRegex(GATE.MigrationError, "Owner evidence"):
            self.evaluate()

    def test_candidate_bytes_changed_even_without_a_new_break_fails(self):
        self.review_fixture()
        changed = copy.deepcopy(self.candidate)
        changed["info"]["description"] = "Additional metadata"
        allowed, reason, _ = self.evaluate(candidate=changed)
        self.assertFalse(allowed)
        self.assertIn("candidate SHA256", reason)

    def test_candidate_raw_serialization_change_fails(self):
        self.review_fixture()
        allowed, reason, _ = GATE.evaluate(
            raw(self.base), raw(self.candidate) + b"\n", self.base_ref, self.commit,
            raw(self.manifest))
        self.assertFalse(allowed)
        self.assertIn("candidate SHA256", reason)

    def test_base_bytes_changed_even_with_the_same_findings_fails(self):
        self.review_fixture()
        changed = copy.deepcopy(self.base)
        changed["info"]["description"] = "Changed baseline metadata"
        allowed, reason, _ = self.evaluate(base=changed)
        self.assertFalse(allowed)
        self.assertIn("base SHA256", reason)

    def test_base_commit_changed_with_identical_snapshot_fails(self):
        self.review_fixture()
        allowed, reason, _ = self.evaluate(commit="b" * 40)
        self.assertFalse(allowed)
        self.assertIn("base commit", reason)

    def test_unlisted_base_ref_with_identical_commit_and_bytes_fails(self):
        self.review_fixture()
        allowed, reason, _ = self.evaluate(base_ref="unlisted-alias")
        self.assertFalse(allowed)
        self.assertIn("not listed", reason)

    def test_an_additional_removal_fails(self):
        self.review_fixture()
        changed = document(["id"])
        allowed, reason, findings = self.evaluate(candidate=changed)
        self.assertFalse(allowed)
        self.assertIn("candidate SHA256", reason)
        self.assertEqual(2, len(findings))

    def test_updating_candidate_hash_does_not_allow_a_new_finding(self):
        self.review_fixture()
        changed = document(["id"])
        self.manifest["candidate"]["sha256"] = GATE.sha256(raw(changed))
        allowed, reason, _ = self.evaluate(candidate=changed)
        self.assertFalse(allowed)
        self.assertIn("findings differ", reason)

    def test_missing_or_extra_manifest_findings_fail(self):
        self.review_fixture()
        for findings in (["unrelated finding"],
                         self.manifest["baselines"][0]["findings"] * 2):
            with self.subTest(findings=findings):
                manifest = copy.deepcopy(self.manifest)
                manifest["baselines"][0]["findings"] = findings
                allowed, reason, _ = self.evaluate(manifest=manifest)
                self.assertFalse(allowed)
                self.assertIn("findings differ", reason)

    def test_unknown_candidate_version_fails(self):
        self.review_fixture()
        changed = document(["id", "name"], version="2.1.0")
        self.manifest["candidate"]["sha256"] = GATE.sha256(raw(changed))
        allowed, reason, _ = self.evaluate(candidate=changed)
        self.assertFalse(allowed)
        self.assertIn("version differs", reason)

    def test_changed_checker_fails(self):
        self.review_fixture()
        self.manifest["checker_sha256"] = "0" * 64
        allowed, reason, _ = self.evaluate()
        self.assertFalse(allowed)
        self.assertIn("checker differs", reason)

    def test_missing_manifest_fails_for_breaking_changes(self):
        allowed, reason, _ = GATE.evaluate(
            raw(self.base), raw(self.candidate), self.base_ref, self.commit)
        self.assertFalse(allowed)
        self.assertIn("require a reviewed migration", reason)

    def test_invalid_manifests_fail(self):
        invalid = []
        for field, value in (("schema_version", True), ("schema_version", 2),
                             ("checker_sha256", "bad"), ("baselines", [])):
            manifest = copy.deepcopy(self.manifest)
            manifest[field] = value
            invalid.append(manifest)
        manifest = copy.deepcopy(self.manifest)
        manifest["approval"]["status"] = "approved"
        invalid.append(manifest)
        manifest = copy.deepcopy(self.manifest)
        manifest["extra"] = True
        invalid.append(manifest)
        manifest = copy.deepcopy(self.manifest)
        manifest["baselines"] *= 2
        invalid.append(manifest)
        for manifest in invalid:
            with self.subTest(manifest=manifest):
                with self.assertRaises(GATE.MigrationError):
                    self.evaluate(manifest=manifest)
        for manifest_raw in (b"not json", b"[]", b'{"schema_version":1,"schema_version":2}'):
            with self.subTest(manifest_raw=manifest_raw):
                with self.assertRaises(GATE.MigrationError):
                    GATE.evaluate(raw(self.base), raw(self.candidate),
                                  self.base_ref, self.commit, manifest_raw)

    def test_proposed_manifest_cannot_claim_an_owner_decision(self):
        self.manifest["approval"]["owner"] = "An Owner"
        with self.assertRaisesRegex(GATE.MigrationError, "cannot contain approval claims"):
            self.evaluate()

    def test_invalid_review_evidence_fails(self):
        for field, value in (("owner", ""), ("decision_url", "file:///decision"),
                             ("decided_at", "invalid"), ("decided_at", "2026-11-02T00:00:00")):
            with self.subTest(field=field, value=value):
                self.review_fixture()
                self.manifest["approval"][field] = value
                with self.assertRaises(GATE.MigrationError):
                    self.evaluate()

    def test_future_compatible_change_runs_normal_checker_without_migration(self):
        future = document(["id", "name", "label"], version="2.0.1")
        with patch.object(GATE.CHECKER, "check_compatibility",
                          wraps=GATE.CHECKER.check_compatibility) as check:
            allowed, reason, findings = GATE.evaluate(
                raw(self.candidate), raw(future), "new-unlisted-base", "c" * 40)
        check.assert_called_once_with(self.candidate, future)
        self.assertTrue(allowed)
        self.assertEqual("normal compatibility checker PASS", reason)
        self.assertEqual([], findings)

    def test_future_breaking_change_cannot_reuse_old_review(self):
        self.review_fixture()
        future = document(["id"], version="2.0.1")
        allowed, _, findings = self.evaluate(base=self.candidate, candidate=future)
        self.assertFalse(allowed)
        self.assertEqual(["component Item: property removed: name"], findings)

    def test_security_change_is_not_hidden_by_reviewed_fixture(self):
        self.review_fixture()
        base = copy.deepcopy(self.base)
        candidate = copy.deepcopy(self.candidate)
        base["paths"]["/items"] = {"get": {"security": [{"bearer": []}], "responses": {}}}
        candidate["paths"]["/items"] = {"get": {"security": [{}], "responses": {}}}
        self.manifest["candidate"]["sha256"] = GATE.sha256(raw(candidate))
        self.manifest["baselines"][0]["sha256"] = GATE.sha256(raw(base))
        allowed, reason, findings = self.evaluate(base=base, candidate=candidate)
        self.assertFalse(allowed)
        self.assertIn("findings differ", reason)
        self.assertIn("GET /items: anonymous security alternative introduced", findings)

    def test_repository_manifest_matches_exact_evidence_and_approval_status(self):
        root = SCRIPT.resolve().parents[2]
        manifest_raw = (root / GATE.DEFAULT_MANIFEST).read_bytes()
        manifest = GATE.validate_manifest(manifest_raw)
        reviewed = manifest["approval"]["status"] == "reviewed"
        expected_refs = {"689be612d96079feb02a155fbe76d829a10bdeb1", "v0.17.1", "v0.18.0",
                         "v0.19.0", "v0.20.0", "v0.21.0"}
        self.assertEqual(expected_refs, {baseline["ref"] for baseline in manifest["baselines"]})
        candidate_raw = (root / GATE.SNAPSHOT).read_bytes()
        for baseline in manifest["baselines"]:
            with self.subTest(base_ref=baseline["ref"]):
                commit = subprocess.check_output(
                    ["git", "rev-parse", f'{baseline["ref"]}^{{commit}}'], cwd=root,
                    text=True).strip()
                base_raw = subprocess.check_output(
                    ["git", "show", f"{commit}:{GATE.SNAPSHOT}"], cwd=root)
                allowed, reason, findings = GATE.evaluate(
                    base_raw, candidate_raw, baseline["ref"], commit, manifest_raw)
                self.assertEqual(reviewed, allowed)
                self.assertIn("exact reviewed migration" if reviewed else "proposed", reason)
                self.assertEqual(baseline["findings"], findings)


if __name__ == "__main__":
    unittest.main()
