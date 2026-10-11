#!/usr/bin/env python3
"""Observe only curated Hugging Face paths; never download or execute artifacts."""

import argparse
import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

from validate_catalog import (ROOT, COMMIT, SHA, REPO, MAX_REGISTRY_BYTES,
                              check_registry, read_json, unique_pairs)

MAX_RESPONSE = 2 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("metadata redirect refused")


def model_info(repo, revision=None):
    if not REPO.fullmatch(repo) or (revision is not None and not COMMIT.fullmatch(revision)):
        raise ValueError("invalid metadata source")
    url = f"https://huggingface.co/api/models/{repo}"
    if revision:
        url += f"/revision/{revision}"
    # This is the Hub model_info(files_metadata=True) wire parameter.
    url += "?blobs=true"
    headers = {"Accept": "application/json", "User-Agent": "Buddy-catalog-monitor/1"}
    if os.environ.get("HF_READ_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["HF_READ_TOKEN"]
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
        data = response.read(MAX_RESPONSE + 1)
    if len(data) > MAX_RESPONSE:
        raise ValueError("metadata response too large")
    return json.loads(data, object_pairs_hook=unique_pairs)


def artifact_info(info, repo, path):
    if not isinstance(info, dict) or info.get("id") != repo:
        raise ValueError("metadata repository mismatch")
    revision = info.get("sha", "")
    if not isinstance(revision, str) or not COMMIT.fullmatch(revision):
        raise ValueError("metadata missing pinned commit")
    siblings = info.get("siblings")
    if not isinstance(siblings, list) or len(siblings) > 10000:
        raise ValueError("missing or excessive file metadata")
    matches = [s for s in siblings if isinstance(s, dict) and s.get("rfilename") == path]
    if len(matches) != 1:
        raise ValueError("curated filename missing or duplicated")
    file = matches[0]
    lfs = file.get("lfs") or {}
    digest = lfs.get("sha256", "")
    size = file.get("size")
    if not isinstance(digest, str) or not SHA.fullmatch(digest):
        raise ValueError("missing SHA-256/LFS OID; git blobId is not an artifact SHA-256")
    if type(size) is not int or size <= 0 or lfs.get("size") != size:
        raise ValueError("missing or inconsistent artifact size")
    card = info.get("cardData") or {}
    license_id = card.get("license")
    gated = info.get("gated")
    if not isinstance(license_id, str) or not 1 <= len(license_id) <= 100:
        raise ValueError("license unavailable")
    if gated is not False and gated is not True and gated not in ("auto", "manual"):
        raise ValueError("access status unavailable")
    if info.get("private") is not False or info.get("disabled", False) is not False:
        raise ValueError("private or disabled repository requires manual review")
    display = {}
    for source, dest, limit in (("model_name", "name", 120), ("description", "description", 500)):
        value = card.get(source)
        if value is not None:
            if not isinstance(value, str) or not 1 <= len(value) <= limit or any(ord(c) < 32 for c in value):
                raise ValueError("invalid display metadata")
            display[dest] = value
    return {"repoId": repo, "path": path, "revision": revision, "sizeBytes": size,
            "sha256": digest, "license": license_id, "gated": gated is not False,
            "accessMode": gated, "display": display}


def observe(registry_path, fetch=model_info):
    registry, raw = read_json(registry_path, MAX_REGISTRY_BYTES)
    rows = check_registry(registry)
    observations, unlisted, cache = [], [], {}
    allowed_by_repo = {}
    for row in rows.values():
        if row["enabled"] and row["trackUpdates"]:
            for artifact in row["allowedArtifacts"]:
                allowed_by_repo.setdefault(artifact["repoId"], set()).add(artifact["path"])
    for key, row in sorted(rows.items()):
        if not row["enabled"] or not row["trackUpdates"]:
            continue
        for allowed in row["allowedArtifacts"]:
            repo = allowed["repoId"]
            if repo not in cache:
                latest = fetch(repo)
                revision = latest.get("sha", "")
                if not isinstance(revision, str) or not COMMIT.fullmatch(revision):
                    raise ValueError("metadata missing pinned commit")
                pinned = fetch(repo, revision)
                if pinned.get("sha") != revision:
                    raise ValueError("pinned metadata revision mismatch")
                cache[repo] = pinned
                for sibling in pinned.get("siblings", []):
                    path = sibling.get("rfilename") if isinstance(sibling, dict) else None
                    if not isinstance(path, str) or not path.endswith(".litertlm") or path in allowed_by_repo[repo]:
                        continue
                    if len(path) > 200 or any(ord(char) < 32 for char in path):
                        raise ValueError("invalid unlisted artifact path")
                    lfs = sibling.get("lfs") or {}
                    size = sibling.get("size")
                    digest = lfs.get("sha256")
                    unlisted.append({"repoId": repo, "revision": revision, "path": path,
                                     "sizeBytes": size if type(size) is int and size > 0 and lfs.get("size") == size else None,
                                     "sha256": digest if isinstance(digest, str) and SHA.fullmatch(digest) else None})
            observations.append(dict(artifact_info(cache[repo], repo, allowed["path"]),
                                     generationId=key[0], modelId=key[1]))
    return {"schemaVersion": 1, "registrySha256": hashlib.sha256(raw).hexdigest(),
            "observations": observations, "unlistedArtifacts": sorted(unlisted, key=lambda item: (item["repoId"], item["path"]))}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=ROOT / "catalog/registry/models.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = observe(args.registry)
        write_json(args.output, report)
        print(f"observed {len(report['observations'])} curated artifacts; production unchanged")
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(f"monitor failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
