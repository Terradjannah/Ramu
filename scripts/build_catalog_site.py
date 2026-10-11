#!/usr/bin/env python3
"""Build a validated, signed GitHub Pages artifact in a fresh staging directory."""

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path

from sign_catalog import private_key_from_environment, sign, verify
from validate_catalog import CatalogError, ROOT, read_json, validate, MAX_GENERATION_BYTES, MAX_INDEX_BYTES
from validate_catalog_evidence import validate_evidence


def canonical_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def build(production, registry, schema, output, key):
    if output.exists():
        raise ValueError("output must be a new staging directory")
    validate(production, registry, schema)
    validate_evidence(production, production.parent / "evidence")
    index, _ = read_json(production / "index.json", MAX_INDEX_BYTES)
    parent = output.parent
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="catalog-pages-", dir=parent) as scratch:
        site = Path(scratch) / "site"
        catalog = site / "catalog/v1"
        (catalog / "generations").mkdir(parents=True)
        (catalog / "signatures").mkdir()
        for entry in index["generations"]:
            source, _ = read_json(production / entry["path"], MAX_GENERATION_BYTES)
            data = canonical_bytes(source)
            entry["sizeBytes"] = len(data)
            entry["sha256"] = hashlib.sha256(data).hexdigest()
            (catalog / entry["path"]).write_bytes(data)
            signature = sign(data, key)
            (catalog / "signatures" / f"{entry['generationId']}.sig").write_bytes(signature)
            if not verify(data, signature, key.public_key()):
                raise ValueError(f"generation signature failed: {entry['generationId']}")
        index_bytes = canonical_bytes(index)
        (catalog / "index.json").write_bytes(index_bytes)
        signature = sign(index_bytes, key)
        (catalog / "index.sig").write_bytes(signature)
        if not verify(index_bytes, signature, key.public_key()):
            raise ValueError("index signature failed")
        validate(catalog, registry, schema)
        (site / "index.html").write_text(
            '<!doctype html><html lang="en"><meta charset="utf-8"><title>Ramu model catalog</title>'
            '<p>Signed Ramu model catalog: <a href="catalog/v1/index.json">index.json</a></p></html>\n',
            encoding="utf-8",
        )
        shutil.move(str(site), str(output))
    return hashlib.sha256(index_bytes).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--production", type=Path, default=ROOT / "catalog/production")
    parser.add_argument("--registry", type=Path, default=ROOT / "catalog/registry/models.json")
    parser.add_argument("--schema-dir", type=Path, default=ROOT / "catalog/schema/v1")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        digest = build(args.production, args.registry, args.schema_dir, args.output, private_key_from_environment())
    except (CatalogError, OSError, ValueError, TypeError) as exc:
        print(f"catalog build error: {exc}", file=sys.stderr)
        return 1
    print(f"built catalog index sha256={digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
