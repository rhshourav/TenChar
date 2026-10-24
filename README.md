# TenChar

A private two-person messenger for Android. Every message is at most 10
characters. A small Cloudflare relay carries end-to-end encrypted envelopes.
There are no accounts, phone numbers, names, analytics or Google services.

**Status: Step 1 of the build (project skeleton, constants, Gradle).** Nothing
described under "Threat model" or "Design" below is implemented yet. Each
section will be marked as implemented only when the code exists and can be
checked.

## Decisions

- **Double Ratchet:** implemented from the Signal specification using only Tink
  primitives (X25519, HKDF-SHA256, ChaCha20-Poly1305). libsignal is not used,
  so the app is not bound to AGPL-3.0.
  **A hand-composed ratchet needs an independent review before anyone relies on
  it.** This project has not had one.
- **Languages:** English and Bangla (`values-bn`).

## Threat model

### Protected against

- A malicious or compromised relay or operator reading or altering messages.
- Network attackers (TLS plus end-to-end encryption).
- Someone who steals the locked phone (the keys need the user's
  authentication).
- Later compromise of one message key (forward secrecy and post-compromise
  recovery from the Double Ratchet).
- Someone who learns or guesses the pairing code after pairing is complete (the
  code stops working once both phones have joined).
- Replay, reordering, message injection, and reflection of your own messages.
- Message-length leakage (all frames are the same size).

### Not protected against

- Malware or a compromised OS on an unlocked phone, screen readers, and
  accessibility services with permission.
- A third-party keyboard that records what you type. The app cannot stop this.
- Metadata. The relay and Cloudflare can see IP addresses, timing, message
  counts, message sizes (constant), and ratchet public-key changes, which
  reveal who spoke when. The app does not hide your IP.
- The other person keeping, screenshotting or forwarding what you send.
- Denial of service, and relay availability.
- A weak lock screen on the phone. The app requires one but cannot judge it.
- Header contents. The 41-byte ratchet header is sent in the clear (it is
  authenticated, not encrypted). Header encryption is out of scope for v0.1.
- Memory inspection. Secrets are held in `ByteArray` and zeroed after use where
  possible, but JVM memory handling is best effort.

## Build

Requirements: JDK 17 or newer, Android SDK platform 35 and Build Tools 36.0.0.

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### Versions (pinned exactly in `gradle/libs.versions.toml`)

| Component | Version |
|---|---|
| Gradle | 9.6.0 |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.0 |
| Compose BOM | 2026.08.00 |
| androidx.core:core-ktx | 1.19.1 |
| androidx.activity:activity-compose | 1.13.0 |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.11.0 |
| androidx.biometric:biometric | 1.1.0 |
| androidx.work:work-runtime-ktx | 2.12.0 |
| kotlinx-coroutines-android | 1.11.0 |
| com.google.crypto.tink:tink-android | 1.23.0 |
| junit | 4.13.2 |
| org.json:json | 20250517 |

### Supply chain

Dependencies are pinned to exact versions, resolved with dependency locking,
and checked against `gradle/verification-metadata.xml`. Generate or update them
with network access:

```
./gradlew :app:dependencies --write-locks
./gradlew --write-verification-metadata sha256 :app:testDebugUnitTest :app:assembleRelease
```

Commit `app/gradle.lockfile` and `gradle/verification-metadata.xml`. To update a
dependency, change its version in the catalog, rerun both commands, and review
the diff of both files before committing.
