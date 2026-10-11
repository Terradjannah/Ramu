#!/usr/bin/env python3
"""Sign and verify the exact bytes of a catalog document."""

import argparse
import base64
import binascii
import os
import sys
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def private_key_from_environment():
    pem = os.environ.get("CATALOG_SIGNING_KEY_PEM", "")
    if not pem.strip():
        raise ValueError("CATALOG_SIGNING_KEY_PEM is empty")
    key = serialization.load_pem_private_key(pem.encode("ascii"), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("signing key must be ECDSA P-256")
    return key


def sign(data, key):
    return base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256()))) + b"\n"


def verify(data, signature, public_key):
    if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
        raise ValueError("public key must be ECDSA P-256")
    try:
        encoded = signature.strip()
        if not encoded:
            return False
        raw = base64.b64decode(encoded, validate=True)
        public_key.verify(raw, data, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, binascii.Error):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("document", type=Path)
    parser.add_argument("signature", type=Path)
    parser.add_argument("--public-key", type=Path, help="verify with PEM SPKI instead of signing")
    args = parser.parse_args()
    try:
        data = args.document.read_bytes()
        if args.public_key:
            key = serialization.load_pem_public_key(args.public_key.read_bytes())
            if not verify(data, args.signature.read_bytes(), key):
                raise ValueError("signature verification failed")
        else:
            args.signature.write_bytes(sign(data, private_key_from_environment()))
    except (OSError, ValueError, TypeError) as exc:
        print(f"catalog signature error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
