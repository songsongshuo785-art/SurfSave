#!/usr/bin/env python3
"""Create and verify immutable SurfSave release-candidate manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
APPLICATION_ID = "com.surfsave.browser"
WORKFLOW_PATH = ".github/workflows/release-candidate.yml"
APK_OFFSETS = {
    "app-universal-release.apk": 0,
    "app-armeabi-v7a-release.apk": 1,
    "app-arm64-v8a-release.apk": 2,
    "app-x86-release.apk": 3,
    "app-x86_64-release.apk": 4,
}
SEMVER_TAG = re.compile(r"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA = re.compile(r"^[0-9a-f]{40}$")


class CandidateValidationError(ValueError):
    """Raised when a candidate or release asset violates the frozen contract."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CandidateValidationError(message)


def _require_exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    _require(actual == expected, f"{label} keys mismatch: expected={sorted(expected)} actual={sorted(actual)}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_identity(
    *,
    repository: str,
    run_id: int,
    source_sha: str,
    source_ref: str,
    tag: str,
    version_name: str,
    base_version_code: int,
    certificate_sha256: str,
) -> None:
    _require(repository.count("/") == 1 and not repository.startswith("/"), "Invalid repository identity")
    _require(run_id > 0, "Workflow run ID must be positive")
    _require(bool(GIT_SHA.fullmatch(source_sha)), "Source SHA must be a lowercase 40-character Git SHA")
    _require(source_ref.startswith("refs/heads/"), "Candidate source must be a branch ref")
    _require(bool(SEMVER_TAG.fullmatch(tag)), "Release tag must use vMAJOR.MINOR.PATCH")
    _require(tag == f"v{version_name}", "Release tag and versionName do not match")
    _require(base_version_code > 0, "baseVersionCode must be positive")
    _require(base_version_code + max(APK_OFFSETS.values()) <= 2_100_000_000, "ABI versionCode exceeds Android limit")
    _require(bool(SHA256.fullmatch(certificate_sha256)), "Certificate SHA-256 must be lowercase hexadecimal")


def create_manifest(
    *,
    apk_dir: Path,
    repository: str,
    run_id: int,
    source_sha: str,
    source_ref: str,
    tag: str,
    version_name: str,
    base_version_code: int,
    certificate_sha256: str,
) -> dict[str, Any]:
    certificate_sha256 = certificate_sha256.lower()
    _validate_identity(
        repository=repository,
        run_id=run_id,
        source_sha=source_sha,
        source_ref=source_ref,
        tag=tag,
        version_name=version_name,
        base_version_code=base_version_code,
        certificate_sha256=certificate_sha256,
    )

    apks: list[dict[str, Any]] = []
    for name, offset in APK_OFFSETS.items():
        path = apk_dir / name
        _require(path.is_file(), f"Missing expected APK: {name}")
        apks.append(
            {
                "name": name,
                "size": path.stat().st_size,
                "sha256": _sha256(path),
                "versionCode": base_version_code + offset,
            }
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "repository": repository,
        "workflow": {"path": WORKFLOW_PATH, "runId": run_id},
        "source": {"sha": source_sha, "ref": source_ref},
        "release": {
            "tag": tag,
            "versionName": version_name,
            "baseVersionCode": base_version_code,
            "applicationId": APPLICATION_ID,
            "certificateSha256": certificate_sha256,
        },
        "apks": apks,
    }


def write_manifest(manifest: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CandidateValidationError(f"Unable to read candidate manifest: {error}") from error
    _require(isinstance(value, dict), "Candidate manifest root must be an object")
    return value


def verify_manifest(
    manifest: dict[str, Any],
    *,
    apk_dir: Path,
    repository: str,
    run_id: int,
    source_sha: str,
    source_ref: str,
    tag: str,
    certificate_sha256: str,
) -> None:
    _require_exact_keys(
        manifest,
        {"schemaVersion", "repository", "workflow", "source", "release", "apks"},
        "manifest",
    )
    _require(manifest["schemaVersion"] == SCHEMA_VERSION, "Unsupported candidate manifest schema")
    _require(manifest["repository"] == repository, "Candidate repository mismatch")

    workflow = manifest["workflow"]
    source = manifest["source"]
    release = manifest["release"]
    apks = manifest["apks"]
    _require(isinstance(workflow, dict), "workflow must be an object")
    _require(isinstance(source, dict), "source must be an object")
    _require(isinstance(release, dict), "release must be an object")
    _require(isinstance(apks, list), "apks must be an array")
    _require_exact_keys(workflow, {"path", "runId"}, "workflow")
    _require_exact_keys(source, {"sha", "ref"}, "source")
    _require_exact_keys(
        release,
        {"tag", "versionName", "baseVersionCode", "applicationId", "certificateSha256"},
        "release",
    )

    _require(workflow["path"] == WORKFLOW_PATH, "Candidate workflow path mismatch")
    _require(workflow["runId"] == run_id, "Candidate workflow run ID mismatch")
    _require(source["sha"] == source_sha, "Candidate source SHA mismatch")
    _require(source["ref"] == source_ref, "Candidate source ref mismatch")
    _require(release["tag"] == tag, "Candidate release tag mismatch")
    _require(release["applicationId"] == APPLICATION_ID, "Candidate application ID mismatch")
    _require(release["certificateSha256"] == certificate_sha256.lower(), "Candidate certificate mismatch")
    _require(isinstance(release["versionName"], str), "versionName must be a string")
    _require(type(release["baseVersionCode"]) is int, "baseVersionCode must be an integer")
    _validate_identity(
        repository=repository,
        run_id=run_id,
        source_sha=source_sha,
        source_ref=source_ref,
        tag=tag,
        version_name=release["versionName"],
        base_version_code=release["baseVersionCode"],
        certificate_sha256=release["certificateSha256"],
    )

    _require(len(apks) == len(APK_OFFSETS), f"Expected {len(APK_OFFSETS)} APK manifest entries")
    by_name: dict[str, dict[str, Any]] = {}
    for entry in apks:
        _require(isinstance(entry, dict), "Every APK entry must be an object")
        _require_exact_keys(entry, {"name", "size", "sha256", "versionCode"}, "APK entry")
        name = entry["name"]
        _require(isinstance(name, str) and Path(name).name == name, "APK name must be a basename")
        _require(name not in by_name, f"Duplicate APK entry: {name}")
        by_name[name] = entry

    _require(set(by_name) == set(APK_OFFSETS), "Candidate APK filename set mismatch")
    for name, offset in APK_OFFSETS.items():
        entry = by_name[name]
        path = apk_dir / name
        _require(path.is_file(), f"Candidate APK is missing: {name}")
        _require(type(entry["size"]) is int and entry["size"] > 0, f"Invalid APK size: {name}")
        _require(entry["size"] == path.stat().st_size, f"Candidate APK size mismatch: {name}")
        _require(isinstance(entry["sha256"], str) and SHA256.fullmatch(entry["sha256"]) is not None, f"Invalid APK SHA-256: {name}")
        _require(entry["sha256"] == _sha256(path), f"Candidate APK SHA-256 mismatch: {name}")
        expected_code = release["baseVersionCode"] + offset
        _require(entry["versionCode"] == expected_code, f"Candidate APK versionCode mismatch: {name}")


def verify_release_assets(manifest: dict[str, Any], release_json: dict[str, Any]) -> None:
    _require(release_json.get("tag_name") == manifest["release"]["tag"], "Published Release tag mismatch")
    assets = release_json.get("assets")
    _require(isinstance(assets, list), "Release assets must be an array")
    _require(len(assets) == len(APK_OFFSETS), f"Published Release must contain exactly {len(APK_OFFSETS)} assets")
    expected = {entry["name"]: entry for entry in manifest["apks"]}
    actual: dict[str, dict[str, Any]] = {}
    for asset in assets:
        _require(isinstance(asset, dict) and isinstance(asset.get("name"), str), "Invalid Release asset")
        _require(asset["name"] not in actual, f"Duplicate Release asset: {asset['name']}")
        actual[asset["name"]] = asset
    _require(set(actual) == set(expected), "Published Release asset filename set mismatch")
    for name, entry in expected.items():
        asset = actual[name]
        _require(asset.get("state") == "uploaded", f"Release asset is not uploaded: {name}")
        _require(asset.get("size") == entry["size"], f"Release asset size mismatch: {name}")
        _require(asset.get("digest") == f"sha256:{entry['sha256']}", f"Release asset digest mismatch: {name}")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_identity(subparser: argparse.ArgumentParser, *, include_version: bool) -> None:
        subparser.add_argument("--apk-dir", type=Path, required=True)
        subparser.add_argument("--repository", required=True)
        subparser.add_argument("--run-id", type=int, required=True)
        subparser.add_argument("--source-sha", required=True)
        subparser.add_argument("--source-ref", required=True)
        subparser.add_argument("--tag", required=True)
        subparser.add_argument("--certificate-sha256", required=True)
        if include_version:
            subparser.add_argument("--version-name", required=True)
            subparser.add_argument("--base-version-code", type=int, required=True)

    create = subparsers.add_parser("create")
    add_identity(create, include_version=True)
    create.add_argument("--output", type=Path, required=True)

    verify = subparsers.add_parser("verify")
    add_identity(verify, include_version=False)
    verify.add_argument("--manifest", type=Path, required=True)

    verify_release = subparsers.add_parser("verify-release")
    verify_release.add_argument("--manifest", type=Path, required=True)
    verify_release.add_argument("--release-json", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "create":
            manifest = create_manifest(
                apk_dir=args.apk_dir,
                repository=args.repository,
                run_id=args.run_id,
                source_sha=args.source_sha,
                source_ref=args.source_ref,
                tag=args.tag,
                version_name=args.version_name,
                base_version_code=args.base_version_code,
                certificate_sha256=args.certificate_sha256.lower(),
            )
            write_manifest(manifest, args.output)
        elif args.command == "verify":
            verify_manifest(
                load_manifest(args.manifest),
                apk_dir=args.apk_dir,
                repository=args.repository,
                run_id=args.run_id,
                source_sha=args.source_sha,
                source_ref=args.source_ref,
                tag=args.tag,
                certificate_sha256=args.certificate_sha256.lower(),
            )
        else:
            release_json = json.loads(args.release_json.read_text(encoding="utf-8"))
            _require(isinstance(release_json, dict), "Release JSON root must be an object")
            verify_release_assets(load_manifest(args.manifest), release_json)
    except (CandidateValidationError, OSError, json.JSONDecodeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
