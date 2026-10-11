"""Keep review-only candidates out of the signed download catalog."""

import json
import re
import unittest

from validate_catalog import ROOT, validate


class InitialCatalogTest(unittest.TestCase):
    def test_four_models_with_only_tested_e2b_download_variant(self):
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
            self.assertEqual("unavailable", model["status"])
            self.assertEqual([], model["variants"])

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
            self.assertIsNone(candidate["artifactVersion"])
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


if __name__ == "__main__":
    unittest.main()
