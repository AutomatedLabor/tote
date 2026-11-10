# Tote Crypto Reference (Spike 0.4)

Validates the full pairing + blob encryption protocol using libsodium (pynacl).
This is the test-vector oracle that gates the Kotlin + Go ports.

## Run

```bash
pip install pynacl
python3 tote_crypto.py
```

## What it validates

1. **X25519 ECDH** (RFC 7748 §5.2 test vectors)
2. **HKDF-SHA256** (RFC 5869 Test Case 1)
3. **Pairing handshake** (QR + X25519 + HKDF with relay-identity-bound salt → pairing_root → SAS)
4. **Blob encryption** (XChaCha20-Poly1305 with AAD + Ed25519 signature)
5. **Epoch ratchet** (Variant B: random keys, sender-authenticated advance)
6. **Recovery code** (12-word BIP-39 from pairing_root via HKDF)
7. **Kill switch** (nuke + duress flows)
8. **SAS collision test** (1000 pairings, <1% collision rate)

## Protocol

Per `tote-docs/planning/CRYPTO-SPEC-FINAL.md`:

1. A generates ephemeral X25519 `(a, A_pub)` + 128-bit nonce `N_p`.
2. QR encodes `{relay_url, relay_pub, A_pub, N_p, idA_pub}`.
3. B scans, generates `(b, B_pub)` + nonce `N_ack`.
4. Both compute `Z = X25519(own_priv, peer_pub)`.
5. `pairing_root = HKDF(Z, salt=N_p|N_ack|"pair-v1"|relay_url|relay_pub, "pairing-root-v1")`
6. `SAS = HMAC-SHA256(pairing_root, "sas")` → 6-digit + emoji pair.
7. Human confirms SAS match → defeats MITM.

## License

MIT (same as the parent Tote project).
