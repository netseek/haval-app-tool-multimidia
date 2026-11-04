# Coordinated hook installation

`HookInstaller` is an inert orchestration component. It has no default preparation
backend, JNI entry, manifest registration, injection code or app call site. It
cannot install real hooks by itself. Native packaging remains excluded. This
source change does not produce an APK, release or OTA.

The [concrete coordinated dispatch backend](COORDINATED_DISPATCH.md) now implements
admission and atomic route publication. Actual initial OEM entry interception,
authentication, attestation and live capture providers remain absent. Earlier
references below to the absent real backend refer to that full platform boundary,
not to the now-implemented portable dispatcher.

## Order enforced by the coordinator

1. Default disabled returns before invoking any backend. A concurrent request on
   the same installer returns busy instead of waiting behind preparation.
2. The trusted `Prepare` function must authenticate/authorize the request and
   prepare all original targets, module ownership and reversible hook state.
   Rejection returns null. The provided UID/token/request ID are untrusted inputs,
   not an authentication implementation or proof of a Binder caller's identity.
3. Create an enabled adapter without publishing hooks. Preallocate its lifetime
   holder before entering quiescence.
4. `tryQuiesce` must atomically exclude relevant callbacks, prevent new ingress
   and freeze session/stream destruction. If existing callbacks cannot safely
   drain, return null; do not wait from an OEM callback thread.
5. Capture the live principal under that barrier: stream, retained UUID, type
   110 and nonzero epoch. Check currency before and after adoption. Adoption only
   registers the existing stream; it does not call OEM Initialize or restart it.
6. Pin prepared-hook/module ownership and the retained identity into the adapter,
   then claim the shared process registration. A competing legacy/runtime owner
   prevents publication. The ownership holder outlives the runtime destructor.
7. Publish the complete callback table, StartSession and display getter together
   under the barrier. Resume callbacks only when the transaction is destroyed.

## Mandatory backend guarantees

`PreparedHooks` and `QuiescentHookTransaction` are trusted in-process capabilities,
not implementable by values supplied over IPC. Their real backend is still absent.
It must verify the actual Binder peer, session-scoped token, expiry and replay
policy; attest loaded executable modules and original/trampoline bytes against
the reviewed artifact profile; pin the live module/API ownership; and build an
adapter using those exact original targets and the independently owned surface.

All seven video callbacks, StartSession, getter, stop/finalize and session epoch
changes must obey the same quiescence protocol. A private app mutex that OEM
callbacks do not acquire is insufficient. Prevent new admissions before draining;
do not hold locks that a callback needs to finish. Never invoke installation from
an active callback, and do not suspend arbitrary threads holding decoder locks.
`isCurrent` must check the same captured live stream/session epoch, not only a
timestamp, UUID hash or stale observer sequence.

`publish` is noexcept and all-or-nothing: true means every ingress target now
routes through the supplied runtime boundaries; false means every original target
is restored before the barrier is released. Partial code patching must never be
reported as failure while leaving mixed callbacks installed. Preparation/capture
exceptions and transaction destruction must leave original hooks untouched.
The module/UUID owners must actually retain the underlying native resources;
a dummy shared pointer does not provide that proof.

## Failure after process registration

Registration is process-pinned and cannot be undone. On atomic publication failure
the coordinator closes secondary resources, retains the adapter/modules/identity,
and returns `publicationFailedPinned`. Originals remain routed by the backend.
The same installer never retries publication; another installer cannot win the
shared registration. Do not unload the library or reset registration to retry.
This conservative outcome retains bounded resources until process exit.

There is intentionally no automatic uninstall. After successful publication,
closing the runtime stops only the secondary route while the principal continues
through the original OEM callbacks. Hook/module memory remains pinned.

## Validation boundary

Portable tests use a fake trusted preparation backend and a mutex that the fake
OEM source honors. They exercise disabled/auth rejection, callback contention,
stale epoch before/after adoption, invalid capture, exceptions, competing process
registration, concurrent installer requests, atomic success/failure, and retained
lifetimes. Principal frame forwarding is checked before/after publication and
secondary close, with zero original reinitializations and no decoder creation.

They do not prove real authentication, native patch atomicity, instruction-cache
coherency, OEM quiescence, Android JNI/Binder, GPU behavior or iPhone negotiation.
The next task is implementing and reviewing that platform backend; do not deploy
this coordinator by substituting an always-successful mock.
