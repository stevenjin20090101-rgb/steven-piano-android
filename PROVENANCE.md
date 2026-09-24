<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Authorship Provenance

Steven Piano, the Android app in this repository, is the original work of
**Steven Jin** (`stevenjin20090101@gmail.com`). This file explains how anyone
can **cryptographically verify** that Steven Jin authored these exact files,
and how Steven can prove it even if every visible credit is stripped out.

## The layers of proof

| Layer | Where | Strength |
|---|---|---|
| Copyright headers | top of every source file, `LICENSE`, `AUTHORS` | legal / attribution |
| Compiled-in watermark | `Provenance.TAG` in the app's DEX: `unzip -p app-release.apk classes.dex \| strings \| grep STEVEN-PIANO-PROVENANCE`; the same line is the manifest meta-data `dev.stevenjin.stevenpiano.provenance` | survives in a copied APK |
| Visible credit | the About row at the bottom of the Piano tab (`Provenance.text`) | seen by every user |
| **Ed25519 signature** | `provenance/` | **cryptographic proof of authorship** |

## The cryptographic signature (the real proof)

Steven holds a secret **Ed25519 private key** (never in this repo). The matching
**public key** is committed at
[`provenance/author_ed25519_public.pem`](provenance/author_ed25519_public.pem),
public fingerprint **`eab16a502f679465`**. It is the same key that signs the
piano's firmware.

`provenance/MANIFEST.txt` lists every source file with its SHA-256 hash, and
`provenance/MANIFEST.sig` is that manifest **signed with the private key**.
Because only Steven holds the private key, only Steven could have produced a
valid signature over these files, and the git commit dates timestamp *when*.

### Verify it yourself

```bash
cd android
python3 provenance/verify.py
```

The script recomputes every file's hash, checks them against the manifest, and
verifies the signature against the public key. It prints **`AUTHORSHIP VERIFIED`**
only if the files are unmodified and genuinely signed by Steven Jin's key.
(Needs the `cryptography` Python package: `pip install cryptography`.)

### Why this stops theft

- A thief who copies the files **cannot forge a new valid signature** for any
  modification: they don't have the private key.
- If someone strips the headers and claims the work, Steven signs a fresh
  challenge with his private key on demand, instantly proving he controls the
  key that signed the original, timestamped commit.
- Even a rebuilt APK with the credits removed from the screens still carries the
  `STEVEN-PIANO-PROVENANCE` line unless the code itself is changed, and any such
  change breaks the signed manifest.

## For Steven — keep this safe

Your private key is at `~/piano-authorship-PRIVATE-DO-NOT-SHARE.pem`.

- **Never commit it. Never share it.** `*.pem` and `*PRIVATE*` are `.gitignore`d,
  except the public key.
- After any source change, re-sign, verify and commit `provenance/`:
  ```bash
  python3 provenance/sign.py && python3 provenance/verify.py
  ```
- If you ever need to re-prove authorship, sign any text with it:
  ```bash
  python3 -c "from cryptography.hazmat.primitives.serialization import load_pem_private_key; \
  k=load_pem_private_key(open('$HOME/piano-authorship-PRIVATE-DO-NOT-SHARE.pem','rb').read(),None); \
  open('challenge.sig','wb').write(k.sign(b'I am Steven Jin, author of Steven Piano'))"
  ```
  Anyone can verify that `challenge.sig` against the public key in this repo.

---

*Steven Piano — © 2026 Steven Jin. Licensed under the MIT License.*
