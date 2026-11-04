# Coordinated callback dispatch backend

The subsequent [OEM entry bridge](OEM_ENTRY_BINDING.md) adds capture observations
inside admitted calls, C boundaries, permits and real Unix peer transport. This
document's original absent-provider notes describe that earlier stage; full OEM
cold interception/module attestation and app provisioning are still missing.

`CoordinatedDispatch` implements a shared admission barrier and complete route
replacement for seven video callbacks, StartSession and the display getter.
`DispatchPreparedHooks` plugs this mechanism into `HookInstaller`.

This is concrete C++ dispatch logic, not an inline ARM64 patcher. No entry in the
OEM process has been redirected to it. No Binder/JNI bootstrap is added and the
native library remains excluded from APK packaging. New source has portable host
validation only; the earlier Mini receipt does not cover it.

## Admission and publication

Each entry copies one complete route table and counts itself active under a small
mutex, then releases the mutex before invoking its target. The active reference
lasts through the original callback and any nested callback it invokes. Normal
nested OEM calls are supported and are never serialized behind a held callback
mutex. RAII removes the active reference on both return and C++ exception.

`tryFreeze` succeeds only with no active invocation and no other freeze. It does
not wait for an active frame or drain by suspending OEM threads. Once frozen, new
entries wait on a condition variable before reading the table. The scoped freeze
may replace the entire table once; incomplete tables are rejected without edits.
The mutex makes all nine target assignments one publication. Destroying the freeze
wakes waiting callers, including on preparation/capture exceptions and cancellation.
A failed competing freeze must not release the first caller's barrier.

The freeze owner must not invoke an OEM callback through the dispatcher while
holding the barrier: this is rejected with a C++ logic_error rather than waiting
on itself. The eventual noexcept C thunks must contain exceptions; these methods
are not raw C ABI hook entry points. Publication must occur on the acquiring thread.
The frozen phase must contain no Binder calls, I/O, thread joins or callbacks that
wait for another admitted OEM call. A retained freeze pauses new callbacks; there
is no claim of zero impact or a measured device latency bound.

## Prepared backend

`DispatchPreparedHooks` owns the dispatch and a native-ownership handle, and takes
explicit adapter factory, fresh capture and epoch verification providers. The
factory builds before freezing; capture/adoption/publication run while frozen.
An epoch verifier exception fails closed. The installer pins the prepared backend
and retained principal identity before registration/publication, so module owners
outlive the runtime. No global reset or hook unload is introduced.

This replaces the fake mutex/publication transaction from the earlier tests for
the concrete dispatch integration case. Providers and native resources in that
case are still synthetic.

## Remaining OEM integration requirements

- Cold entry installation must route every relevant original invocation through
  this dispatcher before a warm route switch can be considered quiescent. An
  existing call that entered the old target before interception is not counted.
  Complete source/trampoline coverage and a safe initial installation boundary
  are still required; this gate cannot retroactively drain unobserved calls.
- Other session teardown/epoch-changing events outside the nine entries must also
  participate in the lifetime/barrier protocol. The gate alone does not freeze
  arbitrary OEM state or protect stale pointers captured outside it.
- Verify loaded module identities, bytes, target addresses and ownership. Factory
  originals must be the exact unhooked OEM trampolines, not the dispatcher entries
  (which would recursively dispatch into themselves). Do not infer executable
  target correctness from a nonnull function pointer or dummy shared owner.
- Authenticate the actual Binder peer and session token with expiry/replay rules
  before constructing this trusted prepared capability. No always-successful
  preparation callback belongs in a real app bootstrap.
- Keep the session/principal capture fresh, retain its real CF UUID owner and
  invalidate epochs on session replacement/stop/finalize. Static UUID hashes and
  the synthetic captures in tests are not live evidence.
- Measure callback mutex/refcount overhead and frozen-phase latency on parked
  hardware; validate device lifecycle, actual independent queue and iPhone video.

## Portable validation

`dispatch` exercises active frame contention without interruption, ordinary nested
callbacks, callback exceptions, cancelled freeze, invalid table rejection, competing
freeze, prohibited publication reentry, pending-call wakeup and all nine replacement
targets. It checks that pending calls cannot run even after commit until thaw.

`dispatch-install` uses the concrete `DispatchPreparedHooks` with the real source
adapter/coordinator and fake native owners/capture. Capture and epoch exceptions
restore admission; success forwards principal frames before/after secondary close,
never invokes original Initialize, and preserves the prepared ownership handle.

All eleven process modes passed g++ C++17 -Wall -Wextra -Werror with ASan/UBSan
and separately TSan, with no diagnostics (22 final mode executions). These are
not Android, OEM, Binder, GPU or actual CarPlay map-clean tests.
