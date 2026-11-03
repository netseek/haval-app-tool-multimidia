# CarPlay clean-map runtime handoff

## Scope

This source checkpoint connects the previously isolated pieces of the secondary
CarPlay video experiment. It still requires the reviewed cold interceptor and is
off unless the five-minute CarPlay test is started. It does not move the normal
CarPlay activity, borrow its Surface, alter display resolution, or bypass Android
Auto priority.

## Runtime sequence

1. Cold startup installs the nine passthrough entries and captures a successful,
   retained type-110 principal stream.
2. The persistent OEM-side control script waits for the explicit clean-map test
   broadcast. It loads the endpoint class from the installed Impulse APK, binds
   its JNI methods to the already attested native image, and offers a one-shot
   Binder endpoint to Impulse.
3. The exported Impulse broker accepts the endpoint only from the UID that owns
   `com.ts.carplay`. A fresh random 32-byte token and the captured generation are
   never logged.
4. Impulse creates a fresh `TextureView`/Surface in a dedicated display-3 slot.
   The OEM endpoint verifies the real Binder UID and token, imports that Surface
   in the OEM process, and requests native activation.
5. Native activation resolves the exact loaded CoreUtils/AirPlay APIs, copies the
   getter-owned display array, appends type 111, adopts the captured principal
   without Initialize, and atomically publishes all nine runtime routes.

The alternate stream has its own Surface, `ANativeWindow`, decoder, queue, lease,
and generation. A failed preparation returns the untouched principal array. A
secondary decode failure retires only that route; principal callbacks retain the
captured originals.

## Gates and retirement

The display host requires a fresh policy snapshot, display 3, explicit test enable,
CarPlay on D0, known-inactive AA, no warning/native safety panel and no competing
display-3 app. Eligibility is checked before start, before every draw and every
250 ms. Stop, policy loss, endpoint death, owner death, Surface destruction or
the test's End button revoke the secondary generation and hide the slot.

The native route publication remains process-pinned. A post-publication failure
does not attempt an unsafe unhook; a new OEM process is required for another
native installation. The UI and Surface retirement remain bounded and reversible.

## Evidence status

### Attended bootstrap succeeded, 2026-10-25

The corrected cold path reached `installed-pinned` with all nine original
passthrough trampolines retained in the live OEM process. The separate ESM Java
agent then reported `loaded` and `armed`. The owner reconnected the iPhone and
confirmed normal principal CarPlay on the central display.

The Android 9 linker reentry abort was removed by installing at the attested
internal loader completion boundary under its caller's recursive mutex. Actual
Gum-owned trampolines were cloaked; their executable pages are now checked with
`Memory.queryProtection()` after code commit. The endpoint package also now
declares ESM explicitly: the prior CommonJS entry threw before Java import.

The final source checkpoint is `e55014b1` on the personal 74132 release branch.
54 interceptor scenarios, 48 Python passes (one optional test skipped), the full
regression guard and the unchanged Android/native inputs' Mini Debug build pass.
The production 74132 APK and native bridge image are unchanged; the correction
is in the separately staged launcher, controller and Java agent packaging.

Keep the successful native injector and Java control agent alive for the OEM
process lifetime. A vehicle/process restart requires a fresh coordinated cold
preparation; the temporary startup gate has already been cleared. The old
CommonJS endpoint never initialized its receiver, so the corrected ESM agent was
loaded without replacing the live native process.

This proves bootstrap and principal preservation. Binder offering, Surface
activation, type-111 acceptance and first display-3 frame still require the
explicit five-minute test; do not report the clean map as working yet.

### Offline endpoint preparation, 2026-10-24

The OEM control agent now includes its Java dependency explicitly through the
[pinned agent build](../../scripts/carplay-native-analysis/agent/README.md).
Its full Frida archive is staged byte-for-byte only after the source/lock/artifact
manifest checks. It does not depend on the REPL providing a global Java bridge.

Portable tests reproduced a dropped offer when a legitimate Java callback arrived
through a different JavaScript wrapper. Callbacks now use an opaque binding-attempt
identity. Teardown closes pending endpoints as well as offered ones, stale
disconnects cannot retire a newer request, and principal generation is checked
again before offering. This protects the existing isolation/lifecycle contract;
it adds no Activity launch, display change, native-panel action or AA operation.

The new tests establish model behavior and offline bundling only. The corrected
namespace bridge and complete agent still require coordinated parked Android
passthrough, Binder authentication, Surface retirement and first-frame evidence.
The central was offline during the final retrieval window; no new installation
or runtime activation was attempted in this offline preparation session.

### Prior checkpoint

Portable entry/dispatch tests pass under ASan/UBSan, and the OEM control script
passes JavaScript syntax validation. Commit `81923701` passed the authorized Mac
Mini ARM64/JNI/AIDL/Kotlin/Java build and full Debug JVM suite in 2m01s. The linked
bridge SHA-256 is `aeefaa91be9dc805047e3006cfdb73ea45dd4b94ed67891d956f4cd40b534411`.
Release packaging, the control-plane injection, AltScreen acceptance, stream-111 callbacks and a
first rendered display-3 frame are not established by this source checkpoint.
Those are separate gates before claiming that clean CarPlay maps work.
