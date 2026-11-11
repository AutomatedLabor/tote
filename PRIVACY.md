# Tote — Privacy Policy

> **Last updated:** 2026-11-11 (v1.0.0-alpha1)
> **Effective date:** 2026-11-11

## TL;DR

Tote has **no analytics, no telemetry, no crash reporting, no advertising SDKs, and no account system**. All cryptography happens on-device. The relay sees only opaque ciphertext envelopes — your cookies are encrypted to a key derived from a shared ECDH secret that the relay never sees.

Source code: https://github.com/AutomatedLabor/tote (MIT License)

## What Tote collects

**Nothing.** Tote does not collect, transmit, or store any personal data with the developer. There is no Tote account, no Tote server, no Tote database of users.

## What Tote stores locally on your device

| Stored | Where | Encrypted at rest | Purpose |
|---|---|---|---|
| Device Ed25519 + X25519 keypair (32-byte seed) | app-private SharedPreferences (`tote_identity`) | Yes — AndroidKeyStore AES-256-GCM master key wraps the seed before storing | Your device's signing + ECDH identity for pairing + signing session blobs |
| Pairing root (32 bytes) | Same SharedPreferences | Yes — same master key | The shared secret derived from ECDH during pairing; seeds the epoch ratchet |
| Argon2id salt (16 bytes) | Same SharedPreferences | No (not secret — defeats rainbow tables) | Salt for deriving pairingRoot from recovery code |
| Paired device list | app-private SharedPreferences (`tote_peers`) | Yes — AndroidKeyStore master key wraps the JSON | List of devices you've paired with (their device IDs + display names) |
| Duress code (≥8 chars) | app-private SharedPreferences (`tote_kill_switch`) | No (TODO v1.0.0: AndroidKeyStore wrap) | Trigger for the duress-mode wipe |
| WebView cookies per profile | `/data/data/com.tote.app/app_webview/<profileId>/Cookies` (SQLite) | No (per profile, sandboxed) | The actual web sessions you're transferring |
| Tab state (history, scrollY, form data) | In-memory + Android `WebView.saveState` Bundle | No (in-memory only) | Truly-live tabs — survives tab switch, lost on process death |

## What Tote sends to the relay

The relay is a **dumb ciphertext mailbox** — it holds envelopes that you (the sender) post to a paired device's inbox, and the paired device polls/downloads them. The relay:

- **Receives**: `POST /envelope` with the recipient's device ID (hex Ed25519 pubkey), the ciphertext blob (AEAD-encrypted cookies), and a 20-bit PoW nonce.
- **Stores**: envelope ID, sender device ID, recipient device ID, ciphertext, timestamp, TTL.
- **Returns**: `GET /inbox/<recipientDeviceId>` lists pending envelopes; `GET /envelope/<id>` returns the ciphertext.
- **Deletes**: on `DELETE /envelope/<id>` from the recipient (ack).

The relay CANNOT read the ciphertext. It's encrypted with XChaCha20-Poly1305-IETF to a key derived from the shared ECDH secret between sender and recipient (the pairing root, fed through HKDF-SHA256 → epoch ratchet). The relay never sees the pairing root.

The relay also stores peer-pubkey records (`PUT /peer/<deviceId>` with your X25519 + Ed25519 pubkeys + display name) for the relay-mediated pairing handshake. These records have a 30-day TTL and are swept every 5 minutes. The pubkeys are NOT secret — they're public key material. The display name is user-set (defaults to "Device <8-hex-prefix>").

## What the relay can do

- See metadata: which device sent what-size envelope to which device, and when.
- Serve envelopes (ciphertext only) to anyone who proves ownership of the recipient device ID (which requires the Ed25519 private key).
- Drop envelopes (after the 7-day TTL).

## What the relay CANNOT do

- Decrypt your cookies (no key material).
- Forge your signature (no Ed25519 private key).
- Replay old sessions (the epoch ratchet advances; old epoch keys are useless).
- Identify you across sessions (no account, no IP logging beyond rate-limiting).

## Permissions Tote requests

| Permission | Why |
|---|---|
| `INTERNET` | Relay communication (HTTP + WebSocket) |
| `ACCESS_NETWORK_STATE` | Detect offline state for sync service backoff |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | The sync service that polls the relay for incoming session blobs in the background. Special-use subtype: "Polls the encrypted session relay for incoming cookie blobs dispatched by other Tote devices paired with this one." |
| `POST_NOTIFICATIONS` | The sync service notification ("Listening for incoming sessions…") and the per-incoming-session notification ("Incoming session applied: N cookies") |
| `CAMERA` | QR code scanning for device pairing. Optional — only triggered when you tap "Pair a device". The camera is opened only for the QR-scanning screen and is released as soon as a QR is decoded or you cancel. |
| `VIBRATE` | Haptic feedback on swipe gestures (tab switching). Optional. |

## Anti-fraud bright line (non-negotiable)

Tote does NOT forge TLS fingerprints, JA3/JA4 hashes, canvas fingerprints, WebGL fingerprints, or any other anti-fraud signal. Tote is a tool for transferring your own logins across your own devices — not for evading site security.

If you're a website operator who detects Tote usage on your site and you'd like us to provide guidance on how to distinguish legitimate cross-device session transfer from credential-stuffing attacks, contact us via GitHub Issues.

## Open source

Tote is open source under the MIT License. The source code is at https://github.com/AutomatedLabor/tote. The relay source is at https://github.com/AutomatedLabor/tote-relay. You can audit, fork, modify, and self-host everything.

## Changes to this policy

Changes will be committed to this file (`PRIVACY.md`) in the public GitHub repo. The "Last updated" date at the top will reflect the most recent change.

## Contact

Open an issue at https://github.com/AutomatedLabor/tote/issues for any privacy-related questions or concerns.
