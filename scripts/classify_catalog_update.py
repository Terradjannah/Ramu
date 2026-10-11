#!/usr/bin/env python3
"""Classify a release against the protected branch's prior production commit."""

import argparse
import json
import os
import subprocess
import sys
import urllib.request

from validate_catalog import CatalogError, check_registry


RANK = {"none": 0, "metadata": 1, "same-artifact": 2, "artifact-changed": 3, "review": 4}
SENSITIVE = ("catalog/registry/", "catalog/schema/", "catalog/evidence/", "scripts/",
             ".github/workflows/", ".github/CODEOWNERS")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def git_json(ref, path):
    try:
        return json.loads(git("show", f"{ref}:{path}"))
    except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        raise ValueError(f"missing or invalid protected baseline {path}") from exc


def models(ref):
    index = git_json(ref, "catalog/production/index.json")
    result = {}
    for entry in index["generations"]:
        generation = git_json(ref, f"catalog/production/{entry['path']}")
        for model in generation["models"]:
            result[(entry["generationId"], model["modelId"])] = model
    return index, result


def variant_risk(old, new):
    if old == new:
        return "none"
    if old is None or new is None:
        return "review"
    hard = ("format", "runtime", "testedBackends", "minApi", "abis", "minRamBytes",
            "recommendedRamBytes", "minStorageBytes", "hardware", "repoId", "path", "evidence")
    if any(old.get(k) != new.get(k) for k in hard):
        return "review"
    if (old["sizeBytes"], old["sha256"]) != (new["sizeBytes"], new["sha256"]):
        return "artifact-changed"
    if old["artifactVersion"] != new["artifactVersion"]:
        return "review"
    if old["revision"] != new["revision"]:
        return "same-artifact"
    return "review"


def model_risk(old, new, policy):
    if old == new:
        return "none", True
    if old is None or new is None:
        return "review", False
    if any(old.get(k) != new.get(k) for k in set(old) | set(new)
           if k not in ("name", "description", "variants")):
        return "review", False
    before = {v["variantId"]: v for v in old["variants"]}
    after = {v["variantId"]: v for v in new["variants"]}
    risks = [variant_risk(before.get(k), after.get(k)) for k in before.keys() | after.keys()]
    display_changed = any(old.get(k) != new.get(k) for k in ("name", "description"))
    if display_changed:
        risks.append("metadata")
    risk = max(risks or ["review"], key=RANK.get)
    auto = (risk in ("none", "metadata", "same-artifact")
            and (not display_changed or policy["metadataAutoPublish"])
            and ("same-artifact" not in risks or policy["unchangedArtifactAutoPublish"]))
    return risk, auto


def classify(base, head):
    changed = set(git("diff", "--name-only", base, head).splitlines())
    old_index, old_models = models(base)
    new_index, new_models = models(head)
    old_registry = check_registry(git_json(base, "catalog/registry/models.json"))
    check_registry(git_json(head, "catalog/registry/models.json"))
    risk = "none"
    model_auto = True

    def raise_risk(value):
        nonlocal risk
        if RANK[value] > RANK[risk]:
            risk = value

    if any(path.startswith(SENSITIVE) for path in changed):
        raise_risk("review")
    production_changed = any(p.startswith("catalog/production/") for p in changed)
    if production_changed:
        if new_index["catalogVersion"] <= old_index["catalogVersion"]:
            raise ValueError("catalogVersion must exceed prior production version")
        if set(old_models) != set(new_models) or {e["generationId"] for e in old_index["generations"]} != {e["generationId"] for e in new_index["generations"]}:
            raise_risk("review")
        for model_key in set(old_models) & set(new_models):
            old, new = old_models[model_key], new_models[model_key]
            row_risk, row_auto = model_risk(old, new, old_registry[model_key]["updatePolicy"])
            raise_risk(row_risk)
            model_auto &= row_auto
        for entry in old_index["generations"]:
            path = entry.get("path")
            if path and any(e.get("path") == path for e in new_index["generations"]):
                old_gen = git_json(base, f"catalog/production/{path}")
                new_gen = git_json(head, f"catalog/production/{path}")
                if {k: v for k, v in old_gen.items() if k != "models"} != {k: v for k, v in new_gen.items() if k != "models"}:
                    raise_risk("review")
    if old_index != new_index and risk == "none":
        raise_risk("review")
    auto = risk in ("none", "metadata", "same-artifact") and model_auto
    return {"risk": risk, "autoPublish": auto, "changedFiles": sorted(changed),
            "previousVersion": old_index["catalogVersion"], "candidateVersion": new_index["catalogVersion"]}


def api_json(url, token):
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}",
                                                   "Accept": "application/vnd.github+json",
                                                   "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def last_published_commit(repository, token):
    if not repository or not token:
        raise ValueError("GitHub repository/token required to find last Pages release")
    root = f"https://api.github.com/repos/{repository}"
    deployments = api_json(f"{root}/deployments?environment=github-pages&per_page=100", token)
    for deployment in deployments:
        statuses = api_json(f"{root}/deployments/{deployment['id']}/statuses?per_page=1", token)
        if statuses and statuses[0]["state"] == "success":
            return deployment["sha"]
    if len(deployments) == 100:
        raise ValueError("Pages deployment history is truncated")
    return None


def approved_merge(repository, sha, token):
    if not repository or not token:
        return False
    root = f"https://api.github.com/repos/{repository}"
    for pr in api_json(f"{root}/commits/{sha}/pulls?per_page=100", token):
        if not pr.get("merged_at") or pr.get("base", {}).get("ref") != "main":
            continue
        reviews = api_json(f"{root}/pulls/{pr['number']}/reviews?per_page=100", token)
        if len(reviews) == 100:
            continue  # Never infer approval from a truncated review history.
        latest = {}
        for review in reviews:
            latest[review["user"]["login"]] = review
        if any(review["state"] == "APPROVED" and review.get("commit_id") == pr["head"]["sha"]
               and user != pr["user"]["login"] for user, review in latest.items()):
            return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--last-published", action="store_true", help="compare with last successful Pages deployment")
    args = parser.parse_args()
    try:
        repository, token = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GH_TOKEN")
        base = args.base
        if args.last_published:
            published = last_published_commit(repository, token)
            if published:
                pending = set(git("diff", "--name-only", published, args.base).splitlines())
                if any(path.startswith(("catalog/production/", "catalog/registry/", "catalog/schema/", "scripts/", ".github/workflows/")) for path in pending):
                    raise ValueError("unpublished catalog or release changes precede this push")
                base = published
        try:
            result = classify(base, args.head)
        except ValueError as exc:
            if not (args.last_published and not published and
                    str(exc) == "missing or invalid protected baseline catalog/production/index.json"):
                raise
            first_index, _ = models(args.head)
            check_registry(git_json(args.head, "catalog/registry/models.json"))
            result = {"risk": "review", "autoPublish": False,
                      "changedFiles": sorted(git("diff", "--name-only", base, args.head).splitlines()),
                      "previousVersion": None, "candidateVersion": first_index["catalogVersion"]}
        if args.last_published and not published:
            result["risk"], result["autoPublish"] = "review", False
        print(json.dumps(result, sort_keys=True))
        if args.enforce and not result["autoPublish"]:
            if not approved_merge(repository, os.environ.get("GITHUB_SHA"), token):
                raise ValueError("reviewed promotion required; no current PR approval found")
    except (CatalogError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"catalog promotion rejected: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
