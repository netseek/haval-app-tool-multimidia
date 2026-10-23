#!/usr/bin/env python3
"""Build a one-shot, auto-arming Frida launcher from reviewed inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path


PROFILE_DIGEST = "31b878ba35c1a7fec07d7e854264837ef3702962a1e059fa5d020afa0d7c4d0f"
BRIDGE_PATH = re.compile(
    r"^/data/local/tmp/impulse-carplay/[A-Za-z0-9._/-]+/libcarplay_video_bridge\.so$"
)
PRINCIPAL_UUID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
SHA256 = re.compile(r"^[a-f0-9]{64}$")


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def validate_profile(profile: object) -> str:
    if not isinstance(profile, dict):
        raise ValueError("profile must be an object")
    encoded = canonical_json(profile)
    if len(encoded) > 16384 or hashlib.sha256(encoded.encode()).hexdigest() != PROFILE_DIGEST:
        raise ValueError("profile is not the reviewed v3 profile")
    return encoded


def build_launcher(
    interceptor_source: str,
    profile: object,
    expected_pid: int,
    bridge_path: str,
    bridge_size: int,
    bridge_sha256: str,
    principal_uuid: str,
) -> str:
    if expected_pid <= 0:
        raise ValueError("expected PID must be positive")
    if bridge_size < 64 or bridge_size > 67_108_864:
        raise ValueError("bridge size is outside reviewed bounds")
    if not BRIDGE_PATH.fullmatch(bridge_path) or ".." in bridge_path.split("/"):
        raise ValueError("bridge path is outside the private staging root")
    if not SHA256.fullmatch(bridge_sha256):
        raise ValueError("invalid bridge SHA-256")
    if not PRINCIPAL_UUID.fullmatch(principal_uuid):
        raise ValueError("invalid principal UUID")
    if "function createColdOemInterceptor(api)" not in interceptor_source:
        raise ValueError("unexpected interceptor source")

    options = {
        "enabled": True,
        "principalUuid": principal_uuid,
        "bridgePath": bridge_path,
        "bridgeSize": bridge_size,
        "bridgeSha256": bridge_sha256,
        "expectedPid": expected_pid,
    }
    auto_arm = f"""

(() => {{
    const impulseProfile = {validate_profile(profile)};
    const impulseOptions = {canonical_json(options)};
    const impulseInstaller = rpc.exports;
    let previous = null;
    const publish = status => {{
        const encoded = JSON.stringify(status);
        if (encoded !== previous) {{
            previous = encoded;
            send({{ kind: 'impulse-carplay-cold-state', status }});
        }}
        return status;
    }};
    const initial = publish(impulseInstaller.arm(impulseProfile, impulseOptions));
    if (initial.phase !== 'armed') return;
    const poll = setInterval(() => {{
        const status = publish(impulseInstaller.status());
        if (status.phase !== 'armed' && status.phase !== 'installing') clearInterval(poll);
    }}, 50);
}})();
"""
    return interceptor_source.rstrip() + auto_arm


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--interceptor", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--bridge-path", required=True)
    parser.add_argument("--bridge-size", type=int, required=True)
    parser.add_argument("--bridge-sha256", required=True)
    parser.add_argument("--principal-uuid", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    launcher = build_launcher(
        args.interceptor.read_text(encoding="utf-8"),
        json.loads(args.profile.read_text(encoding="utf-8")),
        args.expected_pid,
        args.bridge_path,
        args.bridge_size,
        args.bridge_sha256,
        args.principal_uuid,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(launcher)
    os.chmod(args.output, 0o600)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
