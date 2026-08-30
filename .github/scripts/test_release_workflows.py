from __future__ import annotations

import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class ReleaseWorkflowContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (REPOSITORY_ROOT / relative).read_text(encoding="utf-8")

    def test_tag_push_cannot_rebuild_release(self) -> None:
        diagnostic = self.read(".github/workflows/android.yml")
        self.assertNotIn("tags:", diagnostic)
        self.assertNotIn("assembleRelease", diagnostic)
        self.assertNotIn("KEYSTORE_BASE64", diagnostic)

    def test_candidate_is_the_only_release_builder(self) -> None:
        candidate = self.read(".github/workflows/release-candidate.yml")
        self.assertIn("workflow_dispatch:", candidate)
        self.assertIn("assembleRelease", candidate)
        self.assertIn("KEYSTORE_BASE64", candidate)
        self.assertIn("retention-days: 30", candidate)
        self.assertIn("compression-level: 0", candidate)
        self.assertIn("release-candidate-manifest.json", candidate)
        self.assertIn("Verified using v2 scheme (APK Signature Scheme v2): true", candidate)
        self.assertIn("application-debuggable", candidate)
        self.assertIn("Release candidate must not be debuggable", candidate)

    def test_build_workflows_pin_rust_and_verify_native_apks(self) -> None:
        diagnostic = self.read(".github/workflows/android.yml")
        candidate = self.read(".github/workflows/release-candidate.yml")
        for workflow, variant in (
            (diagnostic, "diagnostic"),
            (candidate, "release"),
        ):
            self.assertIn("rustup toolchain install 1.98.0", workflow)
            self.assertIn("aarch64-linux-android", workflow)
            self.assertIn("x86_64-linux-android", workflow)
            self.assertIn("verify_native_libs.py", workflow)
            self.assertIn(f"--variant {variant}", workflow)

    def test_promotion_cannot_rebuild_or_resign(self) -> None:
        promotion = self.read(".github/workflows/promote-release.yml")
        for forbidden in (
            "gradlew",
            "assembleRelease",
            "setup-go",
            "KEYSTORE_BASE64",
            "KEYSTORE_PASSWORD",
            "base64 --decode",
            "keytool -exportcert",
            "rustup toolchain install",
            "verify_native_libs.py",
        ):
            self.assertNotIn(forbidden, promotion)
        self.assertIn("actions/download-artifact@v5", promotion)
        self.assertIn("run-id: ${{ inputs.candidate_run_id }}", promotion)
        self.assertIn("verify-release", promotion)

    def test_draft_release_is_verified_by_id_before_publication(self) -> None:
        promotion = self.read(".github/workflows/promote-release.yml")
        self.assertIn("id: release", promotion)
        self.assertIn("releases?per_page=100", promotion)
        self.assertIn("RELEASE_ID: ${{ steps.release.outputs.release_id }}", promotion)
        self.assertIn('releases/$RELEASE_ID', promotion)
        self.assertEqual(promotion.count('releases/tags/$RELEASE_TAG'), 1)

    def test_local_helpers_keep_the_existing_release_output(self) -> None:
        build = self.read("scripts/Build-ReleaseCandidate.ps1")
        publish = self.read("scripts/Publish-ReleaseCandidate.ps1")
        expected = r"app\build\outputs\apk\release"
        self.assertIn(expected, build)
        self.assertIn(expected, publish)
        self.assertNotIn(r"D:\Downloads", build)
        self.assertNotIn(r"D:\Downloads", publish)
        self.assertIn("release-candidate-manifest.json", build)
        self.assertIn("release-candidate-manifest.json", publish)

    def test_standard_phone_test_apk_is_the_release_candidate(self) -> None:
        build = self.read("scripts/Build-ReleaseCandidate.ps1")
        self.assertIn("app-arm64-v8a-release.apk", build)
        self.assertIn(r"app\build\outputs\apk\release", build)
        self.assertNotIn(r"app\build\outputs\apk\diagnostic\app-arm64-v8a-diagnostic.apk", build)
        self.assertNotIn("Publish-StandardTestApk", build)
        self.assertNotIn("assembleDiagnostic", build)

    def test_diagnostic_export_is_visibly_internal(self) -> None:
        gradle = self.read("app/build.gradle.kts")
        self.assertIn("INTERNAL-DIAGNOSTIC.apk", gradle)
        self.assertIn("Do not use these APKs for release acceptance", gradle)
        self.assertIn("Build-ReleaseCandidate.ps1", gradle)


if __name__ == "__main__":
    unittest.main()
