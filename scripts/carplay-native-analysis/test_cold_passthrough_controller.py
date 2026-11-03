import contextlib
import io
import json
import re
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

from cold_passthrough_controller import (
    PID_PLACEHOLDER,
    ColdPassthroughController,
    ControllerError,
    ProcessIdentity,
    GATE_PROPERTY,
    REMOTE_ENDPOINT_STATUS,
    launcher_for_pid,
    process_identity_from_stat,
    classify_injector_log,
    endpoint_launcher,
    launcher_template,
    shell_octal_command,
    sha256_bytes,
    specialize_launcher,
)


ROOT = Path(__file__).resolve().parent


def proc_stat(pid=42, start=123, state="S", comm="com.ts.carplay"):
    return f"{pid} ({comm}) " + " ".join([state] + ["0"] * 18 + [str(start)] + ["0"] * 3)


class SnapshotRemote:
    def __init__(self, snapshots, launcher_sha="unused"):
        self.snapshots = list(snapshots)
        self.commands = []
        self.launcher_sha = launcher_sha

    def run(self, command, **kwargs):
        self.commands.append(command)
        if "identity_pids_before=" in command:
            snapshot = self.snapshots.pop(0)
            marker = re.search(r"identity_status_[a-f0-9]+", command).group()
            start = snapshot.get("start", 123)
            default_status = "maps_loaded=no" if "maps_loaded=" in command else (
                self.launcher_sha if "sha256sum" in command else "")
            return "\n".join([
                "root@headunit:/ #",
                "identity_pids_before=" + snapshot.get("pids_before", "42"),
                "identity_stat_before=" + snapshot.get("stat_before", proc_stat(start=start)),
                "identity_cmdline=" + snapshot.get("cmdline", "com.ts.carplay"),
                marker + "_begin",
                snapshot.get("status", default_status),
                marker + "_end",
                "identity_stat_after=" + snapshot.get("stat_after", proc_stat(start=snapshot.get("after_start", start))),
                "identity_pids_after=" + snapshot.get("pids_after", "42"),
                "",
            ])
        if "maps_loaded=" in command:
            return "maps_loaded=no\n"
        if "sha256sum" in command:
            return self.launcher_sha + "\n"
        return ""


class FakeInputs:
    def __init__(self, interceptor, profile):
        self.interceptor, self.profile = interceptor, profile

    def read(self):
        return b"bridge" * 1024, self.interceptor, self.profile, ""


class RunController(ColdPassthroughController):
    def preflight(self):
        self.oem_uid = 1000
        return {"pid": "17"}

    def stage(self):
        return {"bridgeSha256": "test"}

    def _wait_new_pid(self, old_pid, deadline):
        return 42


class ColdPassthroughControllerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.interceptor = (ROOT / "cold_oem_interceptor.js").read_text(encoding="utf-8")
        cls.profile = json.loads((ROOT / "testdata/oem-cold-profile-v3.json").read_text(encoding="utf-8"))

    def test_pid_specialization_matches_exact_launcher_shape(self):
        template = launcher_template(self.interceptor, self.profile, 4096, "a" * 64)
        exact = specialize_launcher(template, 4242)
        self.assertIn('"expectedPid":4242', exact)
        self.assertNotIn(str(PID_PLACEHOLDER), exact)
        self.assertEqual(1, exact.count('"expectedPid":4242'))

    def test_specialization_rejects_missing_or_ambiguous_placeholder(self):
        with self.assertRaises(ValueError):
            specialize_launcher("", 42)
        with self.assertRaises(ValueError):
            specialize_launcher(f'"expectedPid":{PID_PLACEHOLDER}' * 2, 42)
        with self.assertRaises(ValueError):
            specialize_launcher(f'"expectedPid":{PID_PLACEHOLDER}', 0)

    def test_log_phase_uses_last_state(self):
        log = '\n'.join([
            '{"payload":{"status":{"phase":"armed"}}}',
            '{"payload":{"status":{"phase":"installing"}}}',
            '{"payload":{"status":{"phase":"installed-pinned"}}}',
        ])
        self.assertEqual("installed-pinned", classify_injector_log(log))
        self.assertEqual("unknown", classify_injector_log("plain output"))

    def test_hash_is_stable(self):
        self.assertEqual(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256_bytes(b"abc"),
        )

    def test_octal_command_preserves_uppercase_runtime_option(self):
        encoded = shell_octal_command("fridainjector -R qjs")
        self.assertEqual("fridainjector -R qjs", bytes(
            int(value, 8) for value in encoded.split("\\") if value
        ).decode("ascii"))
        self.assertNotIn("R", encoded)

    def test_endpoint_launcher_arms_exact_staged_bridge(self):
        source = (ROOT / "carplay_clean_map_endpoint.js").read_text(encoding="utf-8")
        launcher = endpoint_launcher(source)
        self.assertIn("/data/local/tmp/impulse-carplay/passthrough-v1/libcarplay_video_bridge.so", launcher)
        self.assertEqual(1, launcher.count("impulseEndpointExports.arm({ bridgePath:"))

    def test_endpoint_launcher_rejects_unknown_source(self):
        with self.assertRaises(ValueError):
            endpoint_launcher("rpc.exports = {}")


    def controller(self, snapshots):
        remote = SnapshotRemote(snapshots)
        controller = ColdPassthroughController(remote, None)
        controller.pid = 42
        controller.process_identity = ProcessIdentity(42, 123)
        return controller, remote

    def test_snapshot_shell_reads_stat_and_nul_terminated_cmdline(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / "stat").write_text(proc_stat() + "\n")
            (proc / "cmdline").write_bytes(b"com.ts.carplay\0extra\0")

            class LocalFixtureRemote:
                def run(self, command, **kwargs):
                    command = command.replace("pidof com.ts.carplay", "printf '42\\n'")
                    command = command.replace("/proc/42/", str(proc) + "/")
                    result = subprocess.run(["/bin/sh", "-c", command], check=True,
                                            capture_output=True, text=True)
                    return result.stdout

            controller = ColdPassthroughController(LocalFixtureRemote(), None)
            controller.pid = 42
            controller.process_identity = ProcessIdentity(42, 123)
            status = controller._live_status("printf '%s' '{\"phase\":\"armed\"}'")
            self.assertEqual('{"phase":"armed"}', status)

    def test_process_stat_handles_spaces_and_parentheses_in_comm(self):
        self.assertEqual(ProcessIdentity(42, 123), process_identity_from_stat(
            proc_stat(comm="worker (carplay) )"), 42))

    def test_process_stat_refuses_missing_malformed_dead_and_wrong_pid(self):
        for stat in ["", "broken", proc_stat(pid=99), proc_stat(start=0),
                     proc_stat(state="Z"), proc_stat(state="X")]:
            with self.subTest(stat=stat), self.assertRaises(ControllerError):
                process_identity_from_stat(stat, 42)

    def test_live_exact_process_accepts_armed_and_installed_state(self):
        for phase in ["armed", "installed-pinned", "failed-pinned"]:
            with self.subTest(phase=phase):
                status = json.dumps({"phase": phase})
                controller, remote = self.controller([{"status": status}])
                self.assertEqual((phase, status), controller._wait_phase({phase}, time.monotonic() + 1))
                self.assertEqual(1, len(remote.commands))

    def test_dead_process_cannot_acknowledge_stale_armed(self):
        controller, remote = self.controller([{"status": '{"phase":"armed"}', "pids_before": "", "pids_after": ""}])
        with self.assertRaisesRegex(ControllerError, "exited or was replaced"):
            controller._wait_phase({"armed"}, time.monotonic() + 300)
        self.assertEqual(1, len(remote.commands))

    def test_replacement_process_cannot_acknowledge_stale_installed_state(self):
        controller, remote = self.controller([{"status": '{"phase":"installed-pinned"}', "pids_before": "99", "pids_after": "99"}])
        with self.assertRaisesRegex(ControllerError, "exited or was replaced"):
            controller._wait_phase({"installed-pinned"}, time.monotonic() + 300)
        self.assertEqual(1, len(remote.commands))

    def test_reused_pid_cannot_acknowledge_stale_installed_state(self):
        controller, remote = self.controller([{"status": '{"phase":"installed-pinned"}', "start": 999}])
        with self.assertRaisesRegex(ControllerError, "PID was reused"):
            controller._wait_phase({"installed-pinned"}, time.monotonic() + 300)
        self.assertEqual(1, len(remote.commands))

    def test_process_reuse_or_exit_during_status_read_is_rejected(self):
        for change in [{"after_start": 999}, {"pids_after": ""}]:
            with self.subTest(change=change):
                controller, remote = self.controller([{"status": '{"phase":"armed"}', **change}])
                with self.assertRaises(ControllerError):
                    controller._wait_phase({"armed"}, time.monotonic() + 300)
                self.assertEqual(1, len(remote.commands))

    def test_wrong_executable_or_zombie_process_is_rejected(self):
        for change in [{"cmdline": "unrelated"}, {"stat_after": proc_stat(state="Z")}]:
            with self.subTest(change=change):
                controller, _ = self.controller([{"status": '{"phase":"armed"}', **change}])
                with self.assertRaises(ControllerError):
                    controller._wait_phase({"armed"}, time.monotonic() + 300)

    def test_endpoint_accepts_only_live_exact_process(self):
        status = '{"state":"armed"}'
        controller, remote = self.controller([{"status": status}])
        self.assertEqual(status, controller._wait_endpoint(time.monotonic() + 1))
        self.assertIn(REMOTE_ENDPOINT_STATUS, remote.commands[0])

    def test_endpoint_stale_armed_is_rejected_for_dead_replaced_or_reused_pid(self):
        for change in [{"pids_after": ""}, {"pids_before": "99"}, {"start": 999}]:
            with self.subTest(change=change):
                controller, remote = self.controller([{"status": '{"state":"armed"}', **change}])
                with self.assertRaises(ControllerError):
                    controller._wait_endpoint(time.monotonic() + 300)
                self.assertEqual(1, len(remote.commands))

    def test_endpoint_failure_after_armed_cannot_be_hidden_by_old_log_line(self):
        controller, _ = self.controller([{"status": '{"state":"armed"}\n{"state":"failed"}'}])
        with self.assertRaisesRegex(ControllerError, "failed to arm"):
            controller._wait_endpoint(time.monotonic() + 300)

    def run_controller(self, snapshots):
        inputs = FakeInputs(self.interceptor, self.profile)
        exact = launcher_for_pid(self.interceptor, self.profile, 42, 6144, sha256_bytes(b"bridge" * 1024)).encode()
        remote = SnapshotRemote(snapshots, sha256_bytes(exact))
        return RunController(remote, inputs), remote

    def test_run_clears_gate_without_killing_replacement_when_arm_process_dies(self):
        controller, remote = self.run_controller([{}, {}, {"status": '{"phase":"armed"}', "pids_after": "99"}])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(ControllerError):
            controller.run()
        self.assertIn(f"setprop {GATE_PROPERTY} ''", remote.commands[-1])
        kills = [command for command in remote.commands if command.startswith("kill ")]
        self.assertEqual(["kill -15 17 2>/dev/null || true"], kills)
        self.assertFalse(any(REMOTE_ENDPOINT_STATUS in command for command in remote.commands))

    def test_run_rejects_endpoint_process_loss_and_cleans_release_file(self):
        controller, remote = self.run_controller([
            {}, {}, {"status": '{"phase":"armed"}'},
            {"status": '{"phase":"installed-pinned"}'}, {},
            {"status": '{"state":"armed"}', "start": 999},
        ])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(ControllerError):
            controller.run()
        self.assertIn(f"setprop {GATE_PROPERTY} ''", remote.commands[-1])
        self.assertIn("rm -f", remote.commands[-1])
        self.assertEqual(1, sum(command.startswith("kill ") for command in remote.commands))

    def test_run_success_includes_bound_process_start_time(self):
        controller, remote = self.run_controller([
            {}, {}, {"status": '{"phase":"armed"}'},
            {"status": '{"phase":"installed-pinned"}'}, {}, {"status": '{"state":"armed"}'},
        ])
        with contextlib.redirect_stderr(io.StringIO()):
            result = controller.run()
        self.assertEqual(42, result["pid"])
        self.assertEqual(123, result["processStartTimeTicks"])
        self.assertEqual("installed-pinned", result["phase"])
        self.assertIn(f"setprop {GATE_PROPERTY} ''", remote.commands[-1])
        # The short cold window retains its original number of console round
        # trips: maps and launcher hashing carry the new identity checks.
        killed = next(index for index, command in enumerate(remote.commands)
                      if command.startswith("kill -15"))
        released = next(index for index, command in enumerate(remote.commands)
                        if command.startswith("umask 077"))
        self.assertEqual(5, released - killed - 1)


if __name__ == "__main__":
    unittest.main()
