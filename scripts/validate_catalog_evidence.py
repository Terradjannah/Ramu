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
                validate_variant_evidence(variant, evidence_dir)


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
    if record.get("contract") != {key: variant[key] for key in CONTRACT}:
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
    print("artifact/runtime/inference evidence valid (metadata check only)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
