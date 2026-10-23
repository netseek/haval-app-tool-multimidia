# Initial OEM interception and loaded-image validation

Subsequent [real Android validation](../../../docs/architecture/carplay-android-validation-2026-10-23.md)
compiled source9ceda01b on Mini and passed isolated library/ARM64 trampoline tests
on the car. OEM RX memory was verified read-only. Earlier uncompiled/source-only
statements below describe the implementation stage; actual cold OEM installation
and new-module timing remain untested, and no production activation was added.

## Source status

`cold_oem_interceptor.js` now implements initial Frida replacement of the nine
OEM entries, linked to `initial_entry_bootstrap.{h,cpp}` and the existing C bridge.
It is inert when loaded: an explicit enabled arm request is required. No Android
build, native artifact deployment, app version, signing, OTA or car operation was
performed. Native APK packaging exclusion remains unchanged.

This implementation has host/model validation only. Actual ARM64 relocation by
Frida, Android linker/module-observer behavior, namespace permissions and live
CarPlay passthrough remain untested on the device. Do not treat the Node model as
execution of the ARM64 OEM functions or as proof of an independent clean map.

## Cold boundary and process identity

The installer checks the explicit expected PID against Process.id and reads the
bounded first command-line field from `/proc/self/cmdline`; only `com.ts.carplay`
is accepted. All three OEM modules must be absent before bridge preparation and
again before observer registration. Any OEM module reported during registration
is rejected as an ambiguous race. No existing principal/session is guessed from
an old pointer or interrupted to create this boundary.

The module observer waits for all three images to be newly loaded after arming,
then installs synchronously from the last onAdded callback. The implementation
relies on the documented Frida callback boundary before application use of the
new module. It never attaches this replacement path late to an already-loaded
service. Read-only `inspect(profile)` can attest currently loaded pristine images
without installing anything; after our replacements it is intentionally no longer
a pristine-image check that should succeed.

Arming requires an operator-controlled early instrumentation window, such as an
appropriately coordinated cold process/car startup. No spawn, process restart,
force-stop, thread suspension or vehicle power command is automated here. A
production integration must not bypass the absent-module checks to force a warm
installation. Existing tracked principal adoption remains the separate later
route-publication step, after initial interception has observed a real session.

## Attestation

`cold_profile.py` generates a distinct version-3 `oem-cold-interception-v1` manifest
from the exact captured modules. It rejects text relocations and executable
segments with writable/unsupported flags. It records full file size/hash, build
note, full executable PT_LOAD byte hashes and all thirteen complete function body
sizes/hashes. The reviewed canonical profile digest is
`31b878ba35c1a7fec07d7e854264837ef3702962a1e059fa5d020afa0d7c4d0f`.
Earlier observer and version-2 entry manifests remain unchanged.

Inside the process, validation requires one module per exact name/path, matching
on-disk files, build note and mapped executable bytes. Every memory read is bounded
to the reported image and verified readable, nonwritable, file-backed mappings;
code must be executable. Multiple adjacent mappings may cover one segment. A
changed instruction beyond the old 16-byte prefix is therefore detected too.
Different firmware, pathname aliases, unsupported protections, duplicate images
or altered code are refused rather than patched around.

The separate bridge is accepted only from the explicit restricted staging path
under `/data/local/tmp/impulse-carplay/`, named `libcarplay_video_bridge.so`, with
an expected size/SHA supplied by the trusted authorized-build controller. It must
not already be loaded. Its bounded ELF64/AArch64 program headers are parsed and
each executable mapped segment compared against the verified file. Required
exports must resolve inside that image to executable addresses. ABI version must
be 1 and native entry state unpinned before modification.

This is not whole-process integrity attestation: relocated writable data/GOT,
other libraries and a hostile root/controller are outside this proof. Expected
bridge hashes must come from the reviewed Mini build receipt, not an untrusted
APK/client request. No raw pointer/profile/bridge path is added to the Unix
activation protocol.

## Native ownership before replacement

Before editing an entry, the script calls `impulse_carplay_prepare_initial_entries`.
Native preparation uses RTLD_NOLOAD handles for the three exact OEM paths plus
the bridge image itself. It neither loads missing OEM modules nor initializes a
phone session. A second loaded-code attestation runs after these pins are held,
before any replacement; addresses are taken from that second check.

The script calls Interceptor.replaceFast for each target and retains its returned
original trampoline. It validates executable/distinct/non-self originals and
passes the seven-slot table plus session/getter trampolines and four CF helper
addresses to the native bootstrap. No custom ARM64 instruction relocator is used.
Native preparation confirms the helper addresses against dlsym from the pinned
CoreUtils handle, builds the original dispatcher/capture, then process-pins it.
Only original passthrough/session observation is installed at this stage; it does
not advertise secondary video or activate the clean-map experiment.

Successfully published native ownership transfers to the pinned entry bridge.
Script.pin keeps normal script unload from discarding Frida trampoline ownership;
there is no successful-state unpin/uninstall RPC. The instrumentation session must
remain alive through the process lifetime. Forced agent/process termination is not
made safe by a JavaScript reference count and is not part of this implementation.

## Failure and cancellation

Before publication, a failed target is included in rollback even if replaceFast
throws after editing it. The script reverts attempted targets in reverse order,
flushes and revalidates pristine OEM code before releasing native preparation and
the script pin. This rollback is allowed only within the initial cold boundary.

If restoration, module-pin release or native publication state is uncertain,
`failed-pinned` retains ownership. After a possibly successful native bootstrap,
no trampoline is freed/reverted based on an ambiguous error. There is no automatic
retry, unload, process kill or force-stop. The operator must inspect that state
before relying on a cold test. It does not imply a working CarPlay session.

Preparation may be cancelled only while armed, before modifications. A fixed
60-second wait timeout and a module unloading during preparation cancel cleanly.
Installed state cannot be cancelled through this RPC.

## Initializer evidence for the pinned firmware

The captured stack has two init-array entries (16 bytes), while the captured
AirPlay/CoreUtils files have no init array or DT_INIT entry. REA traced both stack
roots, four helper bodies and the shared CarPlayMsg constructor. Those inspected
bodies construct std::map/list globals, register destructors and initialize mutex/
condition primitives; no video session or worker creation call appears there.
This supports the selected cold module-load path for these artifacts, not a
general proof about other firmware or all dependency initializers at runtime.

Private full Evidence JSON is retained beside the captured libraries:

| Body | REA Evidence ID |
| --- | --- |
| callback init root | ev_24d7eb067f137a5ee9bf94df57ac7c2779ec3a7ccc0e075be538aae26061cccb |
| service init root | ev_1bdd646bdff593836a1e9a7a82d2dc31f6f0218411a54d6da25e94b63bfe3708 |
| callback first map | ev_6c5b95b950bf5e45cf08daaff402f5c645a5774aaa09ad5aea1eb76e3d31f68b |
| callback second map | ev_87abf6ef36ed0df0d1043fb782e601457fb069dce7a5f79295d08f604c386f0c |
| service first message | ev_e27ab47bdda76ec74a3e717fe9e71aa744ad894ce9f4bb0628154057fa5e1355 |
| service second message | ev_b1d8d1a04a201bd61bf7e2e73d72a1ac9d2b42911424508cc2193b3bbb7413bc |
| CarPlayMsg constructor | ev_bf9e023d16d921c0b9ebcb0dce7278b71f7787cd1a256682ef2e23f48f90a558 |

## Validation and remaining steps

- 26 Node model scenarios passed against the real captured file images and a
  synthetic bridge/Frida runtime: cold success, PID/process identity, warm/race
  refusal, duplicate/path/file/code/protection mismatch, ABI mismatch, target and
  bootstrap failures, failed restoration/pin release, timeout and cancellation.
- 15 portable C++ process modes passed ASan/UBSan and TSan (30 final executions),
  including real dlopen failure on missing OEM modules and bootstrap argument/state
  guards. Successful Android module pinning/native bootstrap was not executed.
- Python 17 tests: 16 passed, one existing converter test skipped. The new full-code
  manifest digest and both earlier manifest digests passed unchanged.

Next: compile the audited immutable source through the authorized Mini queue when
requested, review the exact bridge ELF/receipt, then coordinate an early cold
device test preserving the stock principal route. Verify actual module-observer
timing, trampolines, mapping protections and passthrough before any secondary
advertisement. The signed-app/socket/token provisioning, app activation client,
real teardown mapping and exclusive AA/safety feeds remain separate gates.

API contracts consulted: [official Frida JavaScript API](https://frida.re/docs/javascript-api/)
for module observers, replaceFast/original trampolines, flush/revert, bounded File
reads and script lifetime pins. Runtime support is checked before modifications;
the bundled car runtime's compatibility has not been established by host models.
