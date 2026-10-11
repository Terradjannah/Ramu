#!/usr/bin/env python3
"""Run from protected base code; candidate files are data, never imported code."""

import argparse
import json
import os
import sys

from pathlib import Path
from classify_catalog_update import classify, api_json
from validate_catalog import validate, read_json, MAX_INDEX_BYTES, MAX_GENERATION_BYTES
from validate_catalog_evidence import validate_evidence, IDENTITY


def owner_approved(repository, number, head, reviewers, token):
    if not reviewers or not repository or not number or not head or not token:
        return False
    root = f"https://api.github.com/repos/{repository}/pulls/{number}"
    pr = api_json(root, token)
    if pr["head"]["sha"] != head:
        return False
    reviews = api_json(root + "/reviews?per_page=100", token)
    if len(reviews) == 100:
        return False
    latest = {}
    for review in reviews:
        if review["state"] in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
            latest[review["user"]["login"]] = review
    return any(user in reviewers and user != pr["user"]["login"] and
               review["state"] == "APPROVED" and review.get("commit_id") == head
               for user, review in latest.items())


def variants(root):
    production = root / "catalog/production"
    index = read_json(production / "index.json", MAX_INDEX_BYTES)[0]
    result = {}
    for entry in index["generations"]:
        generation = read_json(production / entry["path"], MAX_GENERATION_BYTES)[0]
        for model in generation["models"]:
            if model["status"] == "available":
                result.update({v["variantId"]: v for v in model["variants"]})
    return result


def check_evidence_reuse(candidate, trusted):
    previous = variants(trusted) if (trusted / "catalog/production/index.json").is_file() else {}
    for key, variant in variants(candidate).items():
        name = f"catalog/evidence/{key}.json"
        record, raw = read_json(candidate / name, MAX_GENERATION_BYTES)
        if record["artifact"]["verifiedRevision"] == variant["revision"]:
            continue
        old = previous.get(key)
        if old is None or any(old[k] != variant[k] for k in IDENTITY):
            raise ValueError("new artifact requires evidence bound to its exact commit")
        if raw != (trusted / name).read_bytes() or old["evidence"] != variant["evidence"]:
            raise ValueError("identical-byte evidence reuse must retain protected evidence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--trusted", type=Path, required=True)
    args = parser.parse_args()
    try:
        candidate, trusted = args.candidate.resolve(), args.trusted.resolve()
        # Reject symlinks before following candidate data paths on Linux runners.
        if any(path.is_symlink() for path in (candidate / "catalog").rglob("*")) or (candidate / "catalog").is_symlink():
            raise ValueError("catalog symlinks forbidden")
        validate(candidate / "catalog/production", candidate / "catalog/registry/models.json", trusted / "catalog/schema/v1")
        validate_evidence(candidate / "catalog/production", candidate / "catalog/evidence")
        check_evidence_reuse(candidate, trusted)
        result = classify(args.base, "HEAD")
        print(json.dumps(result, sort_keys=True))
        if not result["autoPublish"]:
            reviewers = set(os.environ.get("CATALOG_REVIEWERS", "").split(",")) - {""}
            if not owner_approved(os.environ.get("GITHUB_REPOSITORY"), os.environ.get("PR_NUMBER"),
                                  os.environ.get("PR_HEAD_SHA"), reviewers, os.environ.get("GH_TOKEN")):
                raise ValueError("current independent catalog owner approval required; approve then rerun this check")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"PR catalog gate rejected: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
