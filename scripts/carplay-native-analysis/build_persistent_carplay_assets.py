#!/usr/bin/env python3
"""Package local bootstrap assets; consume a Mini-built ELF, never compile Android."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import tempfile

from build_endpoint_agent import build as build_endpoint
from cold_passthrough_controller import (
    ENDPOINT_STATUS_PROCESS, PID_PLACEHOLDER, PRINCIPAL_UUID, RUNTIME_ROOT,
    read_endpoint_bundle, sha256_bytes,
)
from make_cold_launcher import build_launcher
from patch_bootstrap_injector import build as build_bootstrap_injector

ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = ROOT.parents[1]
INJECTOR = PROJECT_ROOT / "app/src/main/res/raw/fridainject"
RUNTIME_PLACEHOLDER = "BOOT_PROCESS_PLACEHOLDER"
ELIGIBILITY_PATH = "/data/local/tmp/impulse-carplay-input/safety.json"
SOURCE_FILES = (
    "build_persistent_carplay_assets.py",
    "build_endpoint_agent.py",
    "patch_bootstrap_injector.py",
    "testdata/frida-injector-17.5.2-permissive.json",
    "cold_passthrough_controller.py",
    "make_cold_launcher.py",
    "impulse_residency_peer.js",
    "cold_oem_interceptor.js",
    "carplay_clean_map_endpoint.js",
    "testdata/oem-cold-profile-v3.json",
    "testdata/android9-linker-profile.json",
    "agent/package.json",
    "agent/package-lock.json",
)
BOOTSTRAP_SOURCE_FILES = (
    "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBridgeBootstrapManager.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBridgeQuiescence.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBluetoothQuiescence.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayQuiescenceReading.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlayBootstrapRoot.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlayBridgeBootstrapPolicy.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlaySafetyPublicationGate.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlayResidentProof.kt",
    "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlayProjectionVisibilityProbe.kt",
)
ASSET_FILES = (
    "libcarplay_video_bridge.so",
    "cold-launcher-template.js",
    "endpoint.js",
    "endpoint.js.manifest.json",
    "cpbridge-inject",
)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_sha256(injector_sha256: str) -> str:
    # The failure circuit belongs to both the payload and its local controller.
    # A controller repair must not silently reuse the failed controller's revision.
    files = {name: file_sha256(ROOT / name) for name in SOURCE_FILES}
    files.update({name: file_sha256(PROJECT_ROOT / name) for name in BOOTSTRAP_SOURCE_FILES})
    files["app/src/main/res/raw/fridainject"] = injector_sha256
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return sha256_bytes(encoded)


def read_native_library(path: Path) -> bytes:
    payload = path.read_bytes()
    if (not 64 <= len(payload) <= 67_108_864 or payload[:6] != b"\x7fELF\x02\x01"
            or int.from_bytes(payload[16:18], "little") != 3
            or int.from_bytes(payload[18:20], "little") != 183):
        raise ValueError("A prebuilt ELF64 little-endian AArch64 shared library is required")
    return payload


def build(native_lib: Path, output: Path, version_code: int = 74136) -> dict:
    if isinstance(version_code, bool) or not isinstance(version_code, int) or not 0 < version_code <= 2_147_483_647:
        raise ValueError("Invalid Android version code")
    destination = output / "carplay_bridge"
    if destination.exists() or destination.is_symlink():
        raise ValueError("Use a fresh generated-assets destination; existing assets are never overwritten")
    native = read_native_library(native_lib)
    native_sha = sha256_bytes(native)
    original_injector_sha = file_sha256(INJECTOR)
    source_sha = source_sha256(original_injector_sha)
    revision = f"{version_code}-{native_sha[:12]}-{source_sha[:12]}"
    remote_directory = "/data/local/tmp/impulse-carplay/versions/" + revision
    bridge_path = remote_directory + "/libcarplay_video_bridge.so"
    runtime_template = RUNTIME_ROOT + "/" + RUNTIME_PLACEHOLDER
    launcher = build_launcher(
        (ROOT / "cold_oem_interceptor.js").read_text(encoding="utf-8"),
        json.loads((ROOT / "testdata/oem-cold-profile-v3.json").read_text()),
        PID_PLACEHOLDER, bridge_path, len(native), native_sha, PRINCIPAL_UUID,
        status_path=runtime_template + "/cold-state.json", cold_load_timeout_ms=300_000,
        eligibility_path=ELIGIBILITY_PATH,
    )
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".carplay-assets-", dir=output) as temporary:
        stage = Path(temporary)
        (stage / ASSET_FILES[0]).write_bytes(native)
        (stage / ASSET_FILES[1]).write_text(launcher, encoding="utf-8")
        injector_metadata = build_bootstrap_injector(INJECTOR, stage / "cpbridge-inject")
        build_endpoint(stage / "endpoint.js", bridge_path=bridge_path, status_mode=ENDPOINT_STATUS_PROCESS)
        read_endpoint_bundle(stage / "endpoint.js", bridge_path=bridge_path, status_mode=ENDPOINT_STATUS_PROCESS)
        manifest = {
            "schema": 1,
            "residencyProtocol": 1,
            "injectorMode": "eternalize",
            "versionCode": version_code,
            "revision": revision,
            "remoteDirectory": remote_directory,
            "runtimeDirectoryTemplate": runtime_template,
            "eligibilityPath": ELIGIBILITY_PATH,
            "sourceSha256": source_sha,
            **injector_metadata,
            "files": [
                {"name": name, "sha256": file_sha256(stage / name), "size": (stage / name).stat().st_size}
                for name in ASSET_FILES
            ],
        }
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        for file in stage.iterdir():
            file.chmod(0o644)
        # Only a complete, verified bundle becomes the Gradle asset directory.
        stage.rename(destination)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-lib", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version-code", type=int, default=74136)
    args = parser.parse_args()
    print(json.dumps(build(args.native_lib.resolve(), args.output.resolve(), args.version_code), indent=2))


if __name__ == "__main__":
    main()
