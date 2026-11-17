#!/usr/bin/env python3
"""
Independent reference vectors for the RHMesh packet layer (docs/RHMESH.md).

Written from the written layout, NOT from the Kotlin code, using `cryptography` (OpenSSL) and `hashlib`.
Every "random" input is fixed so each byte is reproducible. The Kotlin test RhVectorTest embeds this output.

Run:  python3 gen_mesh_vectors.py
Needs: pip install cryptography
"""
import hashlib, hmac, json
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

M64 = (1 << 64) - 1
GOLD = 0x9E3779B97F4A7C15


def hkdf(secret, info, n):
    return HKDF(algorithm=hashes.SHA256(), length=n, salt=None, info=info).derive(secret)


def inbox_keys(mesh_secret, member_id_hex):
    okm = hkdf(mesh_secret, b"rhmesh-inbox-v1" + bytes.fromhex(member_id_hex), 64)
    return okm[:32], okm[32:]          # tagKey, sealKey


def tag_of(tag_key, nonce):
    return hmac.new(tag_key, b"rhmesh-tag-v1" + nonce, hashlib.sha256).digest()[:8]


def seal(envelope, tag_key, seal_key, created, nonce):
    assert len(envelope) == 135 and len(nonce) == 12
    tag = tag_of(tag_key, nonce)
    aad = bytes([1]) + created.to_bytes(4, "big") + tag
    sealed = AESGCM(seal_key).encrypt(nonce, envelope, aad)
    assert len(sealed) == 151
    packet = bytes([1, 0]) + created.to_bytes(4, "big") + nonce + tag + sealed + bytes(15)
    assert len(packet) == 192
    return packet


def packet_id(packet):
    return hashlib.sha256(b"rhmesh-id-v1" + packet[0:1] + packet[2:177]).digest()[:8]


def mix(z):
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    return z ^ (z >> 31)


def bloom_encode(ids, salt, words, hashes_k=7):
    m = words * 64
    bits = [0] * words
    for raw in ids:
        h = mix((raw ^ (((salt & 0xFFFFFFFF) * GOLD) & M64)) & M64)
        h1, h2 = h & 0xFFFFFFFF, (h >> 32) | 1
        for i in range(hashes_k):
            idx = (h1 + i * h2) % m
            bits[idx >> 6] |= 1 << (idx & 63)
    out = bytes([1, hashes_k, 0, 0]) + (salt & 0xFFFFFFFF).to_bytes(4, "big") + words.to_bytes(4, "big")
    return out + b"".join(w.to_bytes(8, "big") for w in bits)


def fingerprint_beacon(ids):
    acc = 0
    for raw in ids:
        acc ^= mix(raw)
    return min(len(ids), 0xFFFF).to_bytes(2, "big") + (acc >> 32).to_bytes(4, "big")


def main():
    mesh_secret = bytes(range(32))
    recipient_id = "22" * 16
    tag_key, seal_key = inbox_keys(mesh_secret, recipient_id)
    envelope = bytes((i * 7 + 3) & 0xFF for i in range(135))
    nonce = bytes(range(0xA0, 0xAC))
    created = 1_800_000_000
    packet = seal(envelope, tag_key, seal_key, created, nonce)
    pid = packet_id(packet)

    ids = [int.from_bytes(hashlib.sha256(bytes([n])).digest()[:8], "big") for n in range(5)]
    salt = 0x01020304
    out = {
        "meshSecret": mesh_secret.hex(), "recipientMemberId": recipient_id,
        "tagKey": tag_key.hex(), "sealKey": seal_key.hex(),
        "envelope": envelope.hex(), "nonce": nonce.hex(), "createdAt": created,
        "tag": packet[18:26].hex(), "packet": packet.hex(), "packetId": pid.hex(),
        "bloomIds": [f"{x:016x}" for x in ids], "bloomSalt": salt,
        "bloom": bloom_encode(ids, salt, words=4).hex(),
        "beacon": fingerprint_beacon(ids).hex(),
    }
    print(json.dumps(out, indent=2))


main()
