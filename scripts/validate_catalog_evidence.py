#!/usr/bin/env python3
"""Validate reviewed byte/load/inference records, without downloading model files."""

import argparse
import sys
from pathlib import Path

from validate_catalog import ROOT, COMMIT, SHA, read_json, MAX_INDEX_BYTES, MAX_GENERATION_BYTES

CONTRACT = ("format", "runtime", "testedBackends", "minApi", "abis", "minRamBytes",
            "recommendedRamBytes", "minStorageBytes", "hardware")
IDENTITY = ("repoId", "path", "sizeBytes", "sha256")


def validate_evidence(production, evidence_dir):
    index, _ = read_json(production / "index.json", MAX_INDEX_BYTES)
    for entry in index["generations"]:
        generation, _ = read_json(production / entry["path"], MAX_GENERATION_BYTES)
        for model in generation["models"]:
            for variant in model["variants"]:
                if model["status"] == "candidate":
                    validate_candidate_evidence(variant, model["modelId"], generation["generationId"], evidence_dir.parent / "staging")
                else:
                    validate_variant_evidence(variant, evidence_dir)


def validate_candidate_evidence(variant, model_id, generation_id, staging_dir):
    staged, _ = read_json(staging_dir / "generations" / f"{generation_id}.json", MAX_GENERATION_BYTES)
    matches = [row for row in staged["models"] if row.get("modelId") == model_id and row.get("variantId") == variant["variantId"]]
    if len(matches) != 1:
        raise ValueError("candidate missing unique curated staging record")
    row = matches[0]
    expected = {"repoId": variant["repoId"], "revision": variant["revision"], "filename": variant["path"],
                "artifactVersion": variant["artifactVersion"], "format": variant["format"],
                "sizeBytes": variant["sizeBytes"], "sha256": variant["sha256"],
                "minimumDeviceRamBytes": variant["minDeviceRamBytes"], "minimumAndroidApi": variant["minApi"],
                "minimumRamBytes": variant["minRamBytes"], "minimumStorageBytes": variant["minStorageBytes"],
                "license": variant["evidence"]["license"], "gated": variant["evidence"]["gated"]}
    if any(row.get(key) != value for key, value in expected.items()):
        raise ValueError("candidate metadata or device floor differs from curated staging")
    if row.get("status") != "pending-verification" or row.get("metadataSource") != variant["evidence"]["artifactSource"]:
        raise ValueError("candidate provenance/status missing")
    if row.get("supportedBackends") != variant["candidateBackends"] or not row.get("requirementsSource"):
        raise ValueError("candidate backend or device requirements lack source")
    if variant["evidence"]["runtimeTest"] or variant["evidence"]["backendTests"] or variant["testedBackends"]:
        raise ValueError("candidate incorrectly claims APK inference evidence")


def validate_variant_evidence(variant, evidence_dir):
    name = f"{variant['variantId']}.json"
    record, _ = read_json(evidence_dir / name, MAX_GENERATION_BYTES)
    if record.get("schemaVersion") != 1 or type(record.get("schemaVersion")) is not int:
        raise ValueError("unknown evidence schema")
    expected = {key: variant[key] for key in IDENTITY}
    artifact = record.get("artifact", {})
    if {key: artifact.get(key) for key in IDENTITY} != expected:
        raise ValueError("evidence artifact digest/size/source mismatch")
    if not isinstance(artifact.get("verifiedRevision"), str) or not COMMIT.fullmatch(artifact["verifiedRevision"]):
        raise ValueError("evidence must bind a verified pinned commit")
    if artifact.get("bytesVerified") is not True or artifact.get("formatVerified") is not True:
        raise ValueError("artifact byte/format verification missing")
    expected_contract = {key: variant[key] for key in CONTRACT}
    if "minDeviceRamBytes" in variant:
        expected_contract["minDeviceRamBytes"] = variant["minDeviceRamBytes"]
    if record.get("contract") != expected_contract:
        raise ValueError("runtime/backend/hardware evidence mismatch")
    # Extend this gate only with reviewed APK/native-library support and device evidence.
    if variant["testedBackends"] != ["cpu"] or variant["runtime"] != {"minVersion": "0.16.1", "maxVersion": "0.16.1"}:
        raise ValueError("backend/runtime is not available in the verified APK matrix")
    evidence = variant["evidence"]
    if type(record.get("gated")) is not bool or (record.get("license"), record.get("gated")) != (evidence["license"], evidence["gated"]):
        raise ValueError("license/access evidence mismatch")
    access = record.get("accessMode")
    if access is not False and access is not True and access not in ("auto", "manual"):
        raise ValueError("access mode evidence missing")
    if (access is not False) != evidence["gated"]:
        raise ValueError("access mode evidence mismatch")
    reference = f"catalog/evidence/{name}"
    if evidence["runtimeTest"] != reference or set(evidence["backendTests"].values()) != {reference}:
        raise ValueError("catalog must reference the bound evidence record")
    tests = record.get("tests")
    if not isinstance(tests, list) or not tests:
        raise ValueError("inference evidence missing")
    covered = set()
    covered_socs, covered_drivers = set(), set()
    for test in tests:
        if not isinstance(test, dict) or test.get("load") != "pass" or test.get("inference") != "pass":
            raise ValueError("loading/inference failed or unverified")
        if any(not isinstance(test.get(k), str) or not test[k].strip() for k in ("device", "apkSha256", "log", "testedAt")):
            raise ValueError("device/APK/test log evidence missing")
        if not SHA.fullmatch(test["apkSha256"]) or test.get("backend") != "cpu" or test.get("runtime") != "0.16.1":
            raise ValueError("test APK/backend/runtime mismatch")
        if test.get("abi") not in variant["abis"] or type(test.get("api")) is not int or test["api"] < variant["minApi"]:
            raise ValueError("test ABI/API mismatch")
        for field, test_key in (("socIds", "socId"), ("driverIds", "driverId")):
            if variant["hardware"][field] and test.get(test_key) not in variant["hardware"][field]:
                raise ValueError("test hardware mismatch")
        covered.add(test["abi"])
        covered_socs.add(test.get("socId"))
        covered_drivers.add(test.get("driverId"))
    if covered != set(variant["abis"]):
        raise ValueError("ABI test matrix incomplete")
    if not set(variant["hardware"]["socIds"]) <= covered_socs or not set(variant["hardware"]["driverIds"]) <= covered_drivers:
        raise ValueError("hardware test matrix incomplete")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--production", type=Path, default=ROOT / "catalog/production")
    parser.add_argument("--evidence", type=Path, default=ROOT / "catalog/evidence")
    args = parser.parse_args()
    try:
        validate_evidence(args.production, args.evidence)
    except (ValueError, OSError, KeyError, TypeError) as exc:
        print(f"evidence rejected: {exc}", file=sys.stderr)
        return 1
    print("catalog evidence valid (candidate metadata and verified-model test records)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
