import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import validate_catalog as validator


ROOT = Path(__file__).resolve().parents[1]


class CatalogValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.production = self.root / "production"
        (self.production / "generations").mkdir(parents=True)
        self.schema = ROOT / "catalog/schema/v1"
        self.index = json.loads((ROOT / "catalog/production/index.json").read_text(encoding="utf-8"))
        self.generation = json.loads((ROOT / "catalog/production/generations/gemma-3.json").read_text(encoding="utf-8"))
        self.registry = json.loads((ROOT / "catalog/registry/models.json").read_text(encoding="utf-8"))

    def write_fixture(self):
        raw = (json.dumps(self.generation, separators=(",", ":"), ensure_ascii=False) + "\n").encode()
        (self.production / "generations/gemma-3.json").write_bytes(raw)
        shutil.copyfile(ROOT / "catalog/production/generations/gemma-4.json",
                        self.production / "generations/gemma-4.json")
        self.index["generations"][0]["sizeBytes"] = len(raw)
        self.index["generations"][0]["sha256"] = hashlib.sha256(raw).hexdigest()
        (self.production / "index.json").write_text(json.dumps(self.index), encoding="utf-8")
        registry_path = self.root / "registry.json"
        registry_path.write_text(json.dumps(self.registry), encoding="utf-8")
        return registry_path

    def check_rejected(self, message):
        registry_path = self.write_fixture()
        with self.assertRaisesRegex(validator.CatalogError, message):
            validator.validate(self.production, registry_path, self.schema)

    def add_variant(self):
        self.registry["models"][0]["enabled"] = True
        model = self.generation["models"][0]
        model["status"] = "available"
        self.generation["status"] = "available"
        model["variants"] = [{
            "variantId": "gemma-3n-e2b-cpu", "artifactVersion": 1, "format": "litertlm",
            "runtime": {"minVersion": "0.16.1", "maxVersion": "0.16.1"},
            "testedBackends": ["cpu"], "minApi": 29, "abis": ["arm64-v8a"],
            "minRamBytes": 100, "recommendedRamBytes": 200, "minStorageBytes": 300,
            "hardware": {"socIds": [], "driverIds": []},
            "repoId": "google/gemma-3n-E2B-it-litert-lm", "revision": "a" * 40,
            "path": "gemma-3n-E2B-it-int4.litertlm", "sizeBytes": 300, "sha256": "b" * 64,
            "evidence": {"artifactSha256Verified": True, "artifactSource": "test fixture",
                         "license": "gemma", "gated": True, "runtimeTest": "fixture inference record",
                         "backendTests": {"cpu": "fixture CPU test"}}
        }]
        return model["variants"][0]

    def test_production(self):
        validator.validate(ROOT / "catalog/production", ROOT / "catalog/registry/models.json", self.schema)

    def test_valid_variant_fixture(self):
        self.add_variant()
        validator.validate(self.production, self.write_fixture(), self.schema)

    def test_floating_revision(self):
        self.add_variant()["revision"] = "main"
        self.check_rejected("revision.*invalid format")

    def test_bad_sha(self):
        self.add_variant()["sha256"] = "xyz"
        self.check_rejected("sha256.*invalid format")

    def test_duplicate_model_id(self):
        duplicate = copy.deepcopy(self.generation["models"][0])
        self.generation["models"].append(duplicate)
        self.check_rejected("duplicate model ID")

    def test_path_traversal(self):
        self.add_variant()["path"] = "../model.litertlm"
        self.check_rejected("path.*invalid format")

    def test_missing_generation(self):
        self.index["generations"][0]["generationId"] = "missing"
        self.index["generations"][0]["path"] = "generations/missing.json"
        self.check_rejected("unreadable JSON")

    def test_weakened_policy(self):
        self.registry["models"][0]["updatePolicy"]["requireArtifactVerification"] = False
        self.check_rejected("mandatory verification/review gate disabled")

    def test_missing_backend_evidence(self):
        self.add_variant()["evidence"]["backendTests"] = {}
        self.check_rejected("verified backend without matching test evidence")

    def test_weak_catalog_version(self):
        self.index["catalogVersion"] = 0
        self.check_rejected("below minimum")

    def test_version_must_increase_from_previous(self):
        registry_path = self.write_fixture()
        previous = self.root / "previous.json"
        previous.write_text(json.dumps(self.index), encoding="utf-8")
        with self.assertRaisesRegex(validator.CatalogError, "catalogVersion must increase"):
            validator.validate(self.production, registry_path, self.schema, previous)


if __name__ == "__main__":
    unittest.main()
