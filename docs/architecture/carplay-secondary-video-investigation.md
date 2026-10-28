# CarPlay secondary-video investigation

## Real Android preflight and isolated execution — 2026-10-23

[Android validation](carplay-android-validation-2026-10-23.md) now records actual
OEM RX hashes (3 segments/13 bodies), successful Mini compile/JVM suite and genuine
Android ARM64 isolated library/Frida trampoline execution. OEM process untouched,
installed74127 unchanged, no release/OTA. Full cold OEM interception and app/video
activation remain pending; an isolated smoke pass is not clean-map support.

## Cold initial interception and loaded code validation — 2026-10-23

[COLD_INTERCEPTION.md](../../scripts/carplay-native-analysis/native/COLD_INTERCEPTION.md)
documents the now-implemented cold Frida installer, full file/RX/body hashes, native
pre-patching module ownership and conservative rollback. 26 model cases, 15 native
sanitizer modes and Python 16 passed/1 skip. Not Android compiled or deployed.
Existing principal processes are refused. Actual early cold startup/passthrough
and Frida compatibility remain device gates; secondary clean video is unproven.

## OEM ABI entries and session authorization — 2026-10-23

[OEM_ENTRY_BINDING.md](../../scripts/carplay-native-analysis/native/OEM_ENTRY_BINDING.md)
records the nine C entries, exact-artifact offsets, retained callback-driven session
capture, generation-bound permit and actual Unix OS-peer credential transport.
Fourteen host modes passed ASan/UBSan and TSan; Python 15 passed/1 skip. No live OEM
interception, loaded module attestation, trusted channel provisioning, Android build
or car operation. Those activation gates remain; independent map video is unproven.

## Concrete dispatch source — 2026-10-23

[Coordinated dispatch](../../scripts/carplay-native-analysis/native/COORDINATED_DISPATCH.md)
now implements shared admission, active-call tracking and complete route switching,
with concrete PreparedHooks integration. Eleven portable modes passed ASan/UBSan
and TSan. Initial interception of OEM entry points, authenticated live bootstrap,
real module/UUID ownership and epoch capture remain unimplemented. New code has
not been Android-built or deployed; native packaging stays excluded/default off.

## Hook coordination source — 2026-10-23

The inert [HookInstaller](../../scripts/carplay-native-analysis/native/HOOK_INSTALLATION.md)
now sequences trusted preparation, quiescence, live principal adoption, lifetime
pinning and atomic publication. Nine synthetic modes passed ASan/UBSan and TSan.
The platform authentication/attestation/quiescence/hook engine remains absent;
there is no real installation or app activation. New source is not Android-built.

## Latest static caller evidence — 2026-10-23

The exact captured getter/caller ownership path is now documented in
[carplay-getter-ownership.md](carplay-getter-ownership.md). It supports releasing
the getter-owned original once on successful replacement; the outer OEM delegate
adds its own retain and must remain unchanged. Live module attestation, quiescent
installation and hardware negotiation remain unvalidated. No runtime activation.

## Scope

### Independent Surface and advertisement follow-up

Combined source 1f1a1f7d has now passed authorized Mini Java/Kotlin/JNI/ARM64
compilation and 837 JVM tests (zero failures/errors, one seed-generator skip).
All seven experimental policy/retirement methods passed. Native runtime exports
were checked in the linked AArch64 ELF; the .so remains excluded from the internal
Debug APK. These results close the compilation/unit gate, not actual Binder/GPU,
OEM installation, retained-array ownership or iPhone negotiation validation.

The source OEM adapter now wraps the getter and video/session C boundary, with
exception containment, explicit owned-array replacement and repeated immutable
announcements. Legacy ingress/runtime share a process-only registration gate.
An explicit warm-principal adoption path binds a verified live stream without
calling OEM Initialize; this addresses a bridge installed after native CarPlay is
already on D0. Fresh live identity/epoch proof and quiescent hook installation
remain unimplemented. No code guesses stream identity or installs hooks itself.
Six synthetic adapter modes passed ASan/UBSan/TSan, including concurrent getters,
ownership rollback, warm adoption and close during preparation. No hardware result.

SecondaryRuntime now integrates the portable ingress/factory/advertisement path,
requiring matching identities, an exclusive session generation and committed
advertisement before secondary creation. The decoder enforces advertised geometry;
old-generation close cannot affect a newer session or principal. Six synthetic
end-to-end host scenarios passed ASan/UBSan and TSan, including primary progress
through a blocked secondary shutdown. This is not OEM/Surface/codec execution.
The Android Surface adapter checks native dimensions before runtime preparation,
but has not been compiled/used in this batch. Enabled C bootstrap, actual getter
installation, real getter ownership verification, module attestation and app entry
remain unimplemented; the new C/ownership adapter itself exists only as source.

The next source-only step adds a default-off copied-array advertisement builder
and live Surface route readiness inspection. It calls an injected captured-ABI
AltScreen entry only after explicit identity/geometry/readiness validation, and
returns the borrowed principal array on failure. Synthetic ownership/rollback
tests passed; no runtime hook sends this array and no new Android build was run.
SecondarySession now implements source-level advertisement/revoke generation
serialization through a shared lease guard at ownership handoff; direct readiness
checks alone remain insufficient. This coordinator is not installed in OEM.
User requested one combined release
later; keep current version, production packages and OTA unchanged.

A Debug-only explicit Binder endpoint and native Surface registry now provide the
transport boundary, without registering a service or loading a library. Single
grants require exact UID/session token and remote owner death monitoring. Portable
DecoderLease tests cover immediate secondary retirement and concurrent isolation;
no real Binder/JNI/AMediaCodec execution is claimed. Queue independence must come
from a newly created consumer, not pointer inequality or copying the OEM Surface.
A fresh Debug-only cluster TextureView consumer now exists as source, initially
hidden and explicitly started only on an attached accelerated display 3. It uses
its own texture, serial worker IPC and acknowledged retirement; unconfirmed revoke
retains one bounded quarantine slot rather than releasing a potentially live queue.
A Debug-only dedicated-slot host now connects it without a production entry point
or Presentation. Fresh elapsedRealtime authorization and read-only AA/native
CarPlay guards gate entry and close on loss of eligibility. Seven retirement/policy
JUnit methods and all new Java remain uncompiled/unexecuted. Real display ownership,
safety/AA event integration and authenticated runtime loader are still missing;
the source checks are not a globally atomic display arbitration mechanism.

REA/Ghidra follow-up on the exact captured AirPlay hash confirms
AirPlayInfoArrayAddAltScreenDisplay at ELF 0x33d70 passes stream type 0x6f (111)
to AirPlayInfoArrayAddScreenDisplay (0x33ba8). The latter accepts 0x6e/0x6f,
creates a dictionary, adds common parameters, main/alternate-specific parameters,
and appends it to the array. DWARF confirms eleven arguments to the alternate API:
array pointer, CFL UUID, FPS, pixel width/height, physical width/height, optional
one-byte Boolean pointers for night/limited UI, uint32 limited-UI bits, CFL URL.
This is evidence for the captured library only, not a negotiated runtime stream.

Private Evidence: ghidra-alt-screen.json
(ev_02e17d26d30b3e66dd243a897536ed17066d75cbbf89016901188b3e3529397e),
ghidra-screen-dictionary.json
(ev_53936f6a01c799a4e24b8123cf587c64f8d104ca82ff17a51c58c290cb1621c1),
and dwarf-alt-screen-api.json in the private capture directory. Ghidra base is
0x100000; all addresses above are original ELF virtual addresses.

Do not replace MainScreen with AltScreen or mutate its retained array in place.
A future advertisement adapter must preserve principal capabilities/ownership,
append an independently identified secondary only after the codec/Surface route
is ready, and validate iPhone acceptance. No advertisement patch is installed.

Offline investigation of libraries copied with consent from the test vehicle on
2026-10-22. The running service loaded ARM64. No Android runtime, AA, HUD, display,
theme, bridge, OEM APK or library changes are included. Existing full-interface
CarPlay handoff remains separate from an independent clean-map video experiment.

## Observed blockers

The experimental ingress now carries type/owned UUID through a bounded synchronous
StartSession scope before Initialize, with exception-contained C callbacks and
default passthrough. It is not installed into the OEM process. A separate explicit
observer can correlate this ordering using exact-library profiles; its native
instrumentation requires parked validation before field use. Mock/host tests do
not establish actual callback ordering or secondary negotiation on this vehicle.
The adapter is process-lifetime pinned and requires original targets to remain
loaded, with callback quiescence before any future hook/module teardown.

The captured stack's _getScreenDisplays calls MainScreen functions, not AltScreen
functions in that body. JNI forwards one Surface through ANativeWindow and
setCarPlayPlatform to CarPlayScreenInit. The media layer retains a global decoder
and output window; setting another window can change the existing decoder output.
CarPlayScreenCreate returns the global sPlatformScreen rather than allocating a
separate platform context per call. Enabling a flag alone is not a safe implementation.

These are static observations. They do not prove all possible firmware paths,
runtime stream counts, iPhone acceptance or Apple Maps/Waze secondary-video support.
Captured hashes, disassembly and receipts remain private on the coordinator.

## Callback boundary newly identified

The stack exports gVideoCallback, 56 bytes on ARM64. Relative relocations map slots:

| Offset | Stack callback |
| --- | --- |
| 0 | CarPlayScreenStreamInitialize |
| 8 | CarPlayScreenStreamFinalize |
| 16 | CarPlayScreenStreamSetWidthHeight |
| 24 | CarPlayScreenStreamStart |
| 32 | CarPlayScreenStreamStop |
| 40 | CarPlayScreenStreamSetAVCC |
| 48 | CarPlayScreenStreamProcessData |

The captured libcarplay_proxy_ts.so CarPlayVideoInit stores one table pointer;
its ScreenStream wrappers forward their arguments through those slots. Start
forwards a context, integer and string pointer. Their protocol meaning and full
ABI/lifetime must be established, not inferred from parameter widths alone.

This is a candidate instrumentation boundary, not an installed hook. Replacing
the global table could affect the principal stream, callback ownership and
in-flight calls. CoreUtils/proxy share ScreenStream symbol names. The follow-up
below identifies an explicit dlopen/dlsym path, but not runtime replacement safety.

## Identity and loader follow-up

CoreUtils ScreenStreamCreate (0x146c14) explicitly dlopens libcarplay_proxy_ts.so
at 0x146d24, then dlsyms Initialize, Finalize, SetDelegateContext, SetWidthHeight,
SetViewArea, SetAVCC, Start, Stop and ProcessData into per-instance function slots.
Some entries are optional and not exported by the captured proxy. This path is
more specific than guessing global symbol interposition; namespace resolution
and safe replacement of an already-loaded library still need runtime evidence.

AirPlayCreateScreenStreamContext (0x40ab0) allocates 0x50 bytes, copies the supplied
CFString to context+0x20, stores its integer argument at +0x18 and maintains a list
at session+0xaf8. Duplicate strings are rejected; absent strings have a singleton
restriction. _ScreenThread forwards the string/integer to StartSession, which
forwards them to ScreenStreamStart. The stack Start callback stores but does not
use those two parameters in its observed body. Stream identity exists upstream
but the downstream media context is still shared. Do not classify alternate
video by arrival order or infer numeric type meanings from these observations.

On the observed frame branch, AirPlay _ProcessFrame invokes ScreenStreamProcessData
at 0x43940 with null metadata/completion/context. Other callers remain unproven.
The stack converts a length-prefixed NAL to Annex B and checks that prefix+NAL
length equals the supplied packet size. Do not infer support for arbitrary
multi-NAL packets or declare a leak without following buffer ownership.

## Real host decoder experiment

test_dual_decoder.py encodes local synthetic H.264 fixtures and runs separate
FFmpeg decoders. It verifies decoded hashes/dimensions, terminates the secondary,
observes additional principal frames, then starts a secondary replacement at a
different resolution while preserving the principal PID and image. All processes
are reaped and fixtures are temporary. Synthetic sizes 320x180, 160x96 and 192x112
are not vehicle display settings.

This demonstrates separation only for this Linux host/process topology. It does
not test AMediaCodec, Android Surfaces/resource limits, the OEM callbacks, iPhone
negotiation or CarPlay timing. No production native adapter has been implemented.

## Safe sequence

1. Reproduce analysis with scripts/carplay-native-analysis/audit.py; pin hashes
   and ABI, validate PLT relocations, retain unknown indirect transfers.
2. Complete callback ABI, ownership, callback completion and stream identity
   analysis. Establish loader binding before any hook or binary modification.
3. Design separate codec/window/queue/configuration/epoch resources per stream.
   Validate using a real decoder with two synthetic H.264 sources offline;
   simulated counters alone are not video isolation evidence.
4. Only then design reversible native integration and display advertisement.
   Preserve the principal stream, original full-interface handoff and AA priority.
5. Parked runtime acceptance must prove a distinct negotiated stream, actual
   frames, safe end/disconnect/reconnect and independent cleanup before display 3.

Do not announce an unsupported screen as a probe, shift the main Surface, change
resolution or issue focus/restart commands just to test a flag. No OTA is warranted
until the native integration and rollback gates are met. Keep the regression
contract unchanged; any conflicting experiment needs its own explicit review.

## Field evidence required for the next version

Record the exact app version and keep these observations distinct: secondary display
advertisement accepted by the phone; secondary Start/configuration callbacks accepted;
actual frame callbacks on the second stream; first rendered frame on display 3; and
uninterrupted principal CarPlay output. Navigation callbacks, HUD distance and a
five-minute capture do not prove secondary video or long-sleep recovery.

Repeat disconnect/reconnect, a real long sleep/wake cycle and a switch to Android Auto.
Verify independent secondary cleanup, AA priority and no impact to the principal screen.
Share only anonymized counters/timestamps and redact route, location and device identity.

## Validation

### Mac Mini compile/link result

Source 07567766ab6746a5fcc10ca2dfa64a06463e9915 passed the authorized Mac Mini
ARM64 CMake configure/build/link and assembleDebug job
haval-74126-work-07567766ab6746a5-20261023T011011Z-24713 (2m9s).
Fetched checksums passed. The APK excludes libcarplay_video_bridge.so and shared
C++ runtime; its existing arm64/armv7/x86/x86_64 native entries remain present.
It is an internal Debug artifact, not a production-signed OTA or car-test build.
No new JVM Android suite or actual AMediaCodec execution was performed by that job.

Native host bridge passed ThreadSanitizer as well as ASan/UBSan. Parser and packet
conversion seeded malformed-input checks and twelve Python tests passed. None
of these results proves OEM interception, secondary negotiation or clean-map video.

### Experimental bridge implementation

The portable VideoBridge core and an AndroidSecondaryDecoder backend now exist
under scripts/carplay-native-analysis/native. The core defaults to exact OEM
passthrough. Enabled construction requires explicit principal/secondary identities
before Initialize; unknown/duplicate identities fail closed. Secondary resources
are independently created, serialized, bounded, stopped and released, with no
fallback to OEM after failure. Routes remain fixed until finalization.

The Android backend owns one codec and ANativeWindow reference per instance,
drains output on its own worker and requires an explicit clock converter. The
avcC parser supplies Annex-B CSD-0/CSD-1 without guessing extension formats.
Input-buffer dequeue distinguishes MediaCodec backpressure from fatal decoder failure:
`AMEDIACODEC_INFO_TRY_AGAIN_LATER` keeps the same stream generation and decoder lease
alive, while other negative results still retire it. Because the OEM callback is
synchronous and has no retry contract, the transient packet is currently dropped;
real recovery still requires observing a later keyframe and rendered frame rather than
treating a retained generation as video success.
This follows Android's documented SPS/PPS format:
https://developer.android.com/reference/android/media/MediaCodec#CSD.
Packet/configuration conversion is independent code, not copied FFmpeg source.

The adapter above Initialize is still a missing integration layer, not something
these classes silently provide. No function-table or inline hook is installed;
there is no Surface IPC, display advertisement, clock calibration or runtime UI.
Backends/factories cannot reenter the bridge under a per-stream lock. A future
loader must quiesce all callbacks before pointer reuse or bridge destruction and
handle exceptions at the ABI boundary. Unknown completion variants fail closed.

The Android build compiles/links an ARM64 CMake shared target, but excludes that
target from APK packaging, with a static C++ runtime. This avoids changing runtime
dependencies or supported APK ABIs while validating the backend through the Mac
Mini queue. No inactive binary is installed in OEM. This is not yet a car-test OTA.

### Ghidra/DWARF and native component follow-up

Ghidra 12.1.4 is now available in a private coordinator tool directory, using the
existing JDK 21 and process-scoped environment variables only. The official
archive SHA-256 was verified against GitHub asset metadata. No REA registration,
global Java configuration, car service or product build controls were changed.
Earlier provider-unavailable notes describe the preceding stage, not the current one.

REA CLI full Evidence and snapshots for _getScreenDisplays, Start and ProcessData
are retained privately. Ghidra loads this ELF at base 0x100000: analysis addresses
are not original ELF virtual addresses. The first request used the wrong coordinate
and failed; the corrected requests succeeded. Generated C remains decompiler
output, including unresolved locals and imperfect variadic recovery, not source.

The stack includes DWARF. It confirms ScreenStreamPrivate is 96 bytes and
CarPlayStackServiceVideoCallback is 56 bytes. Start's arguments are ScreenStreamRef,
AirPlayStreamType (uint32_t) and CFLStringRef; ProcessData includes size_t,
uint64_t display ticks, dictionary, completion(void*) and completion context.
native/observed_video_abi.h captures the observed seven-slot ABI with explicit
opaque types and layout assertions. No stream-type numeric mapping is inferred.

Crucial ordering: AirPlay StartSession invokes ScreenStreamCreate (0x440fc),
which invokes Initialize, then SetDelegateContext (0x4412c), then Start (0x44140)
with type/UUID. Therefore routing only at the seven-slot Start callback is too late
to prevent alternate Initialize from touching the shared OEM platform resource.
A future bridge needs identity captured above creation (for example at the observed
StartSession entry), per-stream binding and verified reentrancy/lifetime behavior.
This candidate is not yet a tested interception strategy. Do not install a
table-only hook that initializes both streams in the existing OEM decoder.

Implemented a portable native packet-conversion component outside the Android app.
It validates 1/2/4-byte NAL prefixes, checks complete multi-NAL input before allocation,
bounds output expansion and rejects malformed input without partial output. It
owns returned bytes and never mutates the caller's vector. This is not a decoder,
avcC configuration parser, OEM callback bridge or Surface receiver.

Native host validation: 14 fixtures plus 10,000 seeded malformed-input cases under
ASan/UBSan. A real ten-frame H.264 fixture passed through the actual C++ converter
and decoded to identical framemd5 output. The full Python suite now has twelve
passing tests. No ARM64/NDK build or runtime native interception is validated.

Eleven tests passed: five portable/synthetic, five captured-artifact checks and
one real host dual-decoder experiment. No Android build, OEM dual-decoder or vehicle
test performed. Native REA decompiler provider is unavailable; this investigation
uses readelf and pinned Capstone/pyelftools output, not invented REA Evidence.
