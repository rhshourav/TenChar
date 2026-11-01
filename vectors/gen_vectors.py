#!/usr/bin/env python3
"""
Independent reference vectors for the TenChar protocol.

Written from the protocol description (docs/client-protocol.md), NOT from the Kotlin code.
It uses `cryptography` (OpenSSL) and `hashlib`, with fixed "random" inputs, so every byte of
the handshake and of a five-message ratchet transcript is reproducible.

Run:  python3 gen_vectors.py > vectors.json
"""
import hashlib, hmac, json, sys
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

H = lambda b: b.hex()

def seed(label, n=32):
    """Fixed, visibly arbitrary bytes standing in for random draws."""
    return hashlib.sha256(b"tenchar-test-vector/" + label.encode()).digest()[:n]

def pub_of(priv):
    return X25519PrivateKey.from_private_bytes(priv).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)

def dh(priv, pub):
    out = X25519PrivateKey.from_private_bytes(priv).exchange(X25519PublicKey.from_public_bytes(pub))
    assert out != bytes(32)
    return out

def hkdf(salt, ikm, info, length):
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt if salt else None, info=info).derive(ikm)

def hmac_sha256(key, data):
    return hmac.new(key, data, hashlib.sha256).digest()

# ---- published known answers, asserted so a wrong constant cannot slip into the output ---------------
def check_published():
    # RFC 5869, test case 1
    okm = hkdf(bytes(range(13)), b"\x0b" * 22, bytes(range(0xf0, 0xfa)), 42)
    assert okm.hex() == ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                         "34007208d5b887185865"), "RFC 5869 case 1"
    # RFC 7748 section 6.1
    a = bytes.fromhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    b = bytes.fromhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    assert dh(a, pub_of(b)).hex() == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
    # GCM specification test case 16 (AES-256, with AAD)
    key = bytes.fromhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    iv = bytes.fromhex("cafebabefacedbaddecaf888")
    pt = bytes.fromhex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
                       "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
    aad = bytes.fromhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    ct = AESGCM(key).encrypt(iv, pt, aad)
    assert ct[-16:].hex() == "76fc6ece0f4e1768cddf8853bb2d551b", "GCM case 16 tag"
    return {
        "hkdf_rfc5869_case1": {"salt": H(bytes(range(13))), "ikm": H(b"\x0b" * 22),
                               "info": H(bytes(range(0xf0, 0xfa))), "length": 42, "okm": H(okm)},
        "aes256gcm_case16": {"key": H(key), "iv": H(iv), "aad": H(aad), "plaintext": H(pt),
                             "ciphertext_and_tag": H(ct)},
    }

# ---- pairing ------------------------------------------------------------------------------------------
CODE = "ABCDEFGHJKLMNPQR"
def pbkdf2(salt):
    return hashlib.pbkdf2_hmac("sha256", CODE.encode(), salt, 600_000, 32)

# ---- handshake ----------------------------------------------------------------------------------------
def member(name, member_id):
    ik, ek = seed(name + "/ik"), seed(name + "/ek")
    ikp, ekp = pub_of(ik), pub_of(ek)
    idb = bytes.fromhex(member_id)
    commit = hashlib.sha256(b"tenchar-commit-v1" + ikp + ekp + idb).digest()
    return dict(id=member_id, ik=ik, ek=ek, ikp=ikp, ekp=ekp, commit=commit)

def agree(code_key, i, r):
    """i and r are the initiator and responder members (initiator = smaller member id)."""
    dh1, dh2 = dh(i["ik"], r["ekp"]), dh(i["ek"], r["ikp"])
    dh3, dh4 = dh(i["ek"], r["ekp"]), dh(i["ik"], r["ikp"])
    ikm = b"\xff" * 32 + dh1 + dh2 + dh3 + dh4
    info = (b"tenchar-x3dh-v1" + i["ikp"] + i["ekp"] + r["ikp"] + r["ekp"]
            + bytes.fromhex(i["id"]) + bytes.fromhex(r["id"]))
    sk = hkdf(code_key, ikm, info, 32)
    ad = i["ikp"] + r["ikp"]
    digits = hkdf(b"", sk, b"tenchar-verify-v1" + info, 7)
    number = "%08d" % (int.from_bytes(digits, "big") % 10**8)
    return sk, ad, number[:4] + " " + number[4:]

# ---- double ratchet (written from the specification) --------------------------------------------------
def kdf_rk(rk, dh_out):
    out = hkdf(rk, dh_out, b"tenchar-ratchet-v1", 64)
    return out[:32], out[32:]

def kdf_ck(ck):
    return hmac_sha256(ck, b"\x02"), hmac_sha256(ck, b"\x01")   # (next chain key, message key)

def msg_key(mk):
    return hkdf(b"", mk, b"tenchar-message-v1", 32)

def frame_of(text):
    raw = text.encode("utf-8")
    assert 1 <= len(raw) <= 64
    return bytes([1, len(raw)]) + raw + bytes(64 - len(raw))

def text_of(frame):
    assert frame[0] == 1 and len(frame) == 66
    n = frame[1]
    assert frame[2 + n:] == bytes(64 - n)
    return frame[2:2 + n].decode("utf-8")

class Side:
    def __init__(self, ad, key_queue, nonce_for):
        self.ad, self.keys, self.nonce_for = ad, list(key_queue), nonce_for
        self.skipped = {}

    def new_keypair(self):
        priv = self.keys.pop(0)
        return priv, pub_of(priv)

    def init_initiator(self, sk, ek_priv, ek_pub, peer_ek):
        self.dhs_priv, self.dhs_pub, self.dhr = ek_priv, ek_pub, peer_ek
        self.rk, self.cks = kdf_rk(sk, dh(ek_priv, peer_ek))
        self.ckr, self.ns, self.nr, self.pn = None, 0, 0, 0

    def init_responder(self, sk, ek_priv, peer_ek):
        rk1, self.ckr = kdf_rk(sk, dh(ek_priv, peer_ek))
        self.dhs_priv, self.dhs_pub = self.new_keypair()
        self.rk, self.cks = kdf_rk(rk1, dh(self.dhs_priv, peer_ek))
        self.dhr, self.ns, self.nr, self.pn = peer_ek, 0, 0, 0

    def send(self, step, text):
        self.cks, mk = kdf_ck(self.cks)
        header = b"\x01" + self.dhs_pub + self.pn.to_bytes(4, "big") + self.ns.to_bytes(4, "big")
        self.ns += 1
        nonce = self.nonce_for(step)
        frame = frame_of(text)
        return frame, header + nonce + AESGCM(msg_key(mk)).encrypt(nonce, frame, header + self.ad)

    def _skip(self, until):
        if self.ckr is None:
            return
        while self.nr < until:
            self.ckr, mk = kdf_ck(self.ckr)
            self.skipped[(self.dhr, self.nr)] = mk
            self.nr += 1

    def receive(self, env):
        assert len(env) == 135 and env[0] == 1
        header, nonce, sealed = env[:41], env[41:53], env[53:]
        dhp, pn, n = header[1:33], int.from_bytes(header[33:37], "big"), int.from_bytes(header[37:41], "big")
        mk = self.skipped.pop((dhp, n), None)
        if mk is None:
            if dhp != self.dhr:
                self._skip(pn)
                self.pn, self.ns, self.nr, self.dhr = self.ns, 0, 0, dhp
                self.rk, self.ckr = kdf_rk(self.rk, dh(self.dhs_priv, self.dhr))
                self.dhs_priv, self.dhs_pub = self.new_keypair()
                self.rk, self.cks = kdf_rk(self.rk, dh(self.dhs_priv, self.dhr))
            self._skip(n)
            self.ckr, mk = kdf_ck(self.ckr)
            self.nr += 1
        return AESGCM(msg_key(mk)).decrypt(nonce, sealed, header + self.ad)

def main():
    published = check_published()
    room = pbkdf2(b"tenchar-room-v1")
    token = pbkdf2(b"tenchar-auth-v1")
    code_key = pbkdf2(b"tenchar-key-v1")
    assert room.hex()[:32] == "f1b914add3fb600eb619488f2d3e622c"          # same value the Kotlin PairingTest pins
    assert token.hex()[:48] == "5d3b0d40eb1b4274dde289ad6d7f6f84f6c4c64d23f2ddcf"

    A = member("A", "11" * 16)      # the smaller member id: the initiator
    B = member("B", "22" * 16)
    assert A["id"] < B["id"]
    sk, ad, number = agree(code_key, A, B)
    sk2, ad2, number2 = agree(code_key, A, B)
    assert (sk, ad, number) == (sk2, ad2, number2)

    # Five messages. Send order 1..5, delivery order 1,2,4,3,5. Senders: A, B, A, A, B.
    texts = ["hi", "ok!", "on my way", "আমি আসছি", "\U0001F44D"]
    senders = ["A", "B", "A", "A", "B"]
    nonces = [seed("nonce/%d" % k, 12) for k in range(1, 6)]
    r_keys = [seed("B/ratchet/first"), seed("B/ratchet/3")]
    i_keys = [seed("A/ratchet/2"), seed("A/ratchet/4")]
    side_a = Side(ad, i_keys, lambda step: nonces[step - 1])
    side_b = Side(ad, r_keys, lambda step: nonces[step - 1])
    side_a.init_initiator(sk, A["ek"], A["ekp"], B["ekp"])
    side_b.init_responder(sk, B["ek"], A["ekp"])

    sides = {"A": side_a, "B": side_b}
    env, frame = {}, {}
    def send(step):
        frame[step], env[step] = sides[senders[step - 1]].send(step, texts[step - 1])
    def deliver(step):
        receiver = sides["B" if senders[step - 1] == "A" else "A"]
        assert text_of(receiver.receive(env[step])) == texts[step - 1]

    # Chronological script that respects what each side knows when it sends.
    send(1); deliver(1)
    send(2); deliver(2)
    send(3); send(4); deliver(4); deliver(3)     # step 4 overtakes step 3
    send(5); deliver(5)
    assert not side_a.keys and not side_b.keys   # every scripted key was used exactly once

    out = {
        "note": "Generated by gen_vectors.py from the written spec. All keys, ids and nonces are fixed test values.",
        "published": published,
        "pairing": {"code": CODE, "room": room.hex()[:32], "codeToken": token.hex()[:48], "codeKey": code_key.hex()},
        "members": {k: {"id": m["id"], "ik": H(m["ik"]), "ek": H(m["ek"]), "ikp": H(m["ikp"]),
                        "ekp": H(m["ekp"]), "commit": H(m["commit"])} for k, m in (("A", A), ("B", B))},
        "handshake": {"initiator": "A", "SK": H(sk), "AD": H(ad), "verification": number},
        "ratchet": {
            "injected": {
                "nonces_in_send_order": [H(n) for n in nonces],
                "R_first_ratchet_priv": H(r_keys[0]), "R_ratchet3_priv": H(r_keys[1]),
                "I_ratchet2_priv": H(i_keys[0]), "I_ratchet4_priv": H(i_keys[1]),
            },
            "send_order": [1, 2, 3, 4, 5],
            "delivery_order": [1, 2, 4, 3, 5],
            "steps": [{"step": k, "from": senders[k - 1], "text": texts[k - 1],
                       "frame_hex": H(frame[k]), "envelope_hex": H(env[k])} for k in range(1, 6)],
        },
    }
    json.dump(out, sys.stdout, indent=2, ensure_ascii=True)
    print()

main()