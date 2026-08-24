# SurfSave release signing

SurfSave APK updates must retain the same Android signing identity. The expected release certificate is public information and is pinned here so maintainers and users can verify official APKs.

## Official certificate

```text
SHA-256: c33027eef9607dcf592ac7f8fefe47961e728c349541c3fa23c99355e2edbcc1
Application ID: com.surfsave.browser
```

The private key and its passwords must never be committed to this repository. GitHub tag builds read the encrypted keystore and credentials from these Actions Secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The release workflow validates the keystore alias and certificate before installing the NDK/Go toolchain. After building, every APK is checked again with `apksigner`; a missing secret, unsigned APK, unexpected APK count, or certificate mismatch blocks publication.

## Verify a downloaded APK

Use `apksigner` from Android SDK Build Tools:

```powershell
apksigner verify --verbose --print-certs .\SurfSave.apk
```

The reported `Signer #1 certificate SHA-256 digest` must equal the official fingerprint above.

## Maintainer recovery policy

The GitHub Secrets are deployment inputs, not backups: their values cannot be read back after creation. Maintainers must keep the encrypted release keystore and recovery credentials outside the repository, with at least:

1. one restricted local copy;
2. one copy on a separate physical disk.

A third encrypted offline or off-site copy is strongly recommended.

After restoring a backup, verify the certificate fingerprint before changing GitHub Secrets or publishing a tag. Never generate a replacement key merely because the local copy is missing: direct APK upgrades signed by a different key will be rejected by Android.

## Distribution channels

GitHub Releases are signed by the SurfSave release key. Stores such as F-Droid or Google Play may manage their own signing pipeline; packages from different signing identities may not be interchangeable for in-place updates.
