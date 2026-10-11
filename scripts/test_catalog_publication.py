import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import build_catalog_site
import classify_catalog_update
import sign_catalog
from validate_catalog import ROOT


class CatalogPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.site = Path(self.temp.name) / "site"
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.production = ROOT / "catalog/production"
        self.registry = ROOT / "catalog/registry/models.json"
        self.schema = ROOT / "catalog/schema/v1"

    def test_signed_site_contains_only_valid_payload(self):
        build_catalog_site.build(self.production, self.registry, self.schema, self.site, self.key)
        catalog = self.site / "catalog/v1"
        index = (catalog / "index.json").read_bytes()
        generation = (catalog / "generations/gemma-3.json").read_bytes()
        self.assertTrue(sign_catalog.verify(index, (catalog / "index.sig").read_bytes(), self.key.public_key()))
        self.assertTrue(sign_catalog.verify(generation, (catalog / "signatures/gemma-3.sig").read_bytes(), self.key.public_key()))
        self.assertFalse(sign_catalog.verify(index + b" ", (catalog / "index.sig").read_bytes(), self.key.public_key()))
        self.assertFalse(sign_catalog.verify(generation[:-1] + b" ", (catalog / "signatures/gemma-3.sig").read_bytes(), self.key.public_key()))
        self.assertEqual({"index.html", "catalog"}, {p.name for p in self.site.iterdir()})
        self.assertEqual({"index.json", "index.sig", "generations", "signatures"}, {p.name for p in catalog.iterdir()})

    def test_missing_generation_fails_without_output(self):
        import shutil
        copy = Path(self.temp.name) / "production"
        shutil.copytree(self.production, copy)
        (copy / "generations/gemma-3.json").unlink()
        with self.assertRaisesRegex(ValueError, "unreadable JSON"):
            build_catalog_site.build(copy, self.registry, self.schema, self.site, self.key)
        self.assertFalse(self.site.exists())

    def test_empty_secret_fails(self):
        with patch.dict(os.environ, {"CATALOG_SIGNING_KEY_PEM": ""}):
            with self.assertRaisesRegex(ValueError, "empty"):
                sign_catalog.private_key_from_environment()

    def test_signing_key_round_trip(self):
        pem = self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                     serialization.NoEncryption()).decode("ascii")
        with patch.dict(os.environ, {"CATALOG_SIGNING_KEY_PEM": pem}):
            loaded = sign_catalog.private_key_from_environment()
        self.assertTrue(sign_catalog.verify(b"test", sign_catalog.sign(b"test", loaded), self.key.public_key()))

    def test_policy_or_workflow_change_needs_review(self):
        old_index = {"catalogVersion": 1, "generations": [{"generationId": "gemma-3"}]}
        new_index = {"catalogVersion": 2, "generations": [{"generationId": "gemma-3"}]}
        model = {"familyId": "gemma", "generationId": "gemma-3", "status": "unavailable", "variants": [],
                 "name": "Old", "description": "Old"}
        updated = dict(model, name="New")
        policy = {"updatePolicy": {"metadataAutoPublish": True, "unchangedArtifactAutoPublish": True}}
        with patch.object(classify_catalog_update, "git", return_value=".github/workflows/deploy-pages.yml\ncatalog/production/index.json\n"), \
             patch.object(classify_catalog_update, "models", side_effect=[(old_index, {("gemma-3", "e2b"): model}),
                                                                         (new_index, {("gemma-3", "e2b"): updated})]), \
             patch.object(classify_catalog_update, "git_json", return_value={"schemaVersion": 1, "models": []}), \
             patch.object(classify_catalog_update, "check_registry", return_value={("gemma-3", "e2b"): policy}):
            result = classify_catalog_update.classify("base", "head")
        self.assertEqual("review", result["risk"])
        self.assertFalse(result["autoPublish"])

    def test_artifact_and_compatibility_risks(self):
        old = {"sizeBytes": 10, "sha256": "a" * 64, "revision": "a" * 40,
               "artifactVersion": 1, "runtime": {"minVersion": "0.16.1"}}
        self.assertEqual("none", classify_catalog_update.variant_risk(old, dict(old)))
        self.assertEqual("same-artifact", classify_catalog_update.variant_risk(old, dict(old, revision="b" * 40)))
        self.assertEqual("review", classify_catalog_update.variant_risk(old, dict(old, artifactVersion=2)))
        self.assertEqual("artifact-changed", classify_catalog_update.variant_risk(old, dict(old, sha256="b" * 64)))
        self.assertEqual("review", classify_catalog_update.variant_risk(old, dict(old, runtime={"minVersion": "0.17.0"})))
        self.assertEqual("review", classify_catalog_update.variant_risk(None, old))

    def test_unapproved_release_gate_rejects(self):
        with patch.object(classify_catalog_update, "classify", return_value={"risk": "review", "autoPublish": False}), \
             patch.object(classify_catalog_update, "approved_merge", return_value=False), \
             patch("sys.argv", ["classify_catalog_update.py", "--base", "base", "--enforce"]):
            self.assertEqual(1, classify_catalog_update.main())

    def test_unpublished_catalog_change_blocks_followup_release(self):
        with patch.object(classify_catalog_update, "last_published_commit", return_value="published"), \
             patch.object(classify_catalog_update, "git", return_value="catalog/production/index.json\n"), \
             patch.object(classify_catalog_update, "classify") as classify, \
             patch("sys.argv", ["classify_catalog_update.py", "--base", "previous", "--last-published", "--enforce"]):
            self.assertEqual(1, classify_catalog_update.main())
            classify.assert_not_called()

    def test_initial_release_always_requires_review(self):
        with patch.object(classify_catalog_update, "last_published_commit", return_value=None), \
             patch.object(classify_catalog_update, "classify", return_value={"risk": "metadata", "autoPublish": True}), \
             patch.object(classify_catalog_update, "approved_merge", return_value=False), \
             patch("sys.argv", ["classify_catalog_update.py", "--base", "previous", "--last-published", "--enforce"]):
            self.assertEqual(1, classify_catalog_update.main())

    def test_first_catalog_release_without_protected_baseline_needs_review(self):
        missing = ValueError("missing or invalid protected baseline catalog/production/index.json")
        with patch.object(classify_catalog_update, "last_published_commit", return_value=None), \
             patch.object(classify_catalog_update, "classify", side_effect=missing), \
             patch.object(classify_catalog_update, "models", return_value=({"catalogVersion": 1}, {})), \
             patch.object(classify_catalog_update, "git_json", return_value={"schemaVersion": 1, "models": []}), \
             patch.object(classify_catalog_update, "check_registry", return_value={}), \
             patch.object(classify_catalog_update, "git", return_value="catalog/production/index.json\n"), \
             patch.object(classify_catalog_update, "approved_merge", return_value=False), \
             patch("sys.argv", ["classify_catalog_update.py", "--base", "base", "--last-published", "--enforce"]):
            self.assertEqual(1, classify_catalog_update.main())


if __name__ == "__main__":
    unittest.main()
