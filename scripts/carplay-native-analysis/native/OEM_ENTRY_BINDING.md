# OEM entry bridge, session capture and authenticated request transport

The subsequent [cold interception implementation](COLD_INTERCEPTION.md) now adds
initial Frida entry replacement, loaded-code attestation and native module pins in
source. This document's earlier missing-interceptor notes describe the prior stage;
actual Android/device execution, provisioning and activation remain unvalidated.

## Current boundary

The source now provides nine noexcept C entry thunks, a process-pinned dispatcher,
capture driven by the actual callback argument shapes, native UUID ownership,
single-use generation-bound permits and a kernel-authenticated local packet
transport. It is not yet a live OEM installation: no inline interceptor, loaded
module attestation, socket listener, app client or token provisioner is installed.
No car was contacted and no Android build, version, signing or OTA was generated.
Native APK packaging exclusion and default-off registration remain in place.

## Exact captured targets

`entry_profile.py` generates a separate version-2 `oem-entry-binding-v1` profile
from the three exact reviewed ARM64 files. It contains nine targets and four
CoreUtils owner/string APIs, module paths, file hashes, build notes and 16-byte
function prefixes. Its canonical SHA-256 is
`2cd70fbc84b06ef7b69379f83f8629e0baea55a93704cfc852162dbea7c33940`.
The older observer profile and reviewed digest are unchanged.

These are ELF virtual offsets, not Ghidra analysis addresses or absolute pointers:

| Module | OEM target | Offset | C bridge entry |
| --- | --- | --- | --- |
| libAirPlay_ts.so | AirPlayReceiverSessionScreen_StartSession | 0x4401c | impulse_carplay_entry_session |
| libcarplaystack.so | CarPlayScreenStreamInitialize | 0x21234 | impulse_carplay_entry_initialize |
| same | CarPlayScreenStreamFinalize | 0x212bc | impulse_carplay_entry_finalize |
| same | CarPlayScreenStreamSetWidthHeight | 0x21318 | impulse_carplay_entry_dimensions |
| same | CarPlayScreenStreamStart | 0x2140c | impulse_carplay_entry_start |
| same | CarPlayScreenStreamStop | 0x21498 | impulse_carplay_entry_stop |
| same | CarPlayScreenStreamSetAVCC | 0x21558 | impulse_carplay_entry_avcc |
| same | CarPlayScreenStreamProcessData | 0x217f8 | impulse_carplay_entry_data |
| same | _getScreenDisplays | 0x158c4 | impulse_carplay_entry_displays |

CoreUtils offsets: CFStringGetLength `0x608b8`, CFStringGetCString `0x609d8`,
CFRetain `0x5ebf0`, CFRelease `0x5ec14`. File hashes are those in
`probe_profile.py`; the generator rejects any changed input before parsing.
The profile is an offline binding manifest, not proof of current loaded bytes.

`pinOemEntryBridge` defaults to disabled and never patches code. An explicitly
enabled bridge is pinned once for process lifetime. The eventual interceptor
must redirect to these entry thunks, not directly to the secondary runtime: the
outer dispatcher must continue observing and gating every relevant invocation.
Originals must be verified unhooked trampolines. Passing the patched entry address
back as its own original would recurse and is forbidden.

## Live capture and lifetime

`LiveSessionCapture` observes the principal type-110 StartSession scope on the same
native thread as its nested StreamStart. This matches the previously traced
StartSession -> Create/Initialize -> Start path. A preexisting stream or a Start
without that observed parent is not adopted; an unscoped principal Start invalidates
capture. Only the configured exact principal UUID is eligible.

Before calling original StreamStart, the observer retains its UUID through the
verified owner API. After success it publishes a capture containing stream, type,
UUID, monotonically increasing revision and retained identity. The capture only
becomes usable after the outer session call finishes and the dispatcher is idle.
Observation failure leaves original callback execution/status intact.

Session replacement invalidates the previous ticket. Stop/finalize invalidates
both a published record and an in-flight pending Start for that stream, before
the original retirement callback. An older session returning after a newer one
cannot publish over it or invalidate the newer epoch. Secondary stream retirement
does not invalidate a different principal record. Counter exhaustion fails closed.
Retained UUID owners are released outside capture mutexes.

`makeCapturedUuidOwner` binds verified CFRetain/CFRelease function pointers and a
real native-module lifetime handle. It retains before forwarding Start and releases
exactly that reference through a shared owner; snapshots share the owner without
reading an old log or manufacturing a UUID pointer. The helper requires actual
module/API provenance from the future bootstrap, not a dummy ownership token.
It retains the UUID, not the OEM stream/session object itself.

`impulse_carplay_entry_invalidate_session` is an additional teardown boundary
through the same admission gate. The actual disconnect/teardown source must be
mapped to this entry before activation; the nine video/getter hooks alone do not
prove coverage of every OEM lifetime mutation. An older intercepted call or an
unobserved teardown cannot be retroactively protected by this gate.

## Authorization

`HookPermit` is issued explicitly for one trusted expected UID, nonzero request ID,
principal-capture revision and externally generated 32-byte random token. It also
requires a trusted suspend-aware monotonic millisecond clock and current capture
revision provider. The configured lifetime cannot exceed 60 seconds. Production
token generation/provisioning is still absent; tests use fixed fake tokens only.
This is distinct from the independent Surface/decoder generation and its token.

The consumer compares all token bytes, checks actual peer UID separately from the
wire's declared UID and serializes consumption so only one request wins. Expiry,
clock reversal/provider failure, revocation or changed generation rejects use.
Successful consumption erases the stored secret; retry needs a new permit even
when preparation subsequently fails. Caller-owned secret copies remain the
provisioner's responsibility and must never be logged or persisted with artifacts.

`authenticatedPreparation` wraps the prepared capability and transaction. It
rechecks the same authorized capture revision/deadline at capture, both currency
checks and publication. Thus a new session arriving after initial authentication
cannot inherit authorization for the previous principal. Expiry at the final
publication check fails conservatively; a process slot already pinned remains
consumed as documented in HOOK_INSTALLATION.md. No periodic token-expiry action
is introduced after a successful installation.

## Real local peer transport

`UnixHookEndpoint` accepts an already-connected AF_UNIX SOCK_SEQPACKET descriptor,
duplicates it with CLOEXEC and owns that connection for its lifetime. The bootstrap
must provide a dedicated channel with no competing reader; no listener, filesystem
socket path, public port, automatic thread or SELinux change is created here.

For each packet it requires kernel SCM_CREDENTIALS matching SO_PEERCRED PID/UID/GID
on that owned socket. It then feeds that OS UID into permit verification; a UID
declared in the request is insufficient. Descriptor delegation to another sender
PID is rejected. Extra ancillary data, truncation and SCM_RIGHTS are rejected;
received descriptor copies are closed. Poll uses nonblocking recvmsg and fixed
bounded buffers. Socket disconnection does not silently install anything.

Wire format is exactly 56 bytes, unsigned little endian:

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 4 | magic 0x31484943 |
| 4 | 4 | protocol version 1 |
| 8 | 4 | declared UID, also checked against kernel identity |
| 12 | 4 | reserved, must be zero |
| 16 | 8 | request ID, nonzero |
| 24 | 32 | token |

There are no native pointers, symbol addresses, paths or code payloads on this
wire. Packet/decoded token buffers are erased on scope exit. The trusted bootstrap
still must verify that the expected UID belongs to the intended signed app and
securely provision the channel/permit. Root/process compromise is not addressed by
this local authorization mechanism. This is native Unix transport, not a claim
that the previously proposed Binder bootstrap has been implemented.

## Evidence and tests

Fresh REA/Ghidra evidence for the captured CoreUtils wrappers:

- CFRetain: `ev_f33122854e3be3cdc5dfba0f180ecb81982746c9311ab8e3aa0a09fc7bea395c`;
  CFTypeRef input/return, forwarding to CFLRetain.
- CFRelease: `ev_23ef355fa09cdbe7f755a89cc836713e4d91393f79a613a3d9e892d7086945c2`;
  void return, forwarding its CFTypeRef input to CFLRelease.

Full Evidence JSON and generated profile remain private beside the captured
libraries. No raw binary or secret is committed with this document.

Fourteen native process modes passed g++ C++17/Wall/Wextra/Werror with ASan/UBSan
and TSan (28 final executions). New cases cover 32-byte token corruption, forged
declared/verified UID, replay, expiry, generation changes, concurrent consumption,
clock failure, retained UUID ownership, session/Start/stop races, old-session return,
authenticated revision mismatch before installation and preserved principal frames
without a new original Initialize during adoption. The Unix test uses actual Linux
packet sockets/credentials, including rejected SCM_RIGHTS and descriptor cleanup.
Original frames/callbacks, native CF APIs and module owners in these tests are
synthetic, not calls into the ARM64 OEM libraries on the x86 coordinator.

Python: 16 tests, 15 passed, one existing native-converter test skipped because no
converter was supplied. Exact entry profile and unchanged observer profile passed.
No Android/NDK compilation or Android socket/SELinux/device behavior was tested.

## Remaining activation gate

Implement and review initial native interception plus loaded-module/trampoline
attestation and secure app/client/socket provisioning. Establish a real cold
installation boundary with all old callbacks retired before starting the tracked
session; do not guess quiescence for an already-running unobserved principal.
Map actual disconnect/teardown to the gated invalidation boundary. Validate that
the same admission protocol covers all callbacks and session lifetimes. Then use
the authorized Mini queue for Android compilation, followed by a coordinated parked
device test. Existing fullscreen CarPlay/AA/theme flows are untouched by this work.
