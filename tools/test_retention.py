import copy
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from tools.retention import preview


NOW = datetime(2026, 11, 8, 12, tzinfo=timezone.utc)
IMAGE = "ghcr.io/pipesor-org/pipesor-pipeline-service"


def digest(number):
    return f"sha256:{number:064x}"


def version(number, days=60, tags=None):
    return {"id": number, "name": digest(number), "created_at": (NOW - timedelta(days=days)).isoformat(),
            "metadata": {"container": {"tags": tags if tags is not None else [f"sha-{'a' * 40}-{number}-1"]}}}


def protection():
    return {"schema_version": 1, "image": IMAGE, "captured_at": NOW.isoformat(),
            "environments": {"dev": {"verified": True, "digests": [digest(2)]}},
            "releases": {"verified": True, "digests": [digest(3)]}}


class RetentionTests(unittest.TestCase):
    def test_only_old_unprotected_technical_entries_are_proposed(self):
        inventory = [version(number) for number in range(1, 15)]
        inventory.extend([version(15, tags=["v1.0.0-rc.1"]), version(16, tags=["v1.0.0"]),
                          version(17, tags=[]), version(18, tags=["unknown"]),
                          version(19, tags=["sha-" + "a" * 40 + "-19-1", "v2.0.0"]),
                          version(20, days=2)])
        result = preview(inventory, protection(), IMAGE, now=NOW)
        self.assertEqual([1, 4, 5], [entry["id"] for entry in result["proposed_delete"]])
        reasons = {entry["id"]: entry["reasons"] for entry in result["keep"]}
        self.assertIn("protected_digest", reasons[2])
        self.assertIn("protected_digest", reasons[3])
        self.assertIn("newest_technical_candidates", reasons[6])
        self.assertIn("recent_candidate", reasons[20])
        for number in range(15, 20):
            self.assertIn("untagged_or_nontechnical_tags", reasons[number])

    def test_at_least_ten_technical_candidates_survive_and_age_boundary_is_inclusive(self):
        inventory = [version(number, days=30) for number in range(1, 12)]
        result = preview(inventory, protection(), IMAGE, now=NOW)
        self.assertEqual([1], [entry["id"] for entry in result["proposed_delete"]])
        result = preview(inventory[:9], protection(), IMAGE, now=NOW)
        self.assertEqual([], result["proposed_delete"])
        result = preview([version(number, days=2) for number in range(1, 12)],
                         protection(), IMAGE, now=NOW)
        self.assertEqual([], result["proposed_delete"])

    def test_scope_evidence_must_be_fresh_verified_and_complete(self):
        changes = [lambda item: item.pop("environments"),
                   lambda item: item["environments"].pop("dev"),
                   lambda item: item["environments"]["dev"].update(verified=False),
                   lambda item: item["releases"].update(verified=False),
                   lambda item: item.update(captured_at=(NOW - timedelta(hours=24, seconds=1)).isoformat()),
                   lambda item: item.update(captured_at=(NOW + timedelta(seconds=1)).isoformat()),
                   lambda item: item.update(image="ghcr.io/other/image"),
                   lambda item: item["releases"].update(digests=["invalid"])]
        for change in changes:
            with self.subTest(change=change):
                evidence = protection()
                change(evidence)
                with self.assertRaises(ValueError):
                    preview([], evidence, IMAGE, now=NOW)
        with self.assertRaises(ValueError):
            preview([], protection(), IMAGE, required_environments=["dev", "preprod"], now=NOW)

    def test_verified_empty_scope_is_an_explicit_assertion_and_extra_scopes_are_protected(self):
        evidence = protection()
        evidence["environments"]["dev"]["digests"] = []
        evidence["environments"]["preprod"] = {"verified": True, "digests": [digest(1)]}
        result = preview([version(number) for number in range(1, 12)], evidence, IMAGE, now=NOW)
        self.assertEqual([], result["proposed_delete"])

    def test_malformed_inventory_rejects_entire_preview(self):
        for field, value in [("id", True), ("name", "sha256:invalid"),
                             ("created_at", "2026-11-07T00:00:00"),
                             ("created_at", "2026-02-30T00:00:00Z"),
                             ("created_at", (NOW + timedelta(seconds=1)).isoformat()),
                             ("metadata", {}), ("metadata", {"container": {"tags": "v1"}})]:
            with self.subTest(field=field, value=value):
                entry = version(1)
                entry[field] = value
                with self.assertRaises(ValueError):
                    preview([entry], protection(), IMAGE, now=NOW)
        with self.assertRaises(ValueError):
            preview([version(1), copy.deepcopy(version(1))], protection(), IMAGE, now=NOW)

    def test_cli_rejects_without_emitting_input_or_partial_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            inventory, evidence = Path(directory) / "inventory.json", Path(directory) / "protection.json"
            inventory.write_text(json.dumps([version(1)]))
            evidence.write_text('{"secret":"DO_NOT_LOG"}')
            result = subprocess.run([sys.executable, "tools/retention.py", "--image", IMAGE,
                                     "--inventory", str(inventory), "--protection", str(evidence)],
                                    capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertNotIn("DO_NOT_LOG", result.stderr)
            self.assertIn("no plan produced", result.stderr)


if __name__ == "__main__":
    unittest.main()
