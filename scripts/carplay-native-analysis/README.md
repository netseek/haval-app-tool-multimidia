# Offline CarPlay native audit

## Cold passthrough operator controller — 2026-10-23

`cold_passthrough_controller.py` stages and verifies the reviewed bridge/launcher,
then can coordinate the bounded cold gate through `armed -> installed-pinned`.
It refuses active visual CarPlay/Android Auto tasks. After passthrough publication
it arms the inert authenticated clean-map control script; no AltScreen, decoder
or Surface exists until the Impulse five-minute test explicitly requests it. Details and
the controlled commands are in
[carplay-cold-passthrough-controller.md](../../docs/architecture/carplay-cold-passthrough-controller.md).
The controller itself has not run against the OEM process yet; the vehicle was
offline during the read-only preflight attempt.

## Android validation — 2026-10-23

Source9ceda01b passed Mini compile/JVM suite and an actual isolated Android ARM64
library/trampoline smoke. Three OEM RX segments and13 functions verified by bounded
read-only memory reads, no OEM injection. Details and limits in
[Android validation](../../docs/architecture/carplay-android-validation-2026-10-23.md).
`android_isolated_smoke.js` refuses any target except the explicitly identified
disposable sleep process with no OEM modules; never use it as an OEM loader.
No production version, APK installation or OTA generated. Native remains excluded.

## Latest validation — 2026-10-23

Source `1f1a1f7d` passed authorized Mac Mini Java/Kotlin/JNI/ARM64 compilation and
Debug assembly in 2m19s. assembleDebug now also runs testDebugUnitTest. XML reports:
837 tests, zero failures/errors, one expected seed-generator skip. All seven new
experimental Debug policy/retirement methods executed/passed. Earlier pending-test
notes below describe the implementation stages, not current validation status.
Linked native runtime exports verified; bridge remains excluded from the internal
Debug APK. No production signing/version/OTA/install or vehicle test performed.
This does not prove Binder/GPU/OEM ownership, actual hook installation or clean map.

Read-only ELF analysis, not an Android feature, native patch or deployment tool.
Requires Python 3.11+, with the pinned dependencies in requirements.txt installed
in an isolated virtual environment. OEM binaries are private inputs, never bundled.

```sh
python3 -m venv /tmp/carplay-audit
/tmp/carplay-audit/bin/pip install -r scripts/carplay-native-analysis/requirements.txt
/tmp/carplay-audit/bin/python scripts/carplay-native-analysis/audit.py "$LIBRARY" \
  --sha256 "$EXPECTED_SHA256" --function _ZL18_getScreenDisplaysPi
CARPLAY_NATIVE_DIR="$PRIVATE_CAPTURE_DIRECTORY" /tmp/carplay-audit/bin/python \
  -m unittest discover -s scripts/carplay-native-analysis -v
```

Without CARPLAY_NATIVE_DIR the five captured-artifact tests are skipped explicitly.
The five portable tests are synthetic checks of branch classification and hash rejection.
The captured tests pin the exact 2026-10-22 ARM64 stack hash, confirm main-screen calls
in the selected function, recover seven callback slots and check input integrity.
They also check the explicit CoreUtils proxy loader and its symbol-name literals.
Literal extraction is limited to adjacent ADRP/ADD pairs into printable .rodata;
it is not a general dataflow or string-pointer analysis.

test_dual_decoder.py additionally requires FFmpeg with libx264. It generates small
temporary synthetic H.264 inputs and tests separate Linux decoder processes across
secondary stop/restart/resolution changes. The test is skipped if FFmpeg is absent;
a missing libx264 encoder is reported as failure. This is not an AMediaCodec,
OEM integration or CarPlay test. No Android build runs on the coordinator.

The CLI rejects unexpected hashes, architecture, PLT layout, missing/ambiguous
functions, incomplete disassembly and unsupported callback relocations. It validates
each PLT address against the relocation GOT address before naming a transfer.
Symbol sizes and function names come from the artifact; stripped libraries are
unsupported. Direct calls and external tail branches are recorded. Indirect
transfers remain unresolved; conditionals are not treated as direct calls.
Missing AltScreen calls in one function do not prove their absence everywhere.

When gVideoCallback exists, the tool requires the observed 56-byte table and seven
AArch64 relative relocations. This recovers addresses, not a callable C ABI:
do not cast the table or replace callbacks based on this JSON alone.

No artifact is executed, uploaded or patched. Output can include binary-derived
instructions and symbols; treat it as untrusted data. A successful audit is not
proof of negotiation, a second stream, decoder isolation or vehicle readiness.

## Native host components

native/observed_video_abi.h records the seven callback signatures from the exact
ARM64 stack's DWARF, with 64-bit size/offset assertions. It is not a hook, complete
SDK header or runtime ABI verifier. Opaque types remain opaque.

native/packet_conversion.cpp is a standalone C++17 length-prefix-to-Annex-B
converter. Width is an explicit input (1, 2 or 4), not inferred from packet bytes.
Validation precedes allocation; malformed input produces no partial output.
Output is bounded by the caller, including start-code expansion. It supports
multiple NAL units; that does not imply the OEM accepts this format.

These are Linux host builds only, not Android/NDK builds:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/packet_conversion.cpp \
  scripts/carplay-native-analysis/native/packet_conversion_test.cpp \
  -o /tmp/carplay-packet-conversion-test
/tmp/carplay-packet-conversion-test
g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -fno-omit-frame-pointer scripts/carplay-native-analysis/native/packet_conversion.cpp \
  scripts/carplay-native-analysis/native/packet_conversion_cli.cpp \
  -o /tmp/carplay-packet-converter
CARPLAY_NATIVE_DIR="$PRIVATE_CAPTURE_DIRECTORY" \
CARPLAY_PACKET_CONVERTER=/tmp/carplay-packet-converter /tmp/carplay-audit/bin/python \
  -m unittest discover -s scripts/carplay-native-analysis -v
```

The native fixture executable covers 14 fixed cases plus 10,000 seeded malformed
inputs under ASan/UBSan. The CLI is a bounded host test adapter accepting a file
of four-byte-prefixed NALs, not the full AVCDecoderConfigurationRecord (avcC).
The additional Python test encodes ten synthetic H.264 frames, repackages NALs,
runs the actual native converter, then compares decoded framemd5 output.
With both optional inputs present, all twelve Python tests passed.

## Experimental bridge and Android backend

native/video_bridge.{h,cpp} routes through the exact original callback table by
default (BridgeConfig.enabled=false); it never creates a secondary decoder in
that mode. Enabled mode requires explicit principal/secondary identities captured
before Initialize. Missing identities, duplicate identities and unknown streams
fail closed without touching OEM. Identity is type+UUID, not arrival order.

The secondary gets its own decoder from a factory. Stream routes never change on
failure or shutdown; late secondary data cannot fall back into OEM. Configuration
and frame sizes are bounded. Raw input is copied and validated before submission.
MediaCodec input-buffer `TRY_AGAIN_LATER` is transient: it retains the generation and
decoder lease instead of converting two milliseconds of backpressure into teardown.
The synchronous bridge cannot retry that packet, so field validation must separately
prove later frame/keyframe recovery and display-3 rendering. Other dequeue failures
remain fatal and retire only the secondary route.
Secondary shutdown waits for an in-flight submission, stops/destroys only that
decoder and leaves principal delivery independent. Finalize releases the route.
The adapter must quiesce callbacks before reusing a stream address or destroying
the bridge; the OEM ABI itself has no generation field. Decoder factories and
backends must not reenter the same bridge while called under its per-stream lock.

avc_configuration.cpp parses a conservative avcC subset into Annex-B SPS/PPS.
Malformed input, missing sets, reserved prefix width and trailing extensions
are rejected. It checks structural framing, not full SPS bitstream semantics.
android_secondary_decoder.cpp owns one AMediaCodec, output worker and window
reference per instance. It requires an explicit raw-ticks→microseconds converter;
none is guessed. Its caller must supply an independently owned Surface, not the
principal output. Backpressure, output-size changes or codec errors fail closed.
This is a conservative prototype, not a tuned realtime video pipeline.

Secondary metadata/completion/context variants not observed in the captured
branch are rejected. Principal callbacks pass all original arguments/returns
unchanged. No completion ownership contract is invented for secondary input.

Host bridge validation (uses a synthetic backend, not AMediaCodec):

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/packet_conversion.cpp \
  scripts/carplay-native-analysis/native/avc_configuration.cpp \
  scripts/carplay-native-analysis/native/video_bridge.cpp \
  scripts/carplay-native-analysis/native/video_bridge_test.cpp \
  -o /tmp/carplay-video-bridge-test
/tmp/carplay-video-bridge-test
g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/avc_configuration.cpp \
  scripts/carplay-native-analysis/native/avc_configuration_test.cpp \
  -o /tmp/carplay-avc-configuration-test
/tmp/carplay-avc-configuration-test
```

The app's CMake build compiles/links the Android target only on ARM64, using the
pinned NDK/CMake. The resulting libcarplay_video_bridge.so is explicitly excluded
from the APK, and the C++ runtime is linked statically. This validates compilation
without adding a loadable runtime dependency or restricting existing APK ABIs.
All Android builds go through the audited Mac Mini queue, never the coordinator.

Not implemented: actual OEM interception/loader installation, Surface transfer,
runtime clock calibration, secondary-display advertisement, watchdog/visibility
ownership or a user-facing toggle. Removing the APK exclusion alone is not a safe
deployment procedure.

## Pre-creation ingress and bounded observer

`native/oem_ingress.{h,cpp}` adds exception-contained C trampolines around the
observed seven-slot ABI and StartSession(session, UUID, type, context). A bounded
thread-local scope carries an owned identity across synchronous Initialize;
cross-thread or missing scopes reject secondary setup. Disabled mode forwards
without interpreting UUIDs. The captured CFL string API uses 32-bit lengths,
not the conventional 64-bit CFIndex. Host tests exercise nested scopes, exception
unwinding, malformed strings and independent retirement under ASan/UBSan and TSan.

Registration is one-shot and intentionally process-lifetime pinned: the module
and its original OEM targets must remain loaded. Supply real unmodified targets
or interception trampolines, never already-patched entry points. There is no
unload/rebind protocol. Callback quiescence remains an external requirement.
The C bootstrap installs only passthrough state; it does not patch OEM callbacks,
load the module, create a Surface or enable the secondary decoder.

`probe_profile.py` creates an offline profile from three exact hash-pinned private
ARM64 libraries. `oem_ingress_probe.js` is inert until an explicit RPC start with
that reviewed profile and fresh file-hash attestations. It verifies mapped build
identities and code signatures before installing five observation hooks. It logs
pseudonymous stream/session sequences, UUID hashes and sampled raw 64-bit ticks;
it never copies image payloads. Defaults: 60 seconds/1,000 records; hard limits:
five minutes/3,000 records, bounded stream and scope maps. Stop detaches its hooks.
Finalize records entry, not completed resource release or callback quiescence.

Interceptor.attach temporarily instruments native code and adds callback overhead;
this is not zero-impact observation. First actual use requires a parked controlled
test. No automatic Frida deployment, SELinux change or vehicle operation is added.
Node VM tests validate observer logic, not compatibility with target Frida runtime.

Static decompilation of the captured CoreUtils UpTicks shows CLOCK_MONOTONIC in
nanoseconds. AirPlay's frame path uses UpTicks or synchronized NTP-to-UpTicks;
this is not the navigation ETA field. Runtime clock validation is still required;
the Android decoder continues to demand an explicit converter rather than guessing.

Validation commands in addition to the native bridge tests:

```sh
node --check scripts/carplay-native-analysis/oem_ingress_probe.js
node scripts/carplay-native-analysis/test_probe.js "$PRIVATE_PROFILE"
CARPLAY_NATIVE_DIR="$PRIVATE_CAPTURE_DIRECTORY" \
CARPLAY_PACKET_CONVERTER=/tmp/carplay-packet-converter /tmp/carplay-native-analysis-venv/bin/python \
  -m unittest discover -s scripts/carplay-native-analysis -v
```

The expanded Python suite has 14 tests with both optional private inputs supplied.
Neither these tests nor compilation proves a negotiated secondary CarPlay map.

## Independent Surface transport prototype

`CarPlaySurfaceEndpoint.java` lives exclusively in the existing internalDebug
source set (Debug/leanDebug), without a manifest service, receiver, auto-loading,
service-manager registration or call site. Release code is untouched. A future
authorized OEM loader must load this class and native library in the same class
loader, create the endpoint with one exact producer UID and a fresh cryptographic
32-byte session token, then deliver that Binder through an authenticated bootstrap.
No such loader/bootstrap is installed by this change.

The producer sends a Parcelable Surface, positive generation and a remote owner
Binder. The endpoint enforces UID/token, synchronous protocol, bounded Parcel,
no trailing data and a single grant. It imports the queue using
ANativeWindow_fromSurface, retaining its own native reference, and releases only
the received Java wrapper. Explicit revoke, endpoint close or remote owner death
retires that grant. A local owner Binder cannot provide remote death notification;
the UI owner must also revoke before its Surface is destroyed, hidden or replaced.
No lease renewal/TTL or global registry lookup is exposed to remote callers.

`android_surface_transport.cpp` uses a bounded eight-entry, non-reused positive
handle registry. `surfaceDecoderFactory(handle, explicitTickConverter)` can be
claimed once; no converter means no factory. Closing removes the handle before
stopping its associated decoder, outside the registry lock. The portable
`DecoderLease` serializes submission/revocation and stops only its own codec;
retired factories/clients cannot reopen it. It must not call back into itself.

The caller must create a fresh independent consumer/Surface on the intended
display. Neither different Java/native wrapper pointers nor Binder transfer prove
independence from the principal BufferQueue. This code deliberately does not borrow
the existing CarPlay Surface, create a Presentation, change resolution, move an
Activity or negotiate a display. No Binder/Surface/JNI path has run on Android yet.
The shared library remains excluded from all APKs; invoking this debug endpoint
without a separately authorized loader fails rather than loading code implicitly.

Portable lease tests (fake decoder, not Android Binder or AMediaCodec):

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/decoder_lease.cpp \
  scripts/carplay-native-analysis/native/decoder_lease_test.cpp -o /tmp/carplay-decoder-lease-test
timeout 20 /tmp/carplay-decoder-lease-test
```

Repeat with `-fsanitize=thread` for concurrent revoke/submission isolation. Tests
cover single claim, early/duplicate revoke, factory null/exception, backend failure,
client destruction and independent progress while another submission blocks.

API references: [Surface Parcelable/lifetime](https://developer.android.com/reference/android/view/Surface),
[Binder death](https://developer.android.com/reference/android/os/IBinder.DeathRecipient),
[native window references](https://developer.android.com/ndk/reference/group/a-native-window).

Source `7e707bc` passed Java compilation, ARM64 CMake/link and assembleDebug on the
authorized Mini in 1m55s. Both JNI exports were verified in the linked ELF; the
endpoint class appears in Debug DEX, manifest stays unchanged and native library
remains excluded. This validates compilation/packaging, not Android execution.

## Secondary advertisement preparation, not installed

`secondary_advertisement.{h,cpp}` now prepares a separate display array through
explicit injected copy/release/append APIs. It never mutates or releases the
borrowed original array. Default-off returns that original without calling any
API. Enabled preparation requires distinct principal/secondary identities with
the captured types 110/111, an exact decoded CFL UUID match, explicit bounded
pixel/physical dimensions and FPS, and a readiness callback before and after
append. Missing physical measurements fail closed rather than being invented.

The append signature matches the captured eleven-argument AltScreen API. The
copy callback must return an owned mutable array retaining its elements; the
append callback must preserve ownership of that array, or explicitly transfer
ownership of a replacement. Optional night-mode/limited-UI/URL fields are left
absent; no semantics are inferred for them. Failed preparation releases only
owned candidates and returns the untouched original. PreparedDisplays is movable
but not copyable; takePrepared transfers only an owned prepared array. A future
hook must separately handle its original function's return ownership correctly.

`surfaceRouteReady(handle)` checks a registered, non-revoked decoder lease without
holding the registry lock during lease inspection. It proves route availability,
not a rendered frame, independent BufferQueue or iPhone negotiation. Two readiness
checks alone do not make advertisement/revocation atomic: the SecondarySession
coordinator below must guard publication and reject stale generations. No hook/bootstrap
binds these callbacks, and no secondary advertisement is actually sent.

Host validation:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/secondary_advertisement.cpp \
  scripts/carplay-native-analysis/native/secondary_advertisement_test.cpp \
  -o /tmp/carplay-secondary-advertisement-test
timeout 20 /tmp/carplay-secondary-advertisement-test
```

Tests use synthetic arrays/API functions, not captured OEM execution. They cover
default-off, original preservation, ownership transfer, alias/null copy rejection,
identity/geometry validation, append error/exception/replacement rollback and
readiness lost during preparation. Updated lease tests cover active-state checks.
These changes have not been compiled for Android: the user requested no new
version/build until the combined changes are ready. No Gradle/NDK job was submitted.

## Generation-scoped secondary session

`secondary_session.{h,cpp}` now coordinates lease ownership, copied-array
preparation, publication and retirement. It defaults disabled. Explicit arming
consumes a fresh exclusive lease binding and returns a nonzero monotonic generation;
stale generations cannot publish, revoke a newer lease or reopen a closed session.
The ordinary API allows one initial publication per generation. The getter-only
path described below can repeat the immutable announcement without a new decoder.

Preparation happens outside the session lock. The final commit validates the
generation/state and calls a noexcept ownership-transfer publisher while holding
both the session lock and DecoderLease.withActive guard. Even direct client/backend
lease retirement cannot interleave that commit. Revocation invalidates the session
before stopping the decoder outside the session mutex; repeat close/revoke calls
wait for retirement to finish. Other sessions use independent locks/resources.

The publisher must only hand off the owned array, return promptly and never reenter
the session, lease, codec, or OEM callbacks. Decoder factories/backends must not
reenter the coordinator under the lease lock. The host must keep config, borrowed
original array, CFL UUID and OEM functions alive through preparation, and keep the
coordinator alive until all callers finish. Committing an array does not mean the
iPhone accepted it; close cannot retract an array already handed to OEM/phone.
Old stream addresses still require the ingress quiescence/lifetime protocol.

The native Surface registry now owns a session/generation binding after explicit
factory creation. surfaceSessionBinding returns a retained snapshot for the future
OEM adapter. JNI revoke removes the handle first and closes that session outside
the registry lock. surfaceRouteReady reports an active route both before and after
publication; ready separately means an advertisement may still be prepared.
No runtime loader/hook calls advertise and no feature is enabled in the car.

Host tests (fake arrays, fake decoder and publication callback):

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/decoder_lease.cpp \
  scripts/carplay-native-analysis/native/secondary_advertisement.cpp \
  scripts/carplay-native-analysis/native/secondary_session.cpp \
  scripts/carplay-native-analysis/native/secondary_session_test.cpp \
  -o /tmp/carplay-secondary-session-test
timeout 25 /tmp/carplay-secondary-session-test
```

Repeat with -fsanitize=thread. Tests exercise default-off/exclusive binding,
single publication, stale generation/rearm, close/revoke during preparation,
publication versus direct lease retirement, duplicate close waiting for codec
retirement, backend failure and independent session progress. No Android build,
version bump, signing, OTA or vehicle test is performed for this source-only step.

## Fresh cluster consumer source, not activated

`CarPlaySecondarySurfaceConsumer` is a Debug/leanDebug-only TextureView with no
manifest entry or call site. It starts hidden and requires an explicit enable,
an authenticated Binder/token/generation, main-thread lifecycle, attachment to
display 3 and hardware acceleration. It rejects existing or externally supplied
SurfaceTextures and creates its producer Surface from its own new callback
texture. It never reads/replaces the principal CarPlay Surface or creates/moves
an Activity/Presentation. The future host still owns cluster visibility, AA
priority and permission to attach this experimental view.

Grant/revoke Binder calls run on a serial worker, not the UI thread. onReady
means only grant acceptance with measured view dimensions, not a decoded frame,
an advertised display, iPhone acceptance or clean-map content. A size change ends
the one-shot consumer rather than resizing a live codec. No raw pixels are read.

On texture destruction the view retains release ownership until the producer has
acknowledged revoke, or the Binder peer is known dead. Surface and SurfaceTexture
release are ordered by CarPlayConsumerRetirement: producer stopped first, texture
detached before texture release. Release actions run outside the state monitor,
with a single drain owner to preserve ordering without holding it across GPU calls.
The host must close AND detach/remove its view;
close alone may await framework destruction. onStopped is emitted only after
retirement plus texture release (or when nothing was ever granted).

If revoke is unconfirmed, one consumer is quarantined with its resources retained;
a process-local singleton gate prevents accumulating new sessions/queues. There
is no automatic retry loop. Explicit retryRetirement or peer death can complete
cleanup. A hung synchronous Binder call cannot be cancelled by this class; it
blocks only that worker and keeps the gate held. This conservative bounded hold
needs real-device validation, including decoder stop after consumer detachment.
Session tokens are cleared after confirmed retirement and never logged.

Four JUnit tests in src/testDebug cover retirement ordering in either direction,
idempotence, concurrent stop/detach and failed-release retry. They are written but
NOT executed: no Java/Android compilation or APK build was requested in this batch.
Native host session/lease/advertisement regressions were rerun successfully, but
do not validate the new Java view, Binder or GPU lifecycle. No loader calls start.

The delayed texture release follows the
[SurfaceTextureListener ownership contract](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener#onSurfaceTextureDestroyed(android.graphics.SurfaceTexture));
the view follows the [TextureView API](https://developer.android.com/reference/android/view/TextureView).
Combined Mac Mini compilation/unit tests and parked lifecycle testing remain gates.

## Experimental host and AA-priority guards, source only

`CarPlaySecondaryClusterHost` now owns a consumer in an explicitly supplied empty
FrameLayout slot already attached to display 3. It does not create a Presentation,
change an existing layout, remove another child, alter resolution or touch the
main CarPlay Activity. No application call site creates the host. A future display
owner must provide a genuinely exclusive slot and the authenticated endpoint.

Start, pre-draw, grant acknowledgement, explicit onInputsChanged and a bounded
250-ms active-only poll check authorization. An immutable observed snapshot must
be at most 1,000 ms old and affirm opt-in, exclusive slot ownership, AA known
inactive, native CarPlay on main, no safety overlay and no conflicting app. Missing,
future, stale or wrong-display evidence denies. Both snapshots and clock reads
must use elapsedRealtime, including deep sleep, not uptimeMillis; old permits
must not remain fresh through vehicle sleep. See
[Android clock semantics](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime()).

The host additionally reads AA session/request/Surface state and the existing
CarPlay orchestrator state; it never starts/stops their monitors, disables AA,
changes focus or launches/moves native apps. The SnapshotProvider must be a fast
nonblocking read of authoritative cached telemetry, not shell/Binder work on the
UI thread. An uninitialized cached AA STOPPED is NOT evidence for known inactivity.

Loss of permission closes and detaches only this consumer and removes all host
callbacks. onHidden means its view was removed; onRetired separately means queue
retirement completed. Quarantined resources can outlive hiding and retain their
view/context until recovery, bounded to the consumer singleton. No automatic
resume/retry occurs. The source guards are not a global atomic display arbiter;
the real owner must feed immediate AA/native-safety changes and coordinate with
projection ownership. Polling/pre-draw alone cannot prove zero overlap on hardware.

Three src/testDebug JUnit methods cover every permission field, stale/future/sleep
timestamps and wrong displays. Like the four retirement tests, these are written
but not compiled/executed. Host/consumer source awaits combined Mini validation
and parked device tests. Production source/manifest/AA behavior remain untouched.

## Integrated native runtime, synthetic end-to-end only

`secondary_runtime.{h,cpp}` connects OemIngress, the secondary factory and the
generation coordinator. Default-off forwards OEM without UUID reads, secondary
creation or advertisement. Enabled construction requires matching bridge/display
identities, a ready generation, display APIs and an owned lifetime token. The
trusted future loader must ensure that token really retains the CFL UUID/modules
and that original targets match the captured binary; nonnull is not attestation.

Each generation binds only one runtime. Secondary StartSession AND factory creation
are gated on a committed advertisement, while the principal remains independent.
A decoder wrapper rejects dimensions differing from the advertisement. Close
revokes only the captured generation and this ingress's secondary, without closing
newer generations or changing principal routing.

`runtimeForSurface` prepares the runtime using an imported Surface, explicit clock
converter and caller-supplied identity/APIs/lifetime. Actual native window geometry
must match before consuming the one-shot factory. Failed construction revokes its
captured generation. Disabled construction never accesses the Surface registry.
Android adapter changes remain uncompiled. No new enabled JNI/C bootstrap or hook
installs this runtime; the existing C bootstrap stays passthrough-only.

The source C boundary below contains C++ exceptions; actual installation must still
pin callback/module/UUID lifetimes through quiescence. Publisher remains non-reentrant,
noexcept and an ownership-only handoff. Close cannot withdraw a committed array.

Portable integration validation:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/packet_conversion.cpp \
  scripts/carplay-native-analysis/native/avc_configuration.cpp \
  scripts/carplay-native-analysis/native/video_bridge.cpp \
  scripts/carplay-native-analysis/native/oem_ingress.cpp \
  scripts/carplay-native-analysis/native/decoder_lease.cpp \
  scripts/carplay-native-analysis/native/secondary_advertisement.cpp \
  scripts/carplay-native-analysis/native/secondary_session.cpp \
  scripts/carplay-native-analysis/native/secondary_runtime.cpp \
  scripts/carplay-native-analysis/native/secondary_runtime_test.cpp \
  -o /tmp/carplay-secondary-runtime-test
timeout 25 /tmp/carplay-secondary-runtime-test
```

Repeat with -fsanitize=thread. Six synthetic scenarios cover passthrough,
advertisement/creation/frame isolation, duplicate/mismatched binding, stale close,
geometry mismatch and primary progress while secondary submit/close blocks. These
use fake OEM callbacks/arrays/decoder, not actual Surface/AMediaCodec or decoded
map content. No new build/version/signing/OTA/vehicle operation in this source step.

## OEM C boundary, getter ownership and warm principal adoption

`oem_runtime_adapter.{h,cpp}` adds noexcept C entry points for the seven callbacks,
StartSession, display getter, close and diagnostics. C++ exceptions are caught and
retire the secondary; they never deliberately restart/stop principal OEM services.
This cannot catch invalid pointers, native signals or violations of a noexcept
release callback. The library remains excluded and no native hook is installed.

The getter uses the observed DisplayArray(Status*) shape. Enabled construction
requires an explicit owned-return contract and a trusted nonthrowing release API.
On successful replacement it releases the getter's original owned reference once
and transfers the prepared copy to the original caller. Failure retains the exact
original pointer/status; original error/null returns pass through without an append.
Null status pointers are rejected at the boundary. The real hook point's ownership
contract must still be verified before enabling this assumption on hardware.

Repeated or concurrent getter calls prepare independent owned copies from each
fresh principal array. A private runtime/coordinator path permits repeating the
same immutable configuration under the same generation/lease guard. No new decoder
or runtime binding is created. The ordinary publication API remains single-use.
Close before commit falls back to the original response. A response committed
before concurrent close can still return afterward; close cannot retract it, but
secondary creation/frame delivery remains gated by the revoked lease.

Ingress and runtime adapters share one atomic process registration reservation;
neither can overwrite or chain onto the other. Registration does not patch code.
The chosen adapter stays process-pinned with its original targets; there is no
uninstall/rebind API. A future installer must use real unmodified targets/trampolines,
prepare all state before changing callbacks and provide quiescent rollback.

Warm installation needs particular care: an enabled bridge cannot identify a
principal stream created before its Initialize interception. Explicit
adoptPrincipal validates principal type/UUID and binds the supplied existing stream
without invoking OEM Initialize or creating a decoder. Secondary identity, null
and duplicate bindings reject. The future loader must prove that this is a live
principal with fresh capture, retained UUID, valid stream epoch and quiesced
callbacks BEFORE switching hooks. Do not infer the first stream or reuse a pointer
from logs. No capture/installer automates that proof or adoption in the vehicle.

Portable adapter validation uses synthetic callbacks, refcounted-by-test arrays
and fake decoders, not actual CoreUtils/OEM execution:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -fno-omit-frame-pointer -g scripts/carplay-native-analysis/native/{packet_conversion,avc_configuration,video_bridge,oem_ingress,decoder_lease,secondary_advertisement,secondary_session,secondary_runtime,oem_runtime_adapter,hook_installation,coordinated_dispatch,live_session_capture,hook_permit,oem_entry_bridge,unix_hook_endpoint,initial_entry_bootstrap,oem_runtime_adapter_test}.cpp \
  -ldl -o /tmp/carplay-oem-runtime-test
for mode in off enabled legacy race adopt close-getter install install-fail install-contested dispatch dispatch-install permit entry unix-peer bootstrap; do
  timeout 25 /tmp/carplay-oem-runtime-test "$mode" || exit
done
```

Repeat with -fsanitize=thread. Modes cover disabled passthrough, exception containment,
original error/null/ownership behavior, parallel repeated queries without duplicate
decoders, both registration orders/races, warm adoption without reinitialization,
and close during getter preparation returning the original response. No new
Android compilation, version, signing, OTA or vehicle operation performed.

The inert hook coordinator and mandatory real-backend contract are described in
[native/HOOK_INSTALLATION.md](native/HOOK_INSTALLATION.md). Installation modes use
a fake quiescence/publication backend; they do not install hooks on the vehicle.

[native/COORDINATED_DISPATCH.md](native/COORDINATED_DISPATCH.md) describes the
concrete admission/publication backend and its two added portable modes. The
dispatch integration no longer uses the earlier fake transaction, but still has
synthetic capture/native owners and no OEM cold-entry installation or authentication.

The newer [native/OEM_ENTRY_BINDING.md](native/OEM_ENTRY_BINDING.md) documents the
native C bridge, callback-driven capture/retained UUID, generation-bound permits
and actual Unix credential transport. Its three added modes are permit, entry and
unix-peer. Actual inline OEM interception/module attestation and app provisioning
remain absent; do not confuse the host's synthetic OEM calls with a car session.

Generate the separate exact-entry binding manifest offline:

```sh
/tmp/carplay-audit/bin/python scripts/carplay-native-analysis/entry_profile.py "$PRIVATE_CAPTURE_DIRECTORY"
```

This rejects unknown firmware files and does not access a device or install hooks.

The next-stage [native/COLD_INTERCEPTION.md](native/COLD_INTERCEPTION.md) describes
the implemented cold-only Frida entry installer and full loaded-code attestation.
Earlier absent-interceptor notes describe prior source stages. No device deployment
or Android compilation has been performed for this source.

```sh
/tmp/carplay-audit/bin/python scripts/carplay-native-analysis/cold_profile.py \
  "$PRIVATE_CAPTURE_DIRECTORY" > "$PRIVATE_CAPTURE_DIRECTORY/oem-cold-profile.json"
node scripts/carplay-native-analysis/test_cold_interceptor.js "$PRIVATE_CAPTURE_DIRECTORY"
```

The Node command runs a fake Frida process using captured file bytes; it does not
contact the car or perform real ARM64 interception. Actual arming requires the
trusted expected PID, explicit enable, verified build receipt/hash/staged bridge,
principal identity and a process observed before all three OEM modules load.

The deterministic startup window and its staged build are documented in
[`docs/architecture/carplay-cold-start-controller.md`](../../docs/architecture/carplay-cold-start-controller.md).
`make_cold_launcher.py` creates a private auto-arming launcher only from the
reviewed v3 profile and fixed bridge identity; it does not reload the OEM process
or activate a secondary display.
