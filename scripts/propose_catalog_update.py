#!/usr/bin/env python3
"""Recompute monitor observations and prepare separate staging/promotion PRs."""

import argparse
import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from check_huggingface import observe, model_info, artifact_info, write_json
from classify_catalog_update import RANK, model_risk
from validate_catalog import ROOT, read_json, validate, MAX_INDEX_BYTES, MAX_GENERATION_BYTES, MAX_REGISTRY_BYTES, check_registry
from validate_catalog_evidence import validate_evidence, validate_variant_evidence, validate_candidate_evidence


def prepare(root, output, fetch=model_info):
    production = root / "catalog/production"
    registry = root / "catalog/registry/models.json"
    schema = root / "catalog/schema/v1"
    validate(production, registry, schema)
    validate_evidence(production, root / "catalog/evidence")
    report = observe(registry, fetch)
    rows = check_registry(read_json(registry, MAX_REGISTRY_BYTES)[0])
    index = read_json(production / "index.json", MAX_INDEX_BYTES)[0]
    generations = {e["generationId"]: read_json(production / e["path"], MAX_GENERATION_BYTES)[0]
                   for e in index["generations"]}
    original = copy.deepcopy(generations)
    staging = root / "catalog/staging"
    staged = {}
    if (staging / "index.json").is_file():
        review_index = read_json(staging / "index.json", MAX_INDEX_BYTES)[0]
        for path in review_index["generations"]:
            review_generation = read_json(staging / path, MAX_GENERATION_BYTES)[0]
            for candidate in review_generation["models"]:
                staged[(candidate["repoId"], candidate["filename"])] = candidate
    changes, blocked, risk, auto = [], [], "none", True
    for artifact in report["unlistedArtifacts"]:
        blocked.append({"modelId": None, "reason": "unlisted artifact variant requires reviewed curation",
                        "repoId": artifact["repoId"], "path": artifact["path"]})
        risk, auto = "review", False
    old_cache = {}
    for observation in report["observations"]:
        generation_id, model_id = observation["generationId"], observation["modelId"]
        generation = generations.get(generation_id, {})
        model = next((m for m in generation.get("models", []) if m["modelId"] == model_id), None)
        matches = [] if model is None else [v for v in model["variants"]
                  if (v["repoId"], v["path"]) == (observation["repoId"], observation["path"])]
        if not matches:
            blocked.append({"modelId": model_id, "reason": "new variant requires reviewed curation"})
            risk, auto = "review", False
            baseline = staged.get((observation["repoId"], observation["path"]))
            if baseline:
                previous = {field: baseline[field] for field in ("revision", "sizeBytes", "sha256")}
                current = {field: observation[field] for field in previous}
                if previous != current:
                    changes.append({"modelId": model_id, "before": previous, "after": current})
            continue
        for variant in matches:
            if model["status"] == "candidate":
                validate_candidate_evidence(variant, model_id, generation_id, staging)
                key = (variant["repoId"], variant["revision"], variant["path"])
                old_info = artifact_info(fetch(key[0], key[1]), key[0], key[2])
                if any(old_info[k] != variant[k] for k in ("revision", "sizeBytes", "sha256")):
                    raise ValueError("protected candidate does not match pinned HF metadata")
                before = {k: variant[k] for k in ("revision", "sizeBytes", "sha256")}
                after = {k: observation[k] for k in before}
                if before != after or any(old_info[k] != observation[k] for k in ("license", "gated", "accessMode")):
                    changes.append({"variantId": variant["variantId"], "before": before, "after": after})
                    blocked.append({"modelId": model_id, "reason": "candidate artifact or access change requires metadata review"})
                    risk, auto = "review", False
                continue
            verified = validate_variant_evidence(variant, root / "catalog/evidence")
            key = (variant["repoId"], variant["revision"], variant["path"])
            if key not in old_cache:
                old_cache[key] = artifact_info(fetch(key[0], key[1]), key[0], key[2])
            old_info = old_cache[key]
            if any(old_info[k] != variant[k] for k in ("revision", "sizeBytes", "sha256")):
                raise ValueError("protected artifact does not match pinned HF metadata")
            if (old_info["license"], old_info["gated"]) != (variant["evidence"]["license"], variant["evidence"]["gated"]):
                raise ValueError("protected access/license does not match pinned HF metadata")
            before = copy.deepcopy(variant)
            if (any(old_info[k] != observation[k] for k in ("license", "gated", "accessMode"))
                    or verified["accessMode"] != observation["accessMode"]):
                blocked.append({"modelId": model_id, "reason": "license/access changed"})
                risk, auto = "review", False
            variant.update({k: observation[k] for k in ("revision", "sizeBytes", "sha256")})
            if (before["sizeBytes"], before["sha256"]) != (variant["sizeBytes"], variant["sha256"]):
                variant["artifactVersion"] += 1
                blocked.append({"modelId": model_id, "reason": "changed bytes need new byte/load/inference evidence"})
            # Identical content retains artifactVersion, variantId, and reviewed evidence.
            if before != variant:
                changes.append({"variantId": variant["variantId"], "before": before, "after": copy.deepcopy(variant)})
        model.update(observation["display"])
    for generation_id, generation in generations.items():
        old_models = {m["modelId"]: m for m in original[generation_id]["models"]}
        for model in generation["models"]:
            row_risk, row_auto = model_risk(old_models[model["modelId"]], model,
                                           rows[(generation_id, model["modelId"])]["updatePolicy"])
            risk = max((risk, row_risk), key=RANK.get)
            auto &= row_auto
    auto = auto and not blocked
    report.update(risk=risk, autoPublish=auto, changes=changes, blocked=blocked,
                  candidate=generations, previousVersion=index["catalogVersion"])
    if risk == "none":
        return report
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "staging/report.json", report)
    if auto:
        candidate = output / "production"
        shutil.copytree(production, candidate)
        index["catalogVersion"] += 1
        for entry in index["generations"]:
            path = candidate / entry["path"]
            write_json(path, generations[entry["generationId"]])
            raw = path.read_bytes()
            entry.update(sizeBytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        write_json(candidate / "index.json", index)
        validate(candidate, registry, schema, production / "index.json")
        validate_evidence(candidate, root / "catalog/evidence")
    return report


def gh(endpoint, payload=None, method=None):
    args = ["gh", "api", endpoint]
    if method:
        args += ["--method", method]
    if payload is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            text=True, check=True, capture_output=True)
    return json.loads(result.stdout) if result.stdout.strip() else None


def create_pr(repository, base, branch, files, title, body, auto=False, api=gh):
    root = f"repos/{repository}"
    owner = repository.split("/")[0]
    existing = api(f"{root}/pulls?state=all&head={owner}:{branch}&base=main&per_page=100")
    if existing:
        return existing[0]  # Never overwrite a reviewed, closed, or manually edited proposal.
    baseline = api(f"{root}/git/commits/{base}")
    tree = api(f"{root}/git/trees", {"base_tree": baseline["tree"]["sha"],
               "tree": [{"path": path, "mode": "100644", "type": "blob", "content": data}
                        for path, data in sorted(files.items())]})
    commit = api(f"{root}/git/commits", {"message": title, "tree": tree["sha"], "parents": [base]})
    api(f"{root}/git/refs", {"ref": f"refs/heads/{branch}", "sha": commit["sha"]})
    pr = api(f"{root}/pulls", {"title": title, "body": body, "head": branch, "base": "main"})
    if auto:
        api("graphql", {"query": "mutation($id:ID!){enablePullRequestAutoMerge(input:{pullRequestId:$id,mergeMethod:SQUASH}){pullRequest{id}}}",
                        "variables": {"id": pr["node_id"]}})
    return pr


def publish(root, output, repository, base, api=gh):
    report = read_json(output / "staging/report.json", 8 * 1024 * 1024)[0]
    raw = (output / "staging/report.json").read_text(encoding="utf-8")
    fingerprint = hashlib.sha256((base + raw).encode()).hexdigest()[:24]
    # Refuse a stale baseline before any writes; required up-to-date checks handle later races.
    if api(f"repos/{repository}/git/ref/heads/main")["object"]["sha"] != base:
        raise ValueError("main advanced; recompute on the next monitor run")
    staging = create_pr(repository, base, f"catalog/staging-{fingerprint}",
                        {f"catalog/staging/{fingerprint}.json": raw},
                        f"catalog: review HF observations ({report['risk']})",
                        "Fresh observations recomputed on trusted main. See the JSON diff for pinned sources, "
                        "before/after artifacts, candidate metadata and blocked gates. This PR cannot publish production.", api=api)
    if report["autoPublish"] and report["risk"] in ("metadata", "same-artifact"):
        files = {"catalog/production/" + p.relative_to(output / "production").as_posix(): p.read_text(encoding="utf-8")
                 for p in (output / "production").rglob("*.json")}
        create_pr(repository, base, f"catalog/promote-{fingerprint}", files,
                  f"catalog: promote {report['risk']} update",
                  f"Candidate evidence: {staging['html_url']}\n\nProtected registry permits this change. "
                  "Auto-merge remains subject to required checks and branch rules; deployment revalidates after merge.",
                  auto=True, api=api)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    try:
        if args.publish:
            base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=args.root, check=True,
                                  capture_output=True, text=True).stdout.strip()
            publish(args.root, args.output, os.environ["GITHUB_REPOSITORY"], base)
        else:
            report = prepare(args.root, args.output)
            print(f"risk={report['risk']} autoPublish={report['autoPublish']}")
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                    stream.write(f"changed={'true' if report['risk'] != 'none' else 'false'}\n")
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as exc:
        print(f"proposal failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
