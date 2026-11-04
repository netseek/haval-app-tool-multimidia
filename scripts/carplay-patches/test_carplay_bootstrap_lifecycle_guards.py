"""Guard legacy startup against dropping process-pinned CarPlay callbacks.

The probe cases execute the shell command extracted from the Kotlin source,
with only pidof and /proc paths redirected to local fixtures. No Android build
or device operation is performed.
"""

from pathlib import Path
import json
import errno
import os
import re
import shlex
import subprocess
import sys
import tempfile
import textwrap
import unittest
import hashlib


ROOT = Path(__file__).resolve().parents[2]
MANAGER = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayPatchManager.kt"
FRIDA = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/utils/FridaUtils.java"
BOOTSTRAP = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBridgeBootstrapManager.kt"
QUIESCENCE = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBridgeQuiescence.kt"
BLUETOOTH = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBluetoothQuiescence.kt"
INTERCEPTOR = ROOT / "scripts/carplay-native-analysis/cold_oem_interceptor.js"
PROFILE = ROOT / "scripts/carplay-native-analysis/testdata/oem-cold-profile-v3.json"
RESIDENT = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlayResidentProof.kt"


def function_source(source, name):
    selected = re.search(r"    private fun (?:<[^>]+>\s+)?" + re.escape(name) + r"\(", source)
    if not selected:
        raise AssertionError("Missing private function: " + name)
    start = selected.start()
    following = re.search(r"\n    (?:private |internal )?fun ", source[start + 1:])
    end = start + 1 + following.start() if following else len(source)
    return source[start:end]


class LegacyBootstrapLifecycleGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manager = MANAGER.read_text()
        cls.frida = FRIDA.read_text()
        cls.bootstrap = BOOTSTRAP.read_text()
        match = re.search(r'private val bridgeMappingProbe = """(.*?)"""\.trimIndent\(\)',
                          cls.manager, re.S)
        if not match:
            raise AssertionError("Live bridge mapping probe is missing")
        cls.probe = textwrap.dedent(match.group(1)).replace("${'$'}", "$").strip()

    def run_probe(self, pids, maps, pidof_status=0, grep_status=None):
        with tempfile.TemporaryDirectory(prefix="impulse-bridge-guard-") as directory:
            fixture = Path(directory)
            pidof = fixture / "fixture-pidof"
            pidof.write_text("#!/bin/sh\nprintf '%s\\n' " + shlex.quote(pids) +
                             "\nexit " + str(pidof_status) + "\n")
            pidof.chmod(0o700)
            for pid, contents in maps.items():
                target = fixture / "proc" / pid / "maps"
                target.parent.mkdir(parents=True)
                target.write_text(contents)
            script = self.probe.replace("pidof com.ts.carplay", shlex.quote(str(pidof)))
            script = script.replace("/proc/", str(fixture / "proc") + "/")
            if grep_status is not None:
                grep = fixture / "fixture-grep"
                grep.write_text("#!/bin/sh\nexit " + str(grep_status) + "\n")
                grep.chmod(0o700)
                script = script.replace("grep -Fq", shlex.quote(str(grep)) + " -Fq")
            result = subprocess.run(["sh", "-c", script], text=True, capture_output=True,
                                    timeout=5, check=True)
            return result.stdout.strip()

    def test_server_start_has_no_global_injector_termination(self):
        self.assertNotRegex(self.frida, r"\b(?:pkill|killall)\b")
        self.assertIn("if (serverExists && injectExists)", self.frida)
        self.assertIn("if (!injectExists)", self.frida)

    def test_bootstrap_uses_only_the_profiled_asset_and_attests_it_before_probe(self):
        extract = function_source(self.bootstrap, "extractPayload")
        self.assertNotIn("R.raw.fridainject", extract)
        for field in ("policyMode", "patchProfile", "injectorOriginalSha256", "injectorSha256", "injectorSize"):
            self.assertIn('"' + field + '"', extract)
        self.assertIn('"cpbridge-inject"', extract)
        self.assertIn("files.length() == expectedFiles.size", extract)
        self.assertIn('.toSet() == expectedFiles', extract)
        install = function_source(self.bootstrap, "installPayload")
        self.assertIn('chown 0:0 ${q("$temporary/cpbridge-inject")}', install)
        tick = function_source(self.bootstrap, "tick")
        self.assertLess(tick.index("installPayload(bundle)"), tick.index("ensureInjectorPreflight(bundle)"))
        preflight = function_source(self.bootstrap, "ensureInjectorPreflight")
        self.assertLess(preflight.index('mkdir ${q(directory)} || exit 1'), preflight.index(' --version'))
        existing = preflight.split('"existing" -> {', 1)[1].split('"admitted" -> {', 1)[0]
        self.assertNotIn(' --version', existing)
        self.assertNotIn('rm ', existing)
        self.assertIn('preflightReceipt(before, bundle.revision)', existing)
        self.assertIn('checkPreflight(before, after, version)', preflight)
        self.assertIn('mv ${q("$receipt.next")} ${q(receipt)}', preflight)

    def test_environment_guard_is_in_child_after_barrier_and_before_exec(self):
        proof = RESIDENT.read_text()
        child = proof.split('while [ ! -f ${quote(release)} ]; do', 1)[1].split('cpb_child=§!', 1)[0]
        self.assertLess(child.index('cat ${quote(release)}'), child.index('environmentGuard(binding.environment'))
        self.assertLess(child.index('environmentGuard(binding.environment'), child.index('deadlineGuard(binding.execDeadlineMs)'))
        self.assertLess(child.index('deadlineGuard(binding.execDeadlineMs)'), child.index('exec $args'))
        state_guard = proof.split('fun environmentStateGuard(', 1)[1].split('fun environmentGuard(', 1)[0]
        self.assertNotIn('sha256sum ${quote(injector)}', state_guard)
        self.assertNotRegex(proof, r'\bsetenforce\b|>\s*/sys/fs/selinux/load')

    def test_automatic_owner_is_checked_before_mapping_probe(self):
        body = function_source(self.manager, "deferPatchProcessReload")
        self.assertLess(body.index("CarPlayBridgeBootstrapManager.isAutomaticEnabled()"),
                        body.index("sh(bridgeMappingProbe)"))
        self.assertIn('"automatic_bootstrap_owner"', body)
        self.assertIn('else -> "bridge_state_unknown"', body)

    def parent_dac_commands(self, parents):
        install = function_source(self.bootstrap, "installPayload")
        commands = re.findall(
            r'"((?:mkdir -p|chown 0:0|chmod 711) \$traversalParents \|\| exit 1; )"', install)
        self.assertEqual(commands, ["mkdir -p $traversalParents || exit 1; ",
                                    "chown 0:0 $traversalParents || exit 1; ",
                                    "chmod 711 $traversalParents || exit 1; "])
        return "".join(commands).replace("$traversalParents", " ".join(shlex.quote(str(p)) for p in parents))

    def test_all_runtime_and_version_parents_are_root_owned_traversable_before_reuse(self):
        install = function_source(self.bootstrap, "installPayload")
        self.assertIn('val traversalParents = listOf(ROOT, "$ROOT/versions", "$ROOT/runtime")'
                      '.joinToString(" ") { q(it) }', install)
        for command in ("mkdir -p", "chown 0:0", "chmod 711"):
            self.assertLess(install.index(command + " $traversalParents || exit 1"),
                            install.index("if [ -d ${q(bundle.directory)} ]"))
        self.parent_dac_commands([Path("/fixture")])
        self.assertIn("chmod 500 ${q(temporary)}", install)
        self.assertIn("chmod 400 ${q(temporary)}/*", install)

    def owner_fixture(self, argv, final_nul):
        observe = function_source(self.bootstrap, "observe")
        loop = re.search(r"for c in §\(pidof cpbridge-inject\); do.*?\n\s*done", observe, re.S)
        self.assertIsNotNone(loop)
        script = loop.group(0)
        injector = "/data/local/tmp/impulse-carplay/versions/revision/cpbridge-inject"
        runtime = "/data/local/tmp/impulse-carplay/runtime/boot-22287-952661"
        native = f"{injector} -p 22287 -s {runtime}/cold-launcher.js -R qjs"
        java = f"{injector} -p 22287 -s /data/local/tmp/impulse-carplay/versions/revision/endpoint.js -R qjs"
        native_expression = '${q("${bundle.injector} -p ${id.pid} -s $run/cold-launcher.js -R qjs")}'
        java_expression = '${q("${bundle.injector} -p ${id.pid} -s ${bundle.directory}/endpoint.js -R qjs")}'
        self.assertIn(native_expression, script)
        self.assertIn(java_expression, script)
        script = script.replace(native_expression, shlex.quote(native)).replace(java_expression, shlex.quote(java))
        self.assertNotIn("${q(", script)
        script = script.replace("§", "$")
        with tempfile.TemporaryDirectory(prefix="impulse-owner-argv-") as directory:
            fixture = Path(directory)
            proc = fixture / "proc" / "9441"
            proc.mkdir(parents=True)
            payload = b"\0".join(value.encode() for value in argv)
            (proc / "cmdline").write_bytes(payload + (b"\0" if final_nul else b""))
            (proc / "stat").write_text("9441 (cpbridge-inject) S " + "0 " * 18 + "952700\n")
            pidof = fixture / "pidof-fixture"
            pidof.write_text("#!/bin/sh\nprintf '9441\\n'\n")
            pidof.chmod(0o700)
            script = script.replace("pidof cpbridge-inject", shlex.quote(str(pidof)))
            script = script.replace("/proc/", str(fixture / "proc") + "/")
            result = subprocess.run(["sh", "-c", script], capture_output=True, text=True,
                                    check=True, timeout=5)
            return {line.split("=", 1)[0]: line.split("=", 1)[1]
                    for line in result.stdout.splitlines() if "=" in line}

    def owner_argv(self, role):
        injector = "/data/local/tmp/impulse-carplay/versions/revision/cpbridge-inject"
        script = ("/data/local/tmp/impulse-carplay/runtime/boot-22287-952661/cold-launcher.js"
                  if role == "native_owner" else
                  "/data/local/tmp/impulse-carplay/versions/revision/endpoint.js")
        return [injector, "-p", "22287", "-s", script, "-R", "qjs"]

    def test_native_and_java_owners_accept_kernel_argv_with_or_without_final_nul(self):
        for role in ("native_owner", "java_owner"):
            for terminated in (False, True):
                with self.subTest(role=role, terminated=terminated):
                    actual = self.owner_fixture(self.owner_argv(role), terminated)
                    self.assertEqual(actual.get(role), "9441")
                    other = "java_owner" if role == "native_owner" else "native_owner"
                    self.assertNotIn(other, actual)

    def test_owner_argv_rejects_extra_arguments_and_reused_or_foreign_processes(self):
        for role in ("native_owner", "java_owner"):
            original = self.owner_argv(role)
            variants = {
                "extra_argument": original + ["--unexpected"],
                "wrong_target": original[:2] + ["99999"] + original[3:],
                "wrong_injector": ["/system/bin/toybox"] + original[1:],
                "wrong_revision": [original[0].replace("/revision/", "/other/")] + original[1:],
                "wrong_script": original[:4] + [original[4] + ".old"] + original[5:],
                "wrong_runtime": original[:-1] + ["v8"],
            }
            for label, argv in variants.items():
                for terminated in (False, True):
                    with self.subTest(role=role, variant=label, terminated=terminated):
                        self.assertEqual(self.owner_fixture(argv, terminated), {})

    @unittest.skipUnless(hasattr(os, "setuid") and os.geteuid() == 0,
                         "UID1000 DAC fixture requires Linux/POSIX root privileges")
    def test_system_uid_can_traverse_protected_parents_without_listing_or_mutating_them(self):
        def demote(uid):
            def apply():
                os.setgroups([])
                os.setgid(uid)
                os.setuid(uid)
            return apply

        probe = textwrap.dedent("""
            import errno, json, os, pathlib, sys
            bridge, state, parent = map(pathlib.Path, sys.argv[1:])
            result = {'uid': os.geteuid()}
            for name, operation in (
                ('readBridge', lambda: bridge.read_bytes()),
                ('writeState', lambda: state.write_text('fixture-state')),
                ('listParent', lambda: list(parent.iterdir())),
                ('writeParent', lambda: (parent / 'foreign').write_text('denied'))):
                try:
                    operation()
                    result[name] = 0
                except OSError as error:
                    result[name] = error.errno
            print(json.dumps(result))
        """)
        with tempfile.TemporaryDirectory(prefix="impulse-parent-dac-") as directory:
            fixture = Path(directory)
            fixture.chmod(0o711)
            root = fixture / "impulse-carplay"
            versions, runtime = root / "versions", root / "runtime"
            revision, epoch = versions / "revision", runtime / "epoch"
            previous_umask = os.umask(0o077)
            try:
                revision.mkdir(parents=True)
                epoch.mkdir(parents=True)
                bridge = revision / "libcarplay_video_bridge.so"
                bridge.write_bytes(b"fixture-ELF")
            finally:
                os.umask(previous_umask)
            for item in (revision, epoch, bridge):
                os.chown(item, 1000, 1000)
            revision.chmod(0o500)
            epoch.chmod(0o700)
            bridge.chmod(0o400)
            state = epoch / "cold-state.json"

            def run(uid=1000):
                result = subprocess.run(
                    [sys.executable, "-c", probe, str(bridge), str(state), str(versions)],
                    cwd=directory, preexec_fn=demote(uid), capture_output=True, text=True,
                    timeout=5, check=True)
                return json.loads(result.stdout)

            denied = run()
            self.assertEqual(denied['uid'], 1000)
            self.assertEqual((denied['readBridge'], denied['writeState']), (errno.EACCES, errno.EACCES))
            root.chmod(0o711)
            self.assertEqual(run()['readBridge'], errno.EACCES, "Every ancestor needs traversal")
            versions.chmod(0o711)
            partial = run()
            self.assertEqual(partial['readBridge'], 0)
            self.assertEqual(partial['writeState'], errno.EACCES, "Runtime parent also needs traversal")
            # Execute the actual three installer commands, with only paths redirected.
            subprocess.run(["sh", "-c", self.parent_dac_commands((root, versions, runtime))],
                           capture_output=True, text=True, timeout=5, check=True)
            permitted = run()
            self.assertEqual((permitted['readBridge'], permitted['writeState']), (0, 0))
            self.assertEqual((permitted['listParent'], permitted['writeParent']),
                             (errno.EACCES, errno.EACCES))
            for parent in (root, versions, runtime):
                self.assertEqual((parent.stat().st_uid, parent.stat().st_mode & 0o777), (0, 0o711))
            foreign = run(1001)
            self.assertEqual((foreign['readBridge'], foreign['writeState']), (errno.EACCES, errno.EACCES))

    def test_bridge_file_denial_precedes_module_load_and_every_callback_publication(self):
        source = INTERCEPTOR.read_text()
        arm = source.split("    function arm(input, options) {", 1)[1]
        preflight = arm.index("const bytes = fileBytes(options.bridgePath")
        loaded = arm.index("const bridge = Module.load(options.bridgePath)")
        self.assertLess(preflight, loaded)
        for publication in ("Script.pin()", "Interceptor.attach(", "Interceptor.replaceFast(",
                            "Process.attachModuleObserver("):
            self.assertLess(loaded, arm.index(publication))
        self.assertNotRegex(arm[:preflight], r"(?:Interceptor\.(?:attach|replaceFast)|Script\.pin)\s*\(")
        script = r"""
            const assert = require('assert');
            const crypto = require('crypto');
            const fs = require('fs');
            const create = require(process.argv[1]).createColdOemInterceptor;
            const profile = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
            const calls = { load: 0, attach: 0, replace: 0, observer: 0, pin: 0, native: 0, bridgeOpen: 0 };
            const bridgePath = '/data/local/tmp/impulse-carplay/versions/fixture/libcarplay_video_bridge.so';
            class MockFile {
                constructor(path) {
                    if (path === bridgePath) { calls.bridgeOpen++; throw Error('EACCES fixture bridge'); }
                    assert.strictEqual(path, '/proc/self/cmdline');
                }
                readBytes() { return Uint8Array.from(Buffer.from('com.ts.carplay\0')).buffer; }
                close() {}
            }
            const installer = create({
                Process: { arch: 'arm64', pointerSize: 8, id: 22287, enumerateModules: () => [],
                    attachModuleObserver() { calls.observer++; }, getCurrentThreadId() { return 1; } },
                File: MockFile, Module: { load() { calls.load++; } },
                Memory: { queryProtection() { return 'r-x'; } },
                Checksum: { compute(kind, value) { return crypto.createHash(kind).update(value).digest('hex'); } },
                NativeFunction: function() { calls.native++; },
                Interceptor: { attach() { calls.attach++; }, replaceFast() { calls.replace++; } },
                Script: { pin() { calls.pin++; }, unpin() {} }
            });
            assert.throws(() => installer.arm(profile, { enabled: true, expectedPid: 22287,
                principalUuid: 'fixture', bridgePath, bridgeSize: 64, bridgeSha256: '0'.repeat(64) }), /EACCES/);
            assert.strictEqual(calls.bridgeOpen, 1);
            for (const key of ['load', 'attach', 'replace', 'observer', 'pin', 'native']) assert.strictEqual(calls[key], 0);
            assert.deepStrictEqual(installer.status(), { phase: 'idle', replacements: 0, pinned: false, failure: null });
        """
        subprocess.run(["node", "-e", script, str(INTERCEPTOR), str(PROFILE)],
                       text=True, capture_output=True, check=True, timeout=10)

    def test_reload_defers_before_visual_or_host_actions(self):
        body = function_source(self.manager, "reloadCarPlayProcessesAfterPatchMount")
        self.assertLess(body.index("if (deferPatchProcessReload(reason)) return"),
                        body.index("DisplayAppLauncher.isCarPlayOnDisplay"))

    def test_signals_and_start_fallback_recheck_ownership(self):
        body = function_source(self.manager, "reloadBoundProcessWithoutForceStop")
        for signal in ("TERM", "KILL"):
            with self.subTest(signal=signal):
                self.assertRegex(body, r"if \(deferPatchProcessReload\(reason\)\) return false\s+"
                                 r'sh\("kill -' + signal)
        self.assertRegex(body, r"if \(replacementPids.isEmpty\(\)\) \{\s+"
                         r"if \(deferPatchProcessReload\(reason\)\) return false")

    def test_native_client_refreshes_recheck_ownership(self):
        for name in ("refreshOemCarPlayClientsAfterHostReload", "refreshSystemUiCarPlayView",
                     "refreshAppListCarPlayManager"):
            with self.subTest(name=name):
                body = function_source(self.manager, name)
                self.assertRegex(body, r"\{\s+if \(!clientRefreshAllowed\(reason, verifier\)\) return")

    def test_client_verifier_cannot_bypass_host_reload_guard(self):
        body = function_source(self.manager, "clientRefreshAllowed")
        self.assertIn("if (verifier == null) !deferPatchProcessReload(reason)", body)
        reload = function_source(self.manager, "reloadBoundProcessWithoutForceStop")
        self.assertNotIn("verifier", reload)
        refresh = function_source(self.manager, "refreshSystemUiCarPlayView")
        restore = refresh.split("} finally {", 1)[1].split("val overlayRestored", 1)[0]
        self.assertIn("cmd overlay $restoreAction", restore)
        self.assertNotIn("clientRefreshAllowed", restore)

    def test_client_signals_recheck_verifier_immediately(self):
        body = function_source(self.manager, "refreshAppListCarPlayManager")
        guarded_commands = re.findall(
            r'if \(!clientRefreshAllowed\(reason, verifier\)\) return\s+'
            r'sh\("((?:\\.|[^"\\])*)"\)', body)
        for signal in ("TERM", "KILL"):
            matches = [command for command in guarded_commands if f"kill -{signal} $pid" in command]
            self.assertEqual(len(matches), 1, f"{signal} requires an immediate verifier")
            command = matches[0]
            self.assertTrue(command.startswith(r'[ \"\$(tr '))
            self.assertIn("/proc/$pid/cmdline | head -n 1)", command)
            self.assertIn(r'''\" = '$APP_LIST_PACKAGE' ] && kill -''' + signal + " $pid", command)

    def test_client_signal_shell_rejects_reused_foreign_pid(self):
        body = function_source(self.manager, "refreshAppListCarPlayManager")
        commands = re.findall(r'sh\("((?:\\.|[^"\\])*)"\)', body)
        for signal in ("TERM", "KILL"):
            command = next(command for command in commands if f"kill -{signal} $pid" in command)
            # Kotlin quoted strings use JSON escapes plus \$ for a literal dollar.
            command = json.loads('"' + command.replace(r"\$", "$") + '"')
            for identity, expected in (("com.beantechs.applist", f"-{signal} 1234"),
                                       ("com.ts.carplay", ""), (None, "")):
                with self.subTest(signal=signal, identity=identity), tempfile.TemporaryDirectory() as directory:
                    fixture = Path(directory) / "1234"
                    fixture.mkdir()
                    if identity is not None:
                        (fixture / "cmdline").write_bytes(identity.encode() + b"\0argument\0")
                    script = command.replace("$pid", "1234").replace(
                        "$APP_LIST_PACKAGE", "com.beantechs.applist").replace("/proc/", directory + "/")
                    # Override the shell builtin: the fixture never signals a real process.
                    script = "kill() { printf '%s\\n' \"$*\"; }; " + script
                    result = subprocess.run(["sh", "-c", script], capture_output=True,
                                            text=True, timeout=5, check=True)
                    self.assertEqual(result.stdout.strip(), expected)

    def test_failed_attempt_cannot_be_adopted_as_in_progress(self):
        tick = function_source(self.bootstrap, "tick")
        recovering = tick.split("val recovering =", 1)[1].split("val ownsNative", 1)[0]
        self.assertIn('prefs().getString("cpBridgeAttemptPhase", "") == "preparing" &&', recovering)
        self.assertIn('prefs().getString("cpBridgeAttemptIdentity", "") == id.key &&', recovering)
        self.assertIn('prefs().getString("cpBridgeAttemptRevision", "") == bundle.revision &&', recovering)
        self.assertIn("deadline - SystemClock.elapsedRealtime() in 1L..330_000L", recovering)
        self.assertIn("attemptInProgress = recovering", tick)
        failure = function_source(self.bootstrap, "failAttempt")
        self.assertIn('.putString("cpBridgeAttemptPhase", "failed")', failure)

    def test_exhausted_process_budget_clears_gate_without_retrying_same_identity(self):
        tick = function_source(self.bootstrap, "tick")
        blocked = tick.split("Policy.Action.BLOCKED -> {", 1)[1]
        match = re.search(
            r'if \(observed\.mappedPath == null && decision\.reason == '
            r'Policy\.Reason\.ATTEMPT_BUDGET_EXHAUSTED\) \{([^}]+)\}', blocked)
        self.assertIsNotNone(match, "Only an unmapped, budget-exhausted process releases the stale gate")
        self.assertIn('root.run("setprop $GATE \'\'")', match.group(1))
        self.assertNotIn('.remove(', match.group(1))
        self.assertNotIn("attempt(", match.group(1))
        self.assertIn("val attemptsForProcess = CarPlayBridgeAttemptScope.attemptsForProcess(", tick)
        self.assertIn('id?.key, prefs().getString("cpBridgeAttemptIdentity", "")', tick)
        self.assertIn("attemptsForProcess = attemptsForProcess", tick)
        marker = function_source(self.bootstrap, "attemptMarker")
        self.assertIn("CarPlayBridgeAttemptScope.markerName(processIdentity, bundle.revision)", marker)
        self.assertNotIn("identity.boot", marker)

    def test_explicit_retry_clears_the_process_scoped_attempt(self):
        reset = self.bootstrap.split("@JvmStatic fun resetFailureCircuit", 1)[1].split(
            "\n    private fun report", 1)[0]
        for key in ("cpBridgeCircuitRevision", "cpBridgeCircuitIdentity",
                    "cpBridgeAttemptBoot", "cpBridgeAttemptRevision",
                    "cpBridgeAttemptPhase", "cpBridgeAttemptTicket",
                    "cpBridgeAttemptIdentity", "cpBridgeAttemptWarmRestart",
                    "cpBridgeAttemptDeadlineElapsed"):
            self.assertIn(f'.remove("{key}")', reset)

    def test_null_projection_semantics_require_exact_verified_oem_firmware(self):
        source = QUIESCENCE.read_text()
        self.assertIn('DCM_APK_SHA256 = "7859c63e089d6f053df7b4121063e30d4e5f7795cf8fca52a73bebe8a44b9b69"', source)
        self.assertIn("DCM_APK_SIZE = 237_975L", source)
        self.assertLess(source.index("if (!verifyFirmware(context, service.epoch))"),
                        source.index("val online = readOnlineDevicesEmpty"))
        verify = function_source(source, "verifyFirmware")
        self.assertIn("getServiceInfo(ComponentName(DCM_PACKAGE, DCM_SERVICE_CLASS), 0)", verify)
        self.assertIn("File(info.applicationInfo.sourceDir)", verify)
        self.assertIn('MessageDigest.getInstance("SHA-256")', verify)
        self.assertIn("cached.epoch == epoch", verify)
        self.assertIn("cached.modified == modified", verify)
        self.assertRegex(verify, r"catch \(_: Exception\) \{\s+firmwareCheck = null\s+false")

    def test_profiled_null_list_is_absence_but_empty_list_may_be_connecting(self):
        source = QUIESCENCE.read_text()
        decoder = function_source(source, "readActiveProjectionsEmpty")
        self.assertIn("-1 -> return@transactRead true", decoder)
        self.assertIn("0 -> return@transactRead null", decoder)
        self.assertIn("if (count < 0) return@transactRead null", decoder)
        self.assertIn("reply.setDataPosition(listPosition)", decoder)
        self.assertIn("reply.createTypedArrayList(ConnDevice.CREATOR)", decoder)
        self.assertIn("if (devices.isNotEmpty()) false else null", decoder)
        self.assertNotIn("devices.isEmpty()", decoder)
        transaction = function_source(source, "transactRead")
        self.assertIn("if (reply.dataAvail() == 0) result else null", transaction)
        online = function_source(source, "readOnlineDevicesEmpty")
        self.assertIn("if (reply.readInt() != 1) return@transactRead null", online)
        self.assertIn("val entries = keys.map { devices.get(it) }", online)
        self.assertIn("entries.any { it !is ConnDevice }", online)

    def test_retained_records_require_independent_profiled_physical_bluetooth_proof(self):
        source = QUIESCENCE.read_text()
        self.assertIn("CarPlayBluetoothQuiescence.readDetailed(context, online.retainedAddresses)", source)
        self.assertIn("activeEmpty != true -> null", source)
        bluetooth = BLUETOOTH.read_text()
        self.assertIn('APK_SHA256 = "c99e49b47704e610c044ab01aa530ca451784e9e78885b11cad1c1f45518eac0"', bluetooth)
        self.assertIn("!verifyFirmware(context, current.epoch)", bluetooth)
        self.assertIn("started - current.at < SETTLE_MS", bluetooth)
        self.assertIn("for (profile in 0..4)", bluetooth)
        self.assertIn("call(binder, 14, 1", bluetooth)
        self.assertIn("if (before != after) return finish(null, CarPlayQuiescenceReason.BT_DEVICE_SET_CHANGED)", bluetooth)
        self.assertIn("val addresses = before + retained", bluetooth)
        self.assertIn("devices(binder, 13, -1)", bluetooth)
        self.assertIn("call(binder, 4, 0", bluetooth)
        self.assertIn("if (reply.readInt() != 1) return null", bluetooth)
        self.assertIn("result == expectedResult && reply.dataAvail() == 0", bluetooth)
        self.assertNotRegex(bluetooth, r"\bLog\.")
        self.assertNotIn("getUsbSerialNumber", source)
        self.assertNotIn("getWifiAddr", source)

    def test_detailed_diagnostics_reuse_one_observation_without_authorizing_safety(self):
        source = QUIESCENCE.read_text()
        bluetooth = BLUETOOTH.read_text()
        self.assertIn("fun read(context: Context): Boolean? = readDetailed(context).quiet", source)
        self.assertIn("fun read(context: Context, retainedAddresses: Set<String>): Boolean? = readDetailed(context, retainedAddresses).quiet", bluetooth)
        detailed = source.split("internal fun readDetailed", 1)[1].split("private fun requestBinding", 1)[0]
        self.assertEqual(detailed.count("readOnlineDevicesEmpty("), 1)
        self.assertEqual(detailed.count("readActiveProjectionsEmpty("), 1)
        self.assertEqual(detailed.count("CarPlayBluetoothQuiescence.readDetailed("), 1)
        self.assertIn("!online.absentOrRetained -> false", detailed)
        self.assertIn("online.retainedAddresses.isEmpty() -> true", detailed)
        self.assertIn("activeEmpty != true -> null", detailed)
        self.assertIn("radioEmpty == false || activeEmpty == false -> false", detailed)
        self.assertIn("radioEmpty == true && activeEmpty == true -> true", detailed)
        self.assertIn("if (fresh || observed == false) observed else null", detailed)

        publish = function_source(self.bootstrap, "publishSafety")
        self.assertEqual(publish.count("CarPlayBridgeQuiescence.readDetailed("), 1)
        self.assertNotIn("CarPlayBridgeQuiescence.read(", publish)
        self.assertIn("val quiet = reading.quiet", publish)
        eligibility = publish.split("val eligible =", 1)[1].split("val packet =", 1)[0]
        self.assertIn("quiet == true", eligibility)
        self.assertIn("System.currentTimeMillis() - beginWall in 0..2500", eligibility)
        self.assertIn("SystemClock.elapsedRealtime() - beginElapsed in 0..2500", eligibility)
        self.assertNotIn("diagnostics", eligibility)
        self.assertNotIn("reading.", eligibility)

    def test_absent_host_is_clear(self):
        self.assertEqual(self.run_probe("", {}, pidof_status=1), "bridge-map-clear")

    def test_uninstrumented_host_keeps_legacy_reload_available(self):
        self.assertEqual(self.run_probe("1234", {
            "1234": "1000-2000 r-xp 00000000 00:00 0 /vendor/libcarplaystack.so\n"
                    "2000-3000 r-xp 00000000 00:00 0 /vendor/libc++_shared.so\n"
        }), "bridge-map-clear")

    def test_bridge_mapping_is_found_even_without_an_automatic_preference(self):
        for suffix in ("", " (deleted)"):
            with self.subTest(suffix=suffix):
                self.assertEqual(self.run_probe("1234", {
                    "1234": "1000-2000 r-xp 00000000 00:00 0 "
                            "/data/local/tmp/impulse-carplay/v132/libcarplay_video_bridge.so" + suffix + "\n"
                }), "bridge-map-present")

    def test_each_host_pid_is_checked(self):
        self.assertEqual(self.run_probe("1234 5678", {
            "1234": "1000-2000 r-xp 00000000 00:00 0 /vendor/libcarplaystack.so\n",
            "5678": "1000-2000 r-xp 00000000 00:00 0 "
                    "/data/local/tmp/impulse-carplay/v133/libcarplay_video_bridge.so\n"
        }), "bridge-map-present")

    def test_disappearing_or_unreadable_maps_do_not_authorize_reload(self):
        self.assertEqual(self.run_probe("1234", {}), "bridge-map-unknown")

    def test_process_probe_errors_do_not_authorize_reload(self):
        self.assertEqual(self.run_probe("", {}, pidof_status=2), "bridge-map-unknown")
        self.assertEqual(self.run_probe("not-a-pid", {}), "bridge-map-unknown")

    def test_grep_errors_are_distinct_from_an_absent_mapping(self):
        self.assertEqual(self.run_probe("1234", {"1234": ""}, grep_status=2),
                         "bridge-map-unknown")


class ResidentChallengePublicationTest(unittest.TestCase):
    """Replace a protected existing challenge using the source-extracted shell.

    The OEM mv prompts on a mode400 destination unless -f is supplied. Model
    that prompt as an unattended failure; the field additionally showed its
    stdin read consuming the remainder of the root command's script.
    """

    def publication(self, directory, role, nonce, *, forced=True):
        resident = function_source(BOOTSTRAP.read_text(), "residentSample")
        selected = re.search(r'val responseText = root.run.*?script\("""(.*?)\s*cpb_round=0',
                             resident, re.S)
        self.assertIsNotNone(selected)
        body = textwrap.dedent(selected.group(1)).replace("§", "$")
        self.assertIn('mv -f ${q("$challengePath.next")} ${q(challengePath)} || exit 1', body)
        self.assertIn('chown 1000:1000 ${q("$challengePath.next")}', body)
        self.assertIn('chmod 400 ${q("$challengePath.next")}', body)
        target = directory / (role + "-residency-challenge.json")
        packet = json.dumps({"schema": 1, "instance": "fixture-instance", "nonce": nonce,
                             "issuedElapsedMs": 1}, separators=(",", ":"))
        for expression, value in {
            '${q(packet)}': shlex.quote(packet),
            '${q("$challengePath.next")}': shlex.quote(str(target) + ".next"),
            '${q(challengePath)}': shlex.quote(str(target)),
        }.items():
            body = body.replace(expression, value)
        self.assertNotIn("${q(", body)
        if not forced:
            body = body.replace("mv -f ", "mv ")
        return subprocess.run(["sh", "-c", body], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=5,
                              env={**os.environ, "PATH": str(directory) + ":" + os.environ["PATH"]})

    def tools(self, directory):
        # Stub only the ownership syscall so this fixture also runs without root.
        # The source must still request its unchanged UID/GID and real mode400.
        chown = directory / "chown"
        chown.write_text('#!/bin/sh\n[ "$#" = 2 ] && [ "$1" = 1000:1000 ]\n')
        chown.chmod(0o700)
        mv = directory / "mv"
        mv.write_text("#!" + sys.executable + "\n" + textwrap.dedent("""
            import os, pathlib, stat, sys
            args = sys.argv[1:]
            forced = bool(args and args[0] == '-f')
            if forced:
                args.pop(0)
            source, target = map(pathlib.Path, args)
            if target.exists() and stat.S_IMODE(target.stat().st_mode) == 0o400 and not forced:
                sys.stderr.write("mv: overwrite '%s' (Y/n):" % target)
                sys.stderr.flush()
                sys.stdin.readline()
                sys.exit(73)
            os.replace(source, target)
        """))
        mv.chmod(0o700)

    def test_unforced_protected_challenge_reproduces_unattended_prompt_failure(self):
        for role in ("native", "java"):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                self.tools(directory)
                target = directory / (role + "-residency-challenge.json")
                target.write_text('{"nonce":"original"}\n')
                target.chmod(0o400)
                result = self.publication(directory, role, "must-not-publish", forced=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("mv: overwrite ", result.stderr)
                self.assertEqual(json.loads(target.read_text())["nonce"], "original")
                self.assertEqual(target.stat().st_mode & 0o777, 0o400)

    def test_two_forced_challenge_replacements_update_nonce_and_preserve_mode(self):
        for role in ("native", "java"):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                self.tools(directory)
                target = directory / (role + "-residency-challenge.json")
                target.write_text('{"nonce":"original"}\n')
                target.chmod(0o400)
                for nonce in ("next-nonce-one", "next-nonce-two"):
                    result = self.publication(directory, role, nonce)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stderr, "")
                    self.assertEqual(json.loads(target.read_text())["nonce"], nonce)
                    self.assertEqual(target.stat().st_mode & 0o777, 0o400)
                    self.assertFalse(Path(str(target) + ".next").exists())


class PreflightProbeEvidenceTest(unittest.TestCase):
    """Exercise the actual probe wrapper with an inert local CLI and bounded files."""

    def probe(self, cli_body):
        preflight = function_source(BOOTSTRAP.read_text(), "ensureInjectorPreflight")
        selected = re.search(r'val version = root.run.*?script\("""(.*?)"""\), 15_000\)',
                             preflight, re.S)
        self.assertIsNotNone(selected)
        body = textwrap.dedent(selected.group(1)).replace("§", "$")
        self.assertIn("ulimit -f 16 || exit 125", body)
        self.assertIn("exec timeout 10", body)
        self.assertIn("--version < /dev/null", body)
        with tempfile.TemporaryDirectory(prefix="impulse-preflight-probe-") as temporary:
            directory = Path(temporary)
            cli = directory / "synthetic-cli"
            cli.write_text("#!" + sys.executable + "\n" + textwrap.dedent(cli_body))
            cli.chmod(0o700)
            # No waiting is needed for these immediate fixtures. Keep the real
            # kernel file-size limit and exec the inert CLI with its actual argv.
            timer = directory / "timeout"
            timer.write_text('#!/bin/sh\n[ "$1" = 10 ] || exit 90\nshift\nexec "$@"\n')
            timer.chmod(0o700)
            start = json.dumps({"schema": 1, "state": "probe-started"}, separators=(",", ":"))
            body = body.replace('${q(start)}', shlex.quote(start))
            body = body.replace('${q(bundle.injector)}', shlex.quote(str(cli)))
            leaves = ("probe-start.json", "probe.stdout", "probe.stderr", "probe.exit")
            for leaf in leaves:
                body = body.replace('${q("$directory/' + leaf + '")}',
                                    shlex.quote(str(directory / leaf)))
            self.assertNotIn("${q(", body)
            result = subprocess.run(["sh", "-c", body], stdin=subprocess.DEVNULL,
                                    capture_output=True, timeout=5,
                                    env={**os.environ, "PATH": str(directory) + ":" + os.environ["PATH"]})
            contents = {leaf: (directory / leaf).read_bytes() for leaf in leaves}
            modes = {leaf: (directory / leaf).stat().st_mode & 0o777 for leaf in leaves}
            self.assertEqual(set(modes.values()), {0o400})
            self.assertEqual(json.loads(contents["probe-start.json"])["state"], "probe-started")
            self.assertLessEqual(len(contents["probe.stdout"]), 16_384)
            self.assertLessEqual(len(contents["probe.stderr"]), 16_384)
            return result, contents

    def test_valid_stdout_and_separate_stderr_warning_preserve_success_evidence(self):
        result, files = self.probe("""
            import sys
            print('17.5.2')
            print('fixture warning', file=sys.stderr)
        """)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, b"17.5.2\n")
        self.assertEqual(result.stderr, b"")
        self.assertEqual(files["probe.stdout"], b"17.5.2\n")
        self.assertEqual(files["probe.stderr"], b"fixture warning\n")
        self.assertEqual(files["probe.exit"], b"0\n")

    def test_nonzero_probe_preserves_numeric_exit_and_stderr_before_failure(self):
        result, files = self.probe("""
            import sys
            print('fixture failure', file=sys.stderr)
            sys.exit(7)
        """)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")
        self.assertIn(b"fixture failure", result.stderr)
        self.assertEqual(files["probe.stderr"], b"fixture failure\n")
        self.assertEqual(files["probe.exit"], b"7\n")

    def test_oversized_stdout_or_stderr_fails_child_and_keeps_both_files_bounded(self):
        for channel in (1, 2):
            with self.subTest(channel=channel):
                result, files = self.probe("""
                    import os
                    for _ in range(128):
                        os.write(%d, b'x' * 4096)
                """ % channel)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotEqual(int(files["probe.exit"]), 0)
                self.assertEqual(result.stdout, b"")
                self.assertGreater(len(files["probe.stdout" if channel == 1 else "probe.stderr"]), 512)


class InjectorEnvironmentShellGuardTest(unittest.TestCase):
    """Run the exact read-only pre-exec shell with every kernel path redirected."""

    def run_guard(self, mutation=None):
        proof = RESIDENT.read_text()
        selected = re.search(r'fun environmentStateGuard\(.*?return """(.*?)"""', proof, re.S)
        self.assertIsNotNone(selected)
        body = textwrap.dedent(selected.group(1)).replace('§', '$')
        with tempfile.TemporaryDirectory(prefix='impulse-loader-environment-') as temporary:
            directory = Path(temporary)
            boot = '12345678-abcd-4abc-8def-123456789abc'
            system_pid, start = 4242, 123
            for name, contents in (('boot', boot), ('enforce', '0'), ('policy', 'policy-original'),
                                   ('injector', 'synthetic data; never executed'),
                                   ('stat', f'{system_pid} (system_server) S ' + '0 ' * 18 + str(start))):
                (directory / name).write_text(contents)
            executable = directory / 'injector'
            executable.chmod(0o500)
            fingerprint = subprocess.run(['stat', '-c', '%d:%i:%s:%Y:%Z:%u:%a', str(executable)],
                                         check=True, capture_output=True, text=True).stdout.strip()
            expected_policy = hashlib.sha256((directory / 'policy').read_bytes()).hexdigest()
            for name, result in (('pidof', str(system_pid)), ('getenforce', 'Permissive'), ('getprop', '1')):
                tool = directory / name
                tool.write_text('#!/bin/sh\nprintf \'%s\\n\' ' + shlex.quote(result) + '\n')
                tool.chmod(0o700)
            substitutions = {
                '${quote(environment.bootId)}': shlex.quote(boot),
                '${environment.systemServerPid}': str(system_pid),
                '${environment.systemServerStart}': str(start),
                '${quote(environment.policySha256)}': shlex.quote(expected_policy),
                '${quote(environment.injectorFingerprint)}': shlex.quote(fingerprint),
                '${quote(injector)}': shlex.quote(str(executable)),
                '/sys/fs/selinux/enforce': str(directory / 'enforce'),
                '/sys/fs/selinux/policy': str(directory / 'policy'),
                '/proc/sys/kernel/random/boot_id': str(directory / 'boot'),
            }
            for expression, value in substitutions.items():
                body = body.replace(expression, value)
            body = body.replace(f'/proc/{system_pid}/stat', str(directory / 'stat'))
            self.assertNotIn('${quote(', body)
            self.assertNotIn('/proc/', body)
            self.assertNotIn('/sys/', body)
            if mutation is not None:
                mutation(directory)
            result = subprocess.run(['sh', '-c', body], text=True, capture_output=True, timeout=5,
                                    env={**os.environ, 'PATH': str(directory) + ':' + os.environ['PATH']})
            return result

    def test_matching_environment_is_accepted(self):
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_mode_unknown_policy_changed_framework_restart_and_changed_asset_fail_closed(self):
        variants = {
            'enforcing': lambda p: (p / 'enforce').write_text('1'),
            'unknown_mode': lambda p: (p / 'enforce').write_text(''),
            'getenforce_mismatch': lambda p: (p / 'getenforce').write_text('#!/bin/sh\necho Enforcing\n'),
            'boot_changed': lambda p: (p / 'boot').write_text('other-boot'),
            'not_booted': lambda p: (p / 'getprop').write_text('#!/bin/sh\necho 0\n'),
            'multiple_system_servers': lambda p: (p / 'pidof').write_text('#!/bin/sh\necho 4242 4243\n'),
            'system_server_reused': lambda p: (p / 'stat').write_text('4242 (system_server) S ' + '0 ' * 18 + '124'),
            'system_server_dead': lambda p: (p / 'stat').write_text('4242 (system_server) Z ' + '0 ' * 18 + '123'),
            'system_server_unknown': lambda p: (p / 'stat').write_text(''),
            'policy_changed': lambda p: (p / 'policy').write_text('different-policy'),
            'asset_changed': lambda p: (p / 'injector').chmod(0o400),
        }
        for label, mutation in variants.items():
            with self.subTest(case=label):
                self.assertNotEqual(0, self.run_guard(mutation).returncode)


@unittest.skipUnless(os.geteuid() == 0, "Receipt DAC fixtures require root")
class ResidentReceiptSnapshotTest(unittest.TestCase):
    """Execute the actual local probe while an admitted child publishes its exit."""

    def probe(self, *, publication="none", launch=True, exit_present=False,
              admission=True, supervisor_mode=0o500, supervisor_uid=0,
              bad_hash=False, receipt_mode=0o400, receipt_uid=0,
              empty_exit=False, source=None):
        source = BOOTSTRAP.read_text() if source is None else source
        resident = function_source(source, "residentSample")
        selected = re.search(r'val receipts = root.run\(identityGuard\(identity\) \+ script\("""(.*?)"""\)',
                             resident, re.S)
        self.assertIsNotNone(selected)
        script = textwrap.dedent(selected.group(1))
        with tempfile.TemporaryDirectory(prefix="impulse-receipt-snapshot-") as directory:
            fixture = Path(directory)
            born, end, supervisor = (fixture / name for name in ("launch.json", "exit.json", "supervisor.sh"))
            born_text, exit_text = '{"fixture":"birth"}', '{"fixture":"exit"}'
            supervisor.write_text("#!/bin/sh\nexit 0\n")
            supervisor.chmod(supervisor_mode)
            os.chown(supervisor, supervisor_uid, supervisor_uid)
            supervisor_hash = hashlib.sha256(supervisor.read_bytes()).hexdigest()
            if launch:
                born.write_text(born_text)
                born.chmod(0o400)
            if exit_present:
                end.write_text("" if empty_exit else exit_text)
                end.chmod(receipt_mode)
                os.chown(end, receipt_uid, receipt_uid)
            # Publishing during the supervisor hash deterministically recreates the
            # existence-check race without wall-clock sleeps or Android tooling.
            sha = fixture / "sha256sum"
            publish = ""
            if publication == "during_attestation":
                publish = ("printf '%s' " + shlex.quote("" if empty_exit else exit_text) +
                           " > " + shlex.quote(str(end)) + "\nchmod " + oct(receipt_mode)[2:] +
                           " " + shlex.quote(str(end)) + "\nchown " + str(receipt_uid) + ":" +
                           str(receipt_uid) + " " + shlex.quote(str(end)) + "\n")
            sha.write_text("#!/bin/sh\n" + publish + "exec /usr/bin/sha256sum \"$@\"\n")
            sha.chmod(0o700)
            if publication == "disappear_during_validation":
                stat = fixture / "stat"
                stat.write_text("#!/bin/sh\nif [ \"$3\" = " + shlex.quote(str(born)) +
                                " ]; then rm -f " + shlex.quote(str(end)) +
                                "; fi\nexec /usr/bin/stat \"$@\"\n")
                stat.chmod(0o700)
            replacements = {
                "${q(launchPath)}": shlex.quote(str(born)),
                "${q(exitPath)}": shlex.quote(str(end)),
                "${q(supervisorPath)}": shlex.quote(str(supervisor)),
                "${q(admissionHash)}": shlex.quote("0" * 64 if bad_hash else supervisor_hash),
                '${if (admission != null) "1" else "0"}': "1" if admission else "0",
            }
            for expression, value in replacements.items():
                script = script.replace(expression, value)
            self.assertNotIn("${", script)
            script = script.replace("§", "$")
            result = subprocess.run(["sh", "-c", script], capture_output=True, text=True,
                                    env={**os.environ, "PATH": str(fixture) + ":" + os.environ["PATH"]},
                                    timeout=5)
            values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
            return result, values

    def test_exit_published_during_attestation_is_complete_in_the_same_probe(self):
        result, values = self.probe(publication="during_attestation")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values, {"launch": '{"fixture":"birth"}', "exit": '{"fixture":"exit"}',
                                  "receipts_pending": "no"})

    def test_still_missing_exit_remains_pending_without_new_admission(self):
        result, values = self.probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["receipts_pending"], "yes")
        self.assertEqual(values["exit"], "")
        result, _ = self.probe(admission=False)
        self.assertNotEqual(result.returncode, 0)

    def test_complete_pair_does_not_need_startup_admission(self):
        result, values = self.probe(exit_present=True, admission=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["receipts_pending"], "no")

    def test_exit_without_birth_and_invalid_supervisor_remain_fatal(self):
        variants = [dict(launch=False, exit_present=True), dict(supervisor_mode=0o700),
                    dict(supervisor_uid=1000), dict(bad_hash=True)]
        for variant in variants:
            with self.subTest(variant=variant):
                result, _ = self.probe(**variant)
                self.assertNotEqual(result.returncode, 0)

    def test_newly_published_exit_must_pass_owner_mode_and_size_checks(self):
        for variant in [dict(receipt_mode=0o600), dict(receipt_uid=1000), dict(empty_exit=True)]:
            with self.subTest(variant=variant):
                result, _ = self.probe(publication="during_attestation", **variant)
                self.assertNotEqual(result.returncode, 0)

    def test_disappearing_complete_pair_is_not_reclassified_as_startup_pending(self):
        for admission in (False, True):
            with self.subTest(admission=admission):
                result, _ = self.probe(publication="disappear_during_validation",
                                       exit_present=True, admission=admission)
                self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
