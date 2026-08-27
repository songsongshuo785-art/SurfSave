from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from release_manifest import (
    APK_OFFSETS,
    CandidateValidationError,
    create_manifest,
    verify_manifest,
    verify_release_assets,
)


class ReleaseManifestTest(unittest.TestCase):
    repository = "songsongshuo785-art/SurfSave"
    run_id = 123456
    source_sha = "a" * 40
    source_ref = "refs/heads/main"
    tag = "v0.8.32"
    version_name = "0.8.32"
    base_version_code = 1_788_000_000
    certificate = "c33027eef9607dcf592ac7f8fefe47961e728c349541c3fa23c99355e2edbcc1"

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.apk_dir = Path(self.temp.name)
        for index, name in enumerate(APK_OFFSETS):
            (self.apk_dir / name).write_bytes(f"apk-{index}".encode())

    def tearDown(self) -> None:
        self.temp.cleanup()

    def manifest(self) -> dict:
        return create_manifest(
            apk_dir=self.apk_dir,
            repository=self.repository,
            run_id=self.run_id,
            source_sha=self.source_sha,
            source_ref=self.source_ref,
            tag=self.tag,
            version_name=self.version_name,
            base_version_code=self.base_version_code,
            certificate_sha256=self.certificate,
        )

    def verify(self, manifest: dict) -> None:
        verify_manifest(
            manifest,
            apk_dir=self.apk_dir,
            repository=self.repository,
            run_id=self.run_id,
            source_sha=self.source_sha,
            source_ref=self.source_ref,
            tag=self.tag,
            certificate_sha256=self.certificate,
        )

    def test_create_and_verify_exact_candidate(self) -> None:
        manifest = self.manifest()
        self.verify(manifest)
        self.assertEqual(list(APK_OFFSETS), [entry["name"] for entry in manifest["apks"]])

    def test_changed_apk_is_rejected(self) -> None:
        manifest = self.manifest()
        (self.apk_dir / "app-arm64-v8a-release.apk").write_bytes(b"changed")
        with self.assertRaisesRegex(CandidateValidationError, "size mismatch|SHA-256 mismatch"):
            self.verify(manifest)

    def test_wrong_tag_is_rejected(self) -> None:
        manifest = self.manifest()
        manifest["release"]["tag"] = "v0.8.33"
        with self.assertRaisesRegex(CandidateValidationError, "release tag mismatch"):
            self.verify(manifest)

    def test_release_asset_digests_must_match(self) -> None:
        manifest = self.manifest()
        release = {
            "tag_name": self.tag,
            "assets": [
                {
                    "name": entry["name"],
                    "size": entry["size"],
                    "state": "uploaded",
                    "digest": f"sha256:{entry['sha256']}",
                }
                for entry in manifest["apks"]
            ],
        }
        verify_release_assets(manifest, release)
        release["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(CandidateValidationError, "digest mismatch"):
            verify_release_assets(manifest, release)

    def test_manifest_json_is_utf8_serializable(self) -> None:
        encoded = json.dumps(self.manifest(), sort_keys=True).encode("utf-8")
        self.assertIn(b'"schemaVersion": 1', encoded)


if __name__ == "__main__":
    unittest.main()
