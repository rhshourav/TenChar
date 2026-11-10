#!/usr/bin/env python3
"""
Independent reference implementation of the TenChar attachment format (app/.../crypto/BlobCrypto.kt),
written from the format description, sharing no code with the app. Use it to check the app against something else:

    python3 blob_ref.py selftest
    python3 blob_ref.py decrypt <key-hex> <kind 1|2|3> <plain-size> <encrypted-file> <output-file>
    python3 blob_ref.py id <key-hex>

Needs:  pip install cryptography
"""
import os
import struct
import sys

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

CHUNK, PAD, TAG = 65536, 16384, 16


def _hkdf(key: bytes, info: bytes, n: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=n, salt=None, info=info).derive(key)


def blob_id(key: bytes) -> str:
    """What the relay calls this attachment: 32 hex characters derived from the key."""
    return _hkdf(key, b"tenchar-blob-id-v1", 16).hex()


def _layout(size: int):
    padded = -(-size // PAD) * PAD
    return padded, -(-padded // CHUNK)


def _nonce(i: int, last: bool) -> bytes:
    return bytes(7) + bytes([1 if last else 0]) + struct.pack(">I", i)


def _aad(kind: int, size: int) -> bytes:
    return b"tenchar-blob-v1" + bytes([kind]) + struct.pack(">I", size)


def encrypt(key: bytes, kind: int, plain: bytes) -> bytes:
    padded, n = _layout(len(plain))
    aead = AESGCM(_hkdf(key, b"tenchar-blob-key-v1", 32))
    body = plain + bytes(padded - len(plain))
    return b"".join(
        aead.encrypt(_nonce(i, i == n - 1), body[i * CHUNK:(i + 1) * CHUNK], _aad(kind, len(plain))) for i in range(n)
    )


def decrypt(key: bytes, kind: int, size: int, data: bytes) -> bytes:
    """Raises if anything is wrong: a changed, missing, reordered or extra byte, a wrong key, kind or size."""
    padded, n = _layout(size)
    aead = AESGCM(_hkdf(key, b"tenchar-blob-key-v1", 32))
    out, at = b"", 0
    for i in range(n):
        length = min(CHUNK, padded - i * CHUNK)
        out += aead.decrypt(_nonce(i, i == n - 1), data[at:at + length + TAG], _aad(kind, size))
        at += length + TAG
    if at != len(data):
        raise ValueError("bytes after the last chunk")
    if out[size:] != bytes(padded - size):
        raise ValueError("padding is not zero")
    return out[:size]


def _selftest() -> None:
    key = os.urandom(32)
    for size in (1, 16384, 65537, 300_000):
        plain = os.urandom(size)
        sealed = encrypt(key, 1, plain)
        assert decrypt(key, 1, size, sealed) == plain
        for bad in (sealed[:-1], sealed + b"\0", bytes([sealed[0] ^ 1]) + sealed[1:]):
            try:
                decrypt(key, 1, size, bad)
                raise SystemExit("accepted a damaged file")
            except Exception as e:  # noqa: BLE001
                assert not isinstance(e, SystemExit)
    print("selftest passed")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "selftest":
        _selftest()
    elif cmd == "id" and len(sys.argv) == 3:
        print(blob_id(bytes.fromhex(sys.argv[2])))
    elif cmd == "decrypt" and len(sys.argv) == 7:
        key, kind, size, src, dst = bytes.fromhex(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5], sys.argv[6]
        with open(src, "rb") as f, open(dst, "wb") as g:
            g.write(decrypt(key, kind, size, f.read()))
        print("decrypted", size, "bytes")
    else:
        print(__doc__)
        sys.exit(2)
