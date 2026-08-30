# SurfSave release signing

SurfSave APK updates must retain the same Android signing identity. The expected release certificate is public information and is pinned here so maintainers and users can verify official APKs.

## Official certificate

```text
SHA-256: c33027eef9607dcf592ac7f8fefe47961e728c349541c3fa23c99355e2edbcc1
Application ID: com.surfsave.browser
```

The private key and its passwords must never be committed to this repository. The manually dispatched signed-candidate workflow reads the encrypted keystore and credentials from these Actions Secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The candidate workflow validates the keystore alias and certificate before installing the NDK/Go toolchain. After building, every APK is checked again with `apksigner`; a missing secret, unsigned APK, unexpected APK count, or certificate mismatch blocks candidate creation.

## Build once, test, and promote

SurfSave releases use immutable artifact promotion. A tag push does not build or publish APKs.

1. Commit the release version and push the exact source to `main`.
2. Build and download one formally signed candidate:

   ```powershell
   .\scripts\Build-ReleaseCandidate.ps1 -Tag v0.8.32
   ```

3. Install and test the APK already placed at:

   ```text
   app/build/outputs/apk/release/app-arm64-v8a-release.apk
   ```

4. After acceptance, promote that exact candidate:

   ```powershell
   .\scripts\Publish-ReleaseCandidate.ps1 -ConfirmTag v0.8.32
   ```

The candidate workflow performs the only Gradle, Go/Xray, packaging, and signing build. It stores five APKs plus `release-candidate-manifest.json` as an immutable GitHub Actions artifact for 30 days. The local helper downloads those files into the existing Gradle release output directory and verifies the workflow run, source commit, tag, certificate, file sizes, and SHA-256 values.

Promotion downloads the artifact by its recorded workflow run ID. It does not invoke Gradle, Go, packaging, or signing. Before publication it independently checks source ancestry, APK signatures and versions, then compares every draft Release asset digest with the tested candidate manifest. A public Release is never overwritten; an interrupted draft may be resumed with the same verified candidate.

`app/build/outputs/apk/release/app-arm64-v8a-release.apk` is the only standard phone-test
package. `assembleDiagnostic` and `exportDiagnosticApks` remain internal engineering tools;
their outputs are debuggable and must never be used for user installation acceptance or
GitHub Release promotion. Exported diagnostic filenames contain `INTERNAL-DIAGNOSTIC` to make
this boundary visible.

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
