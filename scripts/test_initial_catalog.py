"""Keep untested candidates distinct from verified download variants."""

import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path

from check_catalog_pr import check_evidence_reuse
from validate_catalog import ROOT, validate
from validate_catalog_evidence import validate_candidate_evidence


class InitialCatalogTest(unittest.TestCase):
    def test_four_models_with_candidate_download_variants(self):
        production = ROOT / "catalog/production"
        validate(production, ROOT / "catalog/registry/models.json", ROOT / "catalog/schema/v1")
        index = json.loads((production / "index.json").read_text(encoding="utf-8"))
        models = {
            model["modelId"]: model
            for entry in index["generations"]
            for model in json.loads((production / entry["path"]).read_text(encoding="utf-8"))["models"]
        }
        self.assertEqual({"gemma-3n-e2b", "gemma-3n-e4b", "gemma-4-e2b", "gemma-4-e4b"}, set(models))
        e2b = models.pop("gemma-3n-e2b")
        self.assertEqual("available", e2b["status"])
        self.assertEqual(["gemma-3n-e2b-cpu"], [v["variantId"] for v in e2b["variants"]])
        self.assertEqual(["cpu"], e2b["variants"][0]["testedBackends"])
        for model in models.values():
            self.assertEqual("candidate", model["status"])
            self.assertEqual(1, len(model["variants"]))
            variant = model["variants"][0]
            self.assertEqual([], variant["testedBackends"])
            self.assertEqual(["cpu"], variant["candidateBackends"])
            self.assertEqual("", variant["evidence"]["runtimeTest"])
            self.assertEqual({}, variant["evidence"]["backendTests"])
            self.assertGreaterEqual(variant["minDeviceRamBytes"], 8_000_000_000)

    def test_staging_candidates_match_curated_paths_and_remain_unverified(self):
        registry = json.loads((ROOT / "catalog/registry/models.json").read_text(encoding="utf-8"))
        allowed = {row["modelId"]: row["allowedArtifacts"][0] for row in registry["models"]}
        index = json.loads((ROOT / "catalog/staging/index.json").read_text(encoding="utf-8"))
        self.assertEqual("staging-only", index["publication"])
        self.assertEqual(["generations/gemma-3.json", "generations/gemma-4.json"], index["generations"])
        candidates = []
        for path in index["generations"]:
            generation = json.loads((ROOT / "catalog/staging" / path).read_text(encoding="utf-8"))
            self.assertEqual(path, f"generations/{generation['generationId']}.json")
            self.assertEqual("gemma", generation["familyId"])
            candidates.extend(generation["models"])
        self.assertEqual(set(allowed), {candidate["modelId"] for candidate in candidates})
        for candidate in candidates:
            self.assertEqual("pending-verification", candidate["status"])
            self.assertEqual(["cpu"] if candidate["modelId"] == "gemma-3n-e2b" else [],
                             candidate["testedBackends"])
            if candidate["modelId"] == "gemma-3n-e2b":
                self.assertIsNone(candidate["artifactVersion"])
            else:
                self.assertEqual(1, candidate["artifactVersion"])
            self.assertEqual(allowed[candidate["modelId"]],
                             {"repoId": candidate["repoId"], "path": candidate["filename"]})
            self.assertTrue(candidate["blockers"])
            if candidate["revision"] is not None:
                self.assertRegex(candidate["revision"], re.compile(r"^[a-f0-9]{40}$"))
            if candidate["sha256"] is not None:
                self.assertRegex(candidate["sha256"], re.compile(r"^[a-f0-9]{64}$"))
                self.assertGreater(candidate["sizeBytes"], 0)
            else:
                self.assertIsNone(candidate["sizeBytes"])

    def test_candidate_metadata_mismatch_blocks_publication(self):
        variant = json.loads((ROOT / "catalog/production/generations/gemma-4.json").read_text(encoding="utf-8"))["models"][0]["variants"][0]
        with tempfile.TemporaryDirectory() as temporary:
            staged = shutil.copytree(ROOT / "catalog/staging", Path(temporary) / "staging")
            path = staged / "generations/gemma-4.json"
            generation = json.loads(path.read_text(encoding="utf-8"))
            generation["models"][0]["sha256"] = "0" * 64
            path.write_text(json.dumps(generation), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "metadata or device floor differs"):
                validate_candidate_evidence(variant, "gemma-4-e2b", "gemma-4", staged)

    def test_candidates_do_not_require_inference_evidence_files(self):
        check_evidence_reuse(ROOT, ROOT)
        with tempfile.TemporaryDirectory() as temporary:
            check_evidence_reuse(ROOT, Path(temporary))


if __name__ == "__main__":
    unittest.main()
