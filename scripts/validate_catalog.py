#!/usr/bin/env python3
"""Validate curated catalog metadata without reading model artifacts."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAX_INDEX_BYTES = 65536
MAX_GENERATION_BYTES = 262144
MAX_REGISTRY_BYTES = 65536
ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*\Z")
REPO = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*\Z")
SHA = re.compile(r"[a-f0-9]{64}\Z")
COMMIT = re.compile(r"[a-f0-9]{40}\Z")
ARTIFACT = re.compile(r"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\.litertlm\Z")
GENERATION = re.compile(r"generations/[a-z0-9]+(?:-[a-z0-9]+)*\.json\Z")
REQUIRED_GATES = ("requireArtifactVerification", "newVariantRequiresReview")
POLICY_KEYS = set(REQUIRED_GATES) | {"requireRuntimeCompatibility", "requireInferenceTest", "metadataAutoPublish", "unchangedArtifactAutoPublish", "changedArtifactAutoPublish"}


class CatalogError(ValueError):
    pass


def fail(where, reason):
    raise CatalogError(f"{where}: {reason}")


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail("JSON", f"duplicate key {key!r}")
        result[key] = value
    return result


def read_json(path, limit):
    try:
        data = path.read_bytes()
        if not data or len(data) > limit:
            fail(str(path), f"document size must be 1..{limit} bytes")
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_pairs), data
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(str(path), f"unreadable JSON: {exc}")


def schema_check(value, schema, where, root, depth=0):
    if depth > 24:
        fail(where, "schema depth exceeded")
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            fail(where, "unsupported schema reference")
        return schema_check(value, root["$defs"][ref[8:]], where, root, depth + 1)
    if "const" in schema and (type(value) is not type(schema["const"]) or value != schema["const"]):
        fail(where, f"expected {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        fail(where, f"unknown enum value {value!r}")
    kind = schema.get("type")
    if kind:
        valid = {"object": lambda x: isinstance(x, dict), "array": lambda x: isinstance(x, list),
                 "string": lambda x: isinstance(x, str), "integer": lambda x: type(x) is int,
                 "boolean": lambda x: type(x) is bool}[kind]
        if not valid(value):
            fail(where, f"expected {kind}")
    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                fail(where, f"missing {key}")
        if "minProperties" in schema and len(value) < schema["minProperties"]:
            fail(where, "too few properties")
        if "maxProperties" in schema and len(value) > schema["maxProperties"]:
            fail(where, "too many properties")
        for key, item in value.items():
            if "propertyNames" in schema:
                schema_check(key, schema["propertyNames"], f"{where}.{key}", root, depth + 1)
            prop = schema.get("properties", {}).get(key, schema.get("additionalProperties", True))
            if prop is False:
                fail(where, f"unknown field {key}")
            if isinstance(prop, dict):
                schema_check(item, prop, f"{where}.{key}", root, depth + 1)
    elif isinstance(value, list):
        for bound in ("minItems", "maxItems"):
            if bound in schema and ((bound == "minItems" and len(value) < schema[bound]) or
                                    (bound == "maxItems" and len(value) > schema[bound])):
                fail(where, f"{bound} violated")
        if schema.get("uniqueItems") and len(set(value)) != len(value):
            fail(where, "duplicate array item")
        if "items" in schema:
            for index, item in enumerate(value):
                schema_check(item, schema["items"], f"{where}[{index}]", root, depth + 1)
    elif isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            fail(where, "empty or short string")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            fail(where, "string too long")
        if "pattern" in schema and not re.fullmatch(schema["pattern"], value):
            fail(where, f"invalid format {value!r}")
    elif type(value) is int:
        if "minimum" in schema and value < schema["minimum"]:
            fail(where, "below minimum")
        if "maximum" in schema and value > schema["maximum"]:
            fail(where, "above maximum")


def check_registry(registry):
    if not isinstance(registry, dict) or set(registry) != {"schemaVersion", "models"} or type(registry["schemaVersion"]) is not int or registry["schemaVersion"] != 1:
        fail("registry", "unknown schema or fields")
    models = registry["models"]
    if not isinstance(models, list) or not 1 <= len(models) <= 64:
        fail("registry.models", "expected 1..64 models")
    seen = set()
    for row in models:
        if not isinstance(row, dict) or set(row) != {"modelId", "generationId", "enabled", "trackUpdates", "allowedArtifacts", "updatePolicy"}:
            fail("registry.model", "unknown or missing fields")
        for field in ("modelId", "generationId"):
            if not isinstance(row[field], str) or not ID.fullmatch(row[field]) or len(row[field]) > 64:
                fail("registry", f"invalid {field}")
        key = (row["generationId"], row["modelId"])
        if key in seen:
            fail("registry", f"duplicate model ID {key}")
        seen.add(key)
        if type(row["enabled"]) is not bool or type(row["trackUpdates"]) is not bool:
            fail("registry", "enabled and trackUpdates must be booleans")
        allowed = row["allowedArtifacts"]
        if not isinstance(allowed, list) or not 1 <= len(allowed) <= 16:
            fail("registry", "allowedArtifacts must contain 1..16 entries")
        pairs = set()
        for artifact in allowed:
            if not isinstance(artifact, dict) or set(artifact) != {"repoId", "path"}:
                fail("registry", "invalid allowed artifact fields")
            if not isinstance(artifact["repoId"], str) or not REPO.fullmatch(artifact["repoId"]):
                fail("registry", "invalid repoId")
            if not isinstance(artifact["path"], str) or not ARTIFACT.fullmatch(artifact["path"]):
                fail("registry", "invalid artifact path or traversal")
            pair = (artifact["repoId"], artifact["path"])
            if pair in pairs:
                fail("registry", "duplicate allowed artifact path")
            pairs.add(pair)
        policy = row["updatePolicy"]
        if not isinstance(policy, dict) or set(policy) != POLICY_KEYS or any(type(v) is not bool for v in policy.values()):
            fail("registry", "invalid updatePolicy fields")
        if any(not policy[k] for k in REQUIRED_GATES) or policy["changedArtifactAutoPublish"]:
            fail("registry", "mandatory verification/review gate disabled or changed artifact auto-publish enabled")
    return {(row["generationId"], row["modelId"]): row for row in models}


def check_variant(variant, registry_row, where, status):
    if not COMMIT.fullmatch(variant["revision"]):
        fail(where, "revision must be a pinned 40-hex commit, not a branch")
    if not SHA.fullmatch(variant["sha256"]):
        fail(where, "invalid SHA-256")
    if not ARTIFACT.fullmatch(variant["path"]):
        fail(where, "invalid artifact path or traversal")
    if (variant["repoId"], variant["path"]) not in {(a["repoId"], a["path"]) for a in registry_row["allowedArtifacts"]}:
        fail(where, "artifact is not allowed by developer registry")
    runtime = variant["runtime"]
    if tuple(map(int, runtime["minVersion"].split("."))) > tuple(map(int, runtime["maxVersion"].split("."))):
        fail(where, "runtime version range is reversed")
    if variant["recommendedRamBytes"] < variant["minRamBytes"]:
        fail(where, "recommended RAM below hard minimum")
    if variant["minStorageBytes"] < variant["sizeBytes"]:
        fail(where, "hard storage below artifact size")
    evidence = variant["evidence"]
    if not evidence["artifactSha256Verified"]:
        fail(where, "artifact metadata SHA-256 not verified")
    if status == "candidate":
        if variant["testedBackends"] or variant.get("candidateBackends") != ["cpu"]:
            fail(where, "candidate must identify CPU as untested, without tested backend claims")
        if evidence["runtimeTest"] or evidence["backendTests"]:
            fail(where, "candidate must not claim inference test evidence")
        if not isinstance(variant.get("minDeviceRamBytes"), int) or variant["minDeviceRamBytes"] < variant["recommendedRamBytes"]:
            fail(where, "candidate needs a documented device RAM floor")
    else:
        if variant.get("candidateBackends") or set(evidence["backendTests"]) != set(variant["testedBackends"]):
            fail(where, "verified backend without matching test evidence")
        if not variant["testedBackends"] or not evidence["runtimeTest"]:
            fail(where, "missing runtime/inference evidence")


def validate(production, registry_path, schema_dir, previous_index=None):
    index_schema, _ = read_json(schema_dir / "index.schema.json", MAX_INDEX_BYTES)
    generation_schema, _ = read_json(schema_dir / "generation.schema.json", MAX_INDEX_BYTES)
    registry, _ = read_json(registry_path, MAX_REGISTRY_BYTES)
    registry_map = check_registry(registry)
    index, _ = read_json(production / "index.json", MAX_INDEX_BYTES)
    schema_check(index, index_schema, "index", index_schema)
    if previous_index is not None:
        previous, _ = read_json(previous_index, MAX_INDEX_BYTES)
        schema_check(previous, index_schema, "previous index", index_schema)
        if index["catalogVersion"] <= previous["catalogVersion"]:
            fail("index", "catalogVersion must increase from previous publication")
    generation_ids, generation_paths, model_ids, variant_ids, artifact_paths = set(), set(), set(), set(), set()
    for entry in index["generations"]:
        generation_id, relpath = entry["generationId"], entry["path"]
        if generation_id in generation_ids or relpath in generation_paths:
            fail("index", "duplicate generation ID or path")
        generation_ids.add(generation_id)
        generation_paths.add(relpath)
        if not GENERATION.fullmatch(relpath) or relpath != f"generations/{generation_id}.json":
            fail("index", "invalid generation path or traversal")
        generation, raw = read_json(production / relpath, MAX_GENERATION_BYTES)
        if len(raw) != entry["sizeBytes"] or hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            fail("index", f"size/SHA-256 mismatch for {relpath}")
        schema_check(generation, generation_schema, relpath, generation_schema)
        if generation["generationId"] != generation_id:
            fail(relpath, "generation ID differs from index")
        available = False
        for model in generation["models"]:
            where = f"{relpath}.{model['modelId']}"
            if (model["familyId"], model["generationId"]) != (generation["familyId"], generation_id):
                fail(where, "family/generation mismatch")
            if model["modelId"] in model_ids:
                fail(where, "duplicate model ID")
            model_ids.add(model["modelId"])
            row = registry_map.get((generation_id, model["modelId"]))
            if row is None:
                fail(where, "model missing from developer registry")
            if model["status"] in ("available", "candidate") and (not model["variants"] or not row["enabled"]):
                fail(where, "downloadable model needs variants and enabled policy")
            if model["status"] == "available" and not (row["updatePolicy"]["requireRuntimeCompatibility"] and row["updatePolicy"]["requireInferenceTest"]):
                fail(where, "verified model requires runtime and inference gates")
            if model["status"] == "unavailable" and model["variants"]:
                fail(where, "unavailable model must not publish variants")
            available |= model["status"] in ("available", "candidate")
            for variant in model["variants"]:
                if variant["variantId"] in variant_ids:
                    fail(where, "duplicate variant ID")
                variant_ids.add(variant["variantId"])
                artifact_key = (variant["repoId"], variant["revision"], variant["path"])
                if artifact_key in artifact_paths:
                    fail(where, "duplicate artifact path")
                artifact_paths.add(artifact_key)
                check_variant(variant, row, where, model["status"])
        if generation["status"] != ("available" if available else "unavailable"):
            fail(relpath, "generation status does not match models")
    print(f"valid catalogVersion={index['catalogVersion']} generations={len(generation_ids)} models={len(model_ids)} variants={len(variant_ids)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--production", type=Path, default=ROOT / "catalog/production")
    parser.add_argument("--registry", type=Path, default=ROOT / "catalog/registry/models.json")
    parser.add_argument("--schema-dir", type=Path, default=ROOT / "catalog/schema/v1")
    parser.add_argument("--previous-index", type=Path, help="previously published index for monotonic version check")
    args = parser.parse_args()
    try:
        validate(args.production, args.registry, args.schema_dir, args.previous_index)
    except CatalogError as exc:
        print(f"invalid catalog: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
