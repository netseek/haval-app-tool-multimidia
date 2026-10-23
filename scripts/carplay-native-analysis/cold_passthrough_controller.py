#!/usr/bin/env python3
"""Coordinate the first cold OEM CarPlay passthrough validation.

This controller installs the reviewed cold passthrough entries and then arms the
inert clean-map Binder control plane. The second stage does not advertise an
AltScreen, create a display or change a Surface until the Impulse app sends its
explicit test broadcast.

The live command is intentionally explicit because it terminates only the idle
OEM host process.  The patched service's normal ActivityManager binding starts a
fresh process; if it does not, the controller starts the declared service only
after confirming the old process is gone.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import secrets
import socket
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from make_cold_launcher import build_launcher


OEM_PROCESS = "com.ts.carplay"
OEM_SERVICE = "com.ts.carplay/.CarPlayService"
GATE_PROPERTY = "persist.haval.carplay.experimental_cold_hook"
STAGE_ROOT = "/data/local/tmp/impulse-carplay/passthrough-v1"
CONTROL_ROOT = "/data/local/tmp/impulse-carplay/control"
REMOTE_BRIDGE = f"{STAGE_ROOT}/libcarplay_video_bridge.so"
REMOTE_LAUNCHER = f"{STAGE_ROOT}/cold-launcher.js"
REMOTE_LOG = f"{STAGE_ROOT}/injector.log"
REMOTE_ENDPOINT_LAUNCHER = f"{STAGE_ROOT}/clean-map-endpoint.js"
REMOTE_ENDPOINT_LOG = f"{STAGE_ROOT}/clean-map-endpoint.log"
REMOTE_INJECTOR = "/data/local/tmp/fridainjector"
REMOTE_SERVICE_PATCH = "/data/local/tmp/carplay_patches/TsCarPlayService.apk"
MOUNTED_SERVICE = "/vendor/app/TsCarPlayService/TsCarPlayService.apk"
EXPECTED_GATE_MD5 = "b61f0e4d63535a2ef447be559211697b"
PRINCIPAL_UUID = "e5f7a68d-7b0f-4305-984b-974f677a150b"
PID_PLACEHOLDER = 1_234_567_890
# This OEM console canonicalizes typed ASCII to lowercase, including marker
# literals embedded in commands. Keep the wire marker lowercase so completion
# detection observes the bytes that are actually printed.
MARKER_PREFIX = "__impulse_rc_"


class ControllerError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def launcher_for_pid(interceptor: str, profile: object, pid: int,
                     bridge_size: int, bridge_sha256: str) -> str:
    return build_launcher(
        interceptor,
        profile,
        pid,
        REMOTE_BRIDGE,
        bridge_size,
        bridge_sha256,
        PRINCIPAL_UUID,
    )


def launcher_template(interceptor: str, profile: object,
                      bridge_size: int, bridge_sha256: str) -> str:
    return launcher_for_pid(
        interceptor,
        profile,
        PID_PLACEHOLDER,
        bridge_size,
        bridge_sha256,
    )


def specialize_launcher(template: str, pid: int) -> str:
    if pid <= 0 or pid == PID_PLACEHOLDER:
        raise ValueError("fresh positive OEM PID required")
    needle = f'"expectedPid":{PID_PLACEHOLDER}'
    if template.count(needle) != 1:
        raise ValueError("launcher PID placeholder is missing or ambiguous")
    return template.replace(needle, f'"expectedPid":{pid}')


def classify_injector_log(log: str) -> str:
    phases = re.findall(
        r'"phase"\s*:\s*"(disabled|armed|installing|installed-pinned|failed-pinned|failed|cancelled)"',
        log,
    )
    return phases[-1] if phases else "unknown"


def endpoint_launcher(source: str) -> str:
    if "Persistent OEM-process control plane" not in source or "rpc.exports" not in source:
        raise ValueError("unexpected clean-map endpoint source")
    return source.rstrip() + f"""

rpc.exports.arm({{ bridgePath: {json.dumps(REMOTE_BRIDGE)} }}).then(
    state => send({{ kind: 'impulse-carplay-clean-map-control', state }}),
    error => send({{ kind: 'impulse-carplay-clean-map-control', state: 'failed', error: String(error) }})
);
"""


def shell_quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def shell_octal_command(value: str) -> str:
    try:
        payload = value.encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError("remote command must be ASCII") from error
    return "".join(f"\\{byte:03o}" for byte in payload)


class TelnetShell:
    """Small root-shell transport for the head unit's existing Telnet console."""

    def __init__(self, host: str, port: int = 23, timeout: float = 10.0):
        self.host = host
        self.port = port
        self.timeout = timeout

    @staticmethod
    def _clean(data: bytes) -> str:
        # Commands are ASCII.  Drop common Telnet negotiation triplets and
        # terminal backspace redraw noise without trying to emulate a terminal.
        data = re.sub(rb"\xff[\xfb-\xfe].", b"", data)
        while b"\x08" in data:
            data = re.sub(rb"[^\r\n]\x08", b"", data)
            data = data.replace(b"\x08", b"")
        return data.decode("utf-8", errors="replace").replace("\r", "")

    def _exchange(self, payload: bytes, marker: bytes, timeout: float | None = None) -> bytes:
        deadline = time.monotonic() + (timeout or self.timeout)
        received = bytearray()
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as sock:
            sock.settimeout(0.25)
            # Let the console finish its greeting and negotiation before input.
            greeting_until = time.monotonic() + 0.35
            while time.monotonic() < greeting_until:
                try:
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    received.extend(chunk)
                except socket.timeout:
                    pass
            # The OEM console echoes bytes as they arrive.  If the marker is
            # sent in the same write as `stty -echo`, its echoed command line
            # can satisfy the completion search before the command executes.
            # Disable echo in a separate round trip, drain that response, and
            # only then send the marker-bearing payload.
            echo_prefix = b"stty -echo; "
            if payload.startswith(echo_prefix):
                sock.sendall(b"stty -echo\n")
                quiet_until = time.monotonic() + 0.25
                while time.monotonic() < quiet_until:
                    try:
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        received.extend(chunk)
                    except socket.timeout:
                        pass
                payload = payload[len(echo_prefix):]
            pending = memoryview(payload)
            while pending:
                if time.monotonic() >= deadline:
                    raise ControllerError("Telnet payload upload timed out")
                try:
                    sent = sock.send(pending[:16384])
                    if sent <= 0:
                        raise ControllerError("Telnet console closed during payload upload")
                    pending = pending[sent:]
                except socket.timeout:
                    continue
            marker_result = re.compile(re.escape(marker.decode()) + r"\d+")
            while not marker_result.search(self._clean(bytes(received))):
                if time.monotonic() >= deadline:
                    raise ControllerError("Telnet command timed out")
                try:
                    chunk = sock.recv(65536)
                    if not chunk:
                        raise ControllerError("Telnet console closed before command completion")
                    received.extend(chunk)
                except socket.timeout:
                    continue
        return bytes(received)

    def run(self, command: str, timeout: float | None = None, check: bool = True) -> str:
        marker_id = secrets.token_hex(8)
        marker = f"{MARKER_PREFIX}{marker_id}:".encode()
        wrapped = (
            "stty -echo; "
            + command
            + "; impulse_rc=$?; printf '\\n"
            + marker.decode()
            + "%s\\n' \"$impulse_rc\"; stty echo\n"
        ).encode()
        raw = self._exchange(wrapped, marker, timeout)
        cleaned = self._clean(raw)
        match = re.search(re.escape(marker.decode()) + r"(\d+)", cleaned)
        if not match:
            raise ControllerError("Telnet result marker was not returned")
        rc = int(match.group(1))
        before = cleaned[: match.start()]
        if check and rc != 0:
            raise ControllerError(f"Remote command failed ({rc}): {before[-1200:]}")
        return before

    def upload(self, local_bytes: bytes, remote_path: str, mode: str = "600") -> None:
        compressed = gzip.compress(local_bytes, compresslevel=9, mtime=0)
        encoded = compressed.hex()
        lines = "\n".join(encoded[index:index + 128] for index in range(0, len(encoded), 128))
        marker_id = secrets.token_hex(8)
        marker = f"{MARKER_PREFIX}{marker_id}:".encode()
        remote_gzip = remote_path + ".gz"
        remote_hex = remote_path + ".hex"
        script = (
            f"stty -echo; rm -f {shell_quote(remote_path)} {shell_quote(remote_gzip)} {shell_quote(remote_hex)}; "
            f"mkdir -p {shell_quote(os.path.dirname(remote_path))}; "
            f"toybox xxd -r -p > {shell_quote(remote_gzip)} <<'IMPULSE_HEX'\n"
            f"{lines}\nIMPULSE_HEX\n"
            f"gzip -dc {shell_quote(remote_gzip)} > {shell_quote(remote_path)}; impulse_rc=$?; "
            f"rm -f {shell_quote(remote_gzip)}; chmod {mode} {shell_quote(remote_path)}; stty echo; "
            f"printf '\\n{marker.decode()}%s\\n' \"$impulse_rc\"\n"
        ).encode()
        raw = self._exchange(script, marker, timeout=max(self.timeout, 180.0))
        cleaned = self._clean(raw)
        match = re.search(re.escape(marker.decode()) + r"(\d+)", cleaned)
        if not match or int(match.group(1)) != 0:
            raise ControllerError(f"Upload failed: {cleaned[-1200:]}")
        observed = self.run(f"sha256sum {shell_quote(remote_path)} | awk '{{print $1}}'").strip().splitlines()[-1]
        expected = sha256_bytes(local_bytes)
        if observed != expected:
            raise ControllerError(f"Uploaded file hash mismatch: expected {expected}, got {observed}")


@dataclass(frozen=True)
class Inputs:
    bridge: Path
    interceptor: Path
    profile: Path
    endpoint: Path

    def read(self) -> tuple[bytes, str, object, str]:
        bridge = self.bridge.read_bytes()
        interceptor = self.interceptor.read_text(encoding="utf-8")
        profile = json.loads(self.profile.read_text(encoding="utf-8"))
        return bridge, interceptor, profile, self.endpoint.read_text(encoding="utf-8")


class ColdPassthroughController:
    def __init__(self, remote: TelnetShell, inputs: Inputs):
        self.remote = remote
        self.inputs = inputs
        self.nonce: str | None = None
        self.pid: int | None = None
        self.oem_uid: int | None = None

    def preflight(self) -> dict[str, str]:
        output = self.remote.run(
            "printf 'mounted='; find /vendor/app -iname tscarplayservice.apk -exec md5sum {} \\; "
            "| head -n 1 | cut -d' ' -f1; "
            "printf 'staged='; find /data/local/tmp/carplay_patches -iname tscarplayservice.apk "
            "-exec md5sum {} \\; | head -n 1 | cut -d' ' -f1; "
            "printf 'injector='; test -x " + shell_quote(REMOTE_INJECTOR) + " && echo yes || echo no; "
            "printf 'carplay_tasks='; am stack list 2>/dev/null | grep -c 'com.ts.carplay.app' || true; "
            "printf 'aa_tasks='; am stack list 2>/dev/null "
            "| grep -e 'com.google.android.projection.gearhead' -e 'com.ts.androidauto' -c || true; "
            "printf 'oem_uid='; dumpsys package com.ts.carplay 2>/dev/null "
            "| grep -im1 userid | cut -d= -f2; "
            "printf 'pid='; pidof " + OEM_PROCESS + " || true"
        )
        values = dict(re.findall(r"(?m)^(mounted|staged|injector|carplay_tasks|aa_tasks|oem_uid|pid)=([^\n]*)", output))
        if values.get("mounted") != EXPECTED_GATE_MD5 or values.get("staged") != EXPECTED_GATE_MD5:
            raise ControllerError("The exact reviewed cold-gate service patch is not both staged and mounted")
        if values.get("injector") != "yes":
            raise ControllerError("The vetted Frida injector is unavailable")
        if values.get("carplay_tasks") != "0" or values.get("aa_tasks") != "0":
            raise ControllerError("A CarPlay or Android Auto visual task is active")
        if not re.fullmatch(r"[1-9][0-9]*", values.get("oem_uid", "")):
            raise ControllerError("Unable to resolve the OEM CarPlay UID")
        self.oem_uid = int(values["oem_uid"])
        return values

    def stage(self) -> dict[str, str | int]:
        bridge, interceptor, profile, endpoint = self.inputs.read()
        bridge_sha = sha256_bytes(bridge)
        template = launcher_template(interceptor, profile, len(bridge), bridge_sha).encode()
        endpoint_script = endpoint_launcher(endpoint).encode()
        self.remote.run(
            f"mkdir -p {shell_quote(STAGE_ROOT)} {shell_quote(CONTROL_ROOT)}; "
            f"chmod 700 {shell_quote(STAGE_ROOT)} {shell_quote(CONTROL_ROOT)}; "
            f"rm -f {shell_quote(REMOTE_LOG)} {shell_quote(REMOTE_ENDPOINT_LOG)} "
            f"{shell_quote(CONTROL_ROOT)}/release.*"
        )
        print("[cold] uploading bridge", file=sys.stderr, flush=True)
        self.remote.upload(bridge, REMOTE_BRIDGE, "500")
        print("[cold] uploading launcher", file=sys.stderr, flush=True)
        self.remote.upload(template, REMOTE_LAUNCHER, "600")
        print("[cold] uploading endpoint", file=sys.stderr, flush=True)
        self.remote.upload(endpoint_script, REMOTE_ENDPOINT_LAUNCHER, "600")
        if self.oem_uid is None:
            raise ControllerError("OEM CarPlay UID was not established by preflight")
        self.remote.run(
            f"chown {self.oem_uid}:{self.oem_uid} {shell_quote(STAGE_ROOT)} {shell_quote(CONTROL_ROOT)} "
            f"{shell_quote(REMOTE_BRIDGE)} {shell_quote(REMOTE_LAUNCHER)} {shell_quote(REMOTE_ENDPOINT_LAUNCHER)}; "
            f"chmod 700 {shell_quote(STAGE_ROOT)} {shell_quote(CONTROL_ROOT)}; "
            f"chmod 500 {shell_quote(REMOTE_BRIDGE)}; "
            f"chmod 600 {shell_quote(REMOTE_LAUNCHER)} {shell_quote(REMOTE_ENDPOINT_LAUNCHER)}"
        )
        print("[cold] staging verified", file=sys.stderr, flush=True)
        return {
            "bridgeSize": len(bridge),
            "bridgeSha256": bridge_sha,
            "launcherTemplateSha256": sha256_bytes(template),
            "endpointLauncherSha256": sha256_bytes(endpoint_script),
        }

    def _wait_new_pid(self, old_pid: str, deadline: float) -> int:
        old = " ".join(re.findall(r"\d+", old_pid))
        timeout = max(1.0, deadline - time.monotonic())
        result = self.remote.run(
            f"old={shell_quote(old)}; sleep 0.3; fresh=\"$(pidof {OEM_PROCESS})\"; "
            "if [ -z \"$fresh\" ] || [ \"$fresh\" = \"$old\" ]; then "
            "component=$(printf 'com.ts.carplay/.\\103ar\\120lay\\123ervice'); "
            "am startservice -n \"$component\" >/dev/null 2>&1 & fi; "
            "attempt=0; while [ $attempt -lt 40 ]; do "
            f"fresh=\"$(pidof {OEM_PROCESS})\"; "
            "if [ -n \"$fresh\" ] && [ \"$fresh\" != \"$old\" ]; then break; fi; "
            "attempt=$((attempt+1)); sleep 0.1; done; "
            "printf 'fresh_pid=%s\\n' \"$fresh\"",
            timeout=timeout,
            check=False,
        )
        matches = re.findall(r"(?m)^fresh_pid=([0-9 ]*)$", result)
        for value in re.findall(r"\d+", matches[-1] if matches else ""):
            if value not in set(re.findall(r"\d+", old)):
                return int(value)
        raise ControllerError("Fresh OEM CarPlay host did not appear inside the cold-gate window")

    def _wait_phase(self, expected: set[str], deadline: float) -> tuple[str, str]:
        last_log = ""
        while time.monotonic() < deadline:
            last_log = self.remote.run(f"tail -n 120 {shell_quote(REMOTE_LOG)} 2>/dev/null || true", check=False)
            phase = classify_injector_log(last_log)
            if phase in expected:
                return phase, last_log
            time.sleep(0.1)
        return classify_injector_log(last_log), last_log

    def run(self) -> dict[str, str | int]:
        print("[cold] preflight", file=sys.stderr, flush=True)
        before = self.preflight()
        print("[cold] staging", file=sys.stderr, flush=True)
        staged = self.stage()
        self.nonce = secrets.token_hex(16)
        release_file = ""
        try:
            self.remote.run(
                f"rm -f {shell_quote(CONTROL_ROOT)}/release.*; "
                f"setprop {GATE_PROPERTY} {self.nonce}; "
                f"test \"$(getprop {GATE_PROPERTY})\" = {self.nonce}"
            )
            print("[cold] gate armed", file=sys.stderr, flush=True)
            old_pid = before.get("pid", "")
            for value in re.findall(r"\d+", old_pid):
                self.remote.run(f"kill -15 {value} 2>/dev/null || true", check=False)
            print("[cold] old host terminated", file=sys.stderr, flush=True)
            deadline = time.monotonic() + 8.0
            self.pid = self._wait_new_pid(old_pid, deadline)
            print(f"[cold] fresh host pid={self.pid}", file=sys.stderr, flush=True)
            maps_status = self.remote.run(
                f"printf 'maps_loaded='; if grep -qi -e libairplay_ts -e libcarplaystack "
                f"-e libcoreutils_ts /proc/{self.pid}/maps; then echo yes; else echo no; fi",
                check=False,
            )
            loaded = re.findall(r"(?m)^maps_loaded=(yes|no)$", maps_status)
            if not loaded or loaded[-1] != "no":
                raise ControllerError("OEM libraries loaded before injector arming; refusing warm interception")
            print("[cold] cold maps verified", file=sys.stderr, flush=True)

            bridge, interceptor, profile, _ = self.inputs.read()
            exact = launcher_for_pid(interceptor, profile, self.pid, len(bridge), sha256_bytes(bridge)).encode()
            self.remote.run(
                "sed -i "
                + shell_quote(f's/{PID_PLACEHOLDER}/{self.pid}/')
                + " " + shell_quote(REMOTE_LAUNCHER)
            )
            observed = self.remote.run(
                f"sha256sum {shell_quote(REMOTE_LAUNCHER)} | awk '{{print $1}}'"
            ).strip().splitlines()[-1]
            if observed != sha256_bytes(exact):
                raise ControllerError("PID-specialized launcher hash mismatch")
            print("[cold] launcher specialized", file=sys.stderr, flush=True)

            persistent_interceptor = (
                f"{REMOTE_INJECTOR} -p {self.pid} -s {REMOTE_LAUNCHER} -R qjs"
            )
            command = (
                f"nohup setsid sh -c \"$(printf {shell_quote(shell_octal_command(persistent_interceptor))})\" "
                f"> {REMOTE_LOG} 2>&1 < /dev/null & :"
            )
            self.remote.run(command)
            print("[cold] interceptor launched", file=sys.stderr, flush=True)
            phase, log = self._wait_phase({"armed", "failed", "failed-pinned"}, deadline)
            if phase != "armed":
                raise ControllerError(f"Cold interceptor did not arm (phase={phase}): {log[-1200:]}")

            release_file = f"{CONTROL_ROOT}/release.{self.pid}.{self.nonce}"
            self.remote.run(
                f"umask 077; : > {shell_quote(release_file)}; "
                f"chown {self.oem_uid}:{self.oem_uid} {shell_quote(release_file)}; "
                f"chmod 400 {shell_quote(release_file)}"
            )
            print("[cold] startup released", file=sys.stderr, flush=True)
            phase, log = self._wait_phase({"installed-pinned", "failed", "failed-pinned"}, time.monotonic() + 8.0)
            if phase != "installed-pinned":
                raise ControllerError(f"Cold interceptor did not install passthrough (phase={phase}): {log[-1200:]}")
            persistent_endpoint = (
                f"{REMOTE_INJECTOR} -p {self.pid} -s {REMOTE_ENDPOINT_LAUNCHER} -R qjs"
            )
            self.remote.run(
                f"nohup setsid sh -c \"$(printf {shell_quote(shell_octal_command(persistent_endpoint))})\" "
                f"> {REMOTE_ENDPOINT_LOG} 2>&1 < /dev/null & :"
            )
            print("[cold] endpoint launched", file=sys.stderr, flush=True)
            endpoint_deadline = time.monotonic() + 8.0
            endpoint_log = ""
            while time.monotonic() < endpoint_deadline:
                endpoint_log = self.remote.run(
                    f"tail -n 80 {REMOTE_ENDPOINT_LOG} 2>/dev/null || true", check=False
                )
                if '"state":"armed"' in endpoint_log:
                    break
                if '"state":"failed"' in endpoint_log:
                    raise ControllerError(f"Clean-map endpoint failed to arm: {endpoint_log[-1200:]}")
                time.sleep(0.1)
            else:
                raise ControllerError(f"Clean-map endpoint did not arm: {endpoint_log[-1200:]}")
            return {
                **staged,
                "pid": self.pid,
                "phase": phase,
                "injectorLog": REMOTE_LOG,
                "endpointLog": REMOTE_ENDPOINT_LOG,
            }
        finally:
            # Clearing the property and releasing a still-waiting gate are safe
            # both after success and on a pre-publication failure.  Never kill a
            # failed-pinned process automatically: that state requires review.
            commands = [f"setprop {GATE_PROPERTY} ''"]
            if release_file:
                commands.append(f"rm -f {shell_quote(release_file)}")
            self.remote.run("; ".join(commands), check=False)


def default_inputs() -> Inputs:
    root = Path(__file__).resolve().parent
    return Inputs(
        bridge=Path("/root/projects/haval-impulse/artifacts/carplay-clean-map-81923701-debug/native/libcarplay_video_bridge.so"),
        interceptor=root / "cold_oem_interceptor.js",
        profile=root / "testdata/oem-cold-profile-v3.json",
        endpoint=root / "carplay_clean_map_endpoint.js",
    )


def parse_args() -> argparse.Namespace:
    defaults = default_inputs()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=23)
    parser.add_argument("--bridge", type=Path, default=defaults.bridge)
    parser.add_argument("--interceptor", type=Path, default=defaults.interceptor)
    parser.add_argument("--profile", type=Path, default=defaults.profile)
    parser.add_argument("--endpoint", type=Path, default=defaults.endpoint)
    parser.add_argument("--stage-only", action="store_true", help="upload and verify, but do not restart the OEM host")
    parser.add_argument(
        "--execute-cold-restart",
        action="store_true",
        help="required acknowledgement for the live idle-host passthrough run",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.stage_only and not args.execute_cold_restart:
        print("Refusing live run without --execute-cold-restart", file=sys.stderr)
        return 64
    inputs = Inputs(
        bridge=args.bridge.resolve(),
        interceptor=args.interceptor.resolve(),
        profile=args.profile.resolve(),
        endpoint=args.endpoint.resolve(),
    )
    for path in (inputs.bridge, inputs.interceptor, inputs.profile, inputs.endpoint):
        if not path.is_file():
            print(f"Missing input: {path}", file=sys.stderr)
            return 66
    controller = ColdPassthroughController(TelnetShell(args.host, args.port), inputs)
    try:
        if args.stage_only:
            controller.preflight()
            result = controller.stage()
        else:
            result = controller.run()
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (ControllerError, OSError, ValueError) as error:
        print(f"Cold passthrough failed safely: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
