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


if __name__ == "__main__":
    unittest.main()
