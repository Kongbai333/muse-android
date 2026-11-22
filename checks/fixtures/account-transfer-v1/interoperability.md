# v1 implementation exchange — 2026-11-22

Synthetic artifacts only. The reviewed candidate protocol SHA-256 was
`22a24a7c07b3f40d4a4d427c43acde001f01702499061543d995909f1b348a65`.
Freezing changed the document status/evidence only, not envelope/AAD/schema semantics.

| Producer | Artifact | SHA-256 | Consumer evidence |
| --- | --- | --- | --- |
| Java standard JCA, JBR 21.0.10 | java-ascii.muse-account | 0b3b7a25dd482ccde7f57a5e0f2c8547e644d659d3ab90773d5b0931c6242e59 | PC reported real Rust ring 0.17.14 decrypt, plaintext equals payload.json |
| Java standard JCA, JBR 21.0.10 | java-unicode.muse-account | 2995893253a793eabcc17b813fe35139ff7c7c3a446010b41d949991811b699d | PC reported real Rust ring decrypt, exact plaintext |
| Rust ring 0.17.14, Windows PC worker | rust-unicode.muse-account | 55a9307d8df12f5d3100308f6e322ce60fe975dae6fba0db4e6e5b810e74be34 | Independently checked hash, Java standard JCA decrypt, exact plaintext |

Reference producer: Python hashlib.pbkdf2_hmac + cryptography AESGCM. Both implementations
also consumed/re-encrypted fixed ASCII and Chinese/emoji vectors exactly. PC reported
`cargo test -p muse-desktop account_transfer -- --test-threads=1`: exit 0, 9 passed.
This worker ran `scripts/check-account-transfer.ps1 -JavaHome <JDK21> -ForeignEnvelope
<absolute fixture rust-unicode.muse-account>`: 35 JCA checks passed, plus 37 schema/Cookie
checks, exit 0. Original outputs are retained in the implementation ledger directory.

Passphrase bytes are the matching `.properties` field `passphrase_utf8_base64`; all are
public synthetic test values. No user credentials, live authentication or proxy requests
were used. These host implementation checks are not a device/provider or real-login test.
