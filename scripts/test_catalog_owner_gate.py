import os
import unittest
from unittest.mock import patch

import check_catalog_pr
import classify_catalog_update


HEAD = "a" * 40
MERGE = "b" * 40
REPOSITORY = "Terradjannah/Ramu"


class OwnerGateTest(unittest.TestCase):
    def test_pr_approval_requires_exact_head_and_owner(self):
        pr = {"head": {"sha": HEAD}, "base": {"ref": "main"}, "user": {"login": "Terradjannah"}}
        with patch.object(check_catalog_pr, "api_json", return_value=pr) as api:
            self.assertTrue(check_catalog_pr.owner_sha_approved(REPOSITORY, "1", HEAD, HEAD, "token"))
            self.assertEqual(1, api.call_count)
        for approved in ("", "old", "c" * 40):
            with patch.object(check_catalog_pr, "api_json") as api:
                self.assertFalse(check_catalog_pr.owner_sha_approved(REPOSITORY, "1", HEAD, approved, "token"))
                api.assert_not_called()
        for change in ({"head": {"sha": "c" * 40}}, {"base": {"ref": "other"}},
                       {"user": {"login": "stranger"}}):
            with patch.object(check_catalog_pr, "api_json", return_value=dict(pr, **change)):
                self.assertFalse(check_catalog_pr.owner_sha_approved(REPOSITORY, "1", HEAD, HEAD, "token"))

    def test_deployment_approval_requires_exact_merged_pr(self):
        pr = {"number": 1, "merged_at": "now", "base": {"ref": "main"},
              "head": {"sha": HEAD}, "merge_commit_sha": MERGE,
              "user": {"login": "Terradjannah"}}
        with patch.dict(os.environ, {"CATALOG_OWNER_APPROVED_SHA": HEAD}):
            with patch.object(classify_catalog_update, "api_json", return_value=[pr]):
                self.assertTrue(classify_catalog_update.approved_merge(REPOSITORY, MERGE, "token"))
            for change in ({"head": {"sha": "c" * 40}}, {"merge_commit_sha": "c" * 40},
                           {"user": {"login": "stranger"}}, {"base": {"ref": "other"}}):
                with patch.object(classify_catalog_update, "api_json", side_effect=[[dict(pr, **change)], []]):
                    self.assertFalse(classify_catalog_update.approved_merge(REPOSITORY, MERGE, "token"))


if __name__ == "__main__":
    unittest.main()
