# CarPlay bootstrap: preserve the existing permissive policy

The first private 145 candidate passed quiescence but did not become READY. Its
native CLI started at kernel elapsed 42.50 s; the kernel recorded a SELinux policy
reload at 42.77 s and the Android framework died immediately afterwards. The
injector returned code 4, `The connection is closed`, without ARMED/resident proof.
This temporal relation motivates the change; it does not establish causality.
No out-of-memory kill was demonstrated. The unrelated ambient-lighting crash
messages do not justify disabling vehicle-control services.

Both the original kernel command line and `getenforce` show that this head unit
already boots permissive. The pinned Frida 17.5.2 initializer nevertheless invokes
`frida_selinux_patch_policy()` before argument parsing, including `--version`.
That upstream implementation writes `/sys/fs/selinux/load` without an initial
permissive check. Sources: [initializer](https://github.com/frida/frida-core/blob/bcd4a3d586f5a95cad943ddb176be23aadf1dfd7/inject/inject-glue.c),
[policy patcher](https://github.com/frida/frida-core/blob/bcd4a3d586f5a95cad943ddb176be23aadf1dfd7/lib/selinux/patch.c).
The upstream [runtime-option request](https://github.com/frida/frida-core/issues/1198)
does not supply a supported CLI switch.

## Separate, reproducible bootstrap asset

The original `res/raw/fridainject` remains unchanged. The authorized Mini builds
a separate `cpbridge-inject` asset from that exact immutable input. The input SHA
is `bdfa97cdd84ca678c5e5eea9eb6625c9528acff32b4f9413f1e5c58a3d15222e`.

The profiled AArch64 initializer first calls `frida_init_with_runtime`, restores
its frame and then tail-branches to the policy patcher. Only that final branch
at file offset `0x449f98` (VA `0x44af98`) changes from `111da614` to `c0035fd6`
(`RET`). Initialization and its return ABI are preserved. The expected derived
SHA is `32f7c702ea36c9ad1d09d2dc9e21de5f4994d98f855c4e51db01bcc6d0fd496a`.

The tool verifies the complete input digest, ELF architecture, executable mapping,
caller preimage, branch target and complete derived digest. Unknown bytes abort
the build. `--audit` is read-only; real Android output is emitted only by the
Mini's generated-assets task. The profile and tool participate in the controller
source digest. Local LLVM disassembly supports the instruction mapping; the native
REA analysis provider was unavailable, so this is not a REA decompilation claim.

The manifest contains five versioned files including the derived injector, plus
its original/derived digests, `policyMode=permissive-existing-policy-v1` and the
exact patch profile. Other application uses of the original resource are unchanged.
The extra asset increases APK disk size; it does not add another runtime process.

## Runtime admission and evidence

The runtime must already have kernel enforcement value `0`; enforcing, unknown,
changed or unreadable mode blocks the bootstrap. No code changes SELinux mode.
Before a cold attempt, a root-owned one-shot compatibility probe runs the derived
CLI's `--version` and requires the same kernel boot, policy SHA, `system_server`
PID/start and immutable injector identity before and after. A marker precedes
execution; interrupted/failed probes cannot automatically run again in the same
boot and revision. A successful root-owned receipt is adopted only if its live
environment still matches.

The approved environment is checked before the idle host restart and immediately
before each native/Java child exec. Large injector hashing belongs to preflight;
the cold gate uses its root-owned immutable-file fingerprint. The original cold
deadlines, quiescence checks, post-exit resident proofs and native/Java ownership
requirements remain mandatory. The environment is local attestation data and does
not change the endpoint's JSON binding protocol or theme bridge contract.

A successful `--version` probe verifies only initialization. It does not prove
that attach, helper or Java paths preserve the policy. Field validation must also
confirm new-boot READY, nine pinned native hooks, Java armed, and an unchanged
Android framework/policy after the complete preparation. Until then this remains
a private candidate, with automatic preparation disabled on the installed first
candidate to avoid repeating its failure. The vehicle was switched OFF and
confirmed through a fresh GWM update before this offline work.

The modified component's notice/license are bundled under
`assets/licenses/frida-bootstrap-injector/`. The eventual release also includes
`frida-bootstrap-source.tar.gz` containing its reproduction tool, profile and
upstream notices, without private application history.

## Field follow-up and console transport

The second private 145 (source `0d649c14`) passed initialization and native CLI
exit/resident ARMED proof on the head unit. The original policy hash and
`system_server` PID/start stayed unchanged throughout. Preparation then failed
while reading a later resident response, with `Local root command timed out`.
It did not reach READY. Automatic preparation was paused and GWM OFF freshly
confirmed at 23:42:28 UTC; no public 145 was promoted.

The exact timeout cause was not captured. A Linux PTY test did not reproduce the
hypothesis that the old multiline octal input necessarily overflows the console.
Source inspection did identify avoidable transport costs: octal expands input
fourfold, and the completion regex was rebuilt and rescanned the entire response
on every read, including possible single-byte reads in Commons Net 3.12.

The controller now sends gzip/hex input using the utilities already verified on
this head unit. Small commands use one line below 3800 bytes. Larger commands
append hex to a same-session shell variable in bounded lines; each append has a
unique anchored acknowledgement that must arrive before the next line is sent.
Only the final packet decodes the complete frame into a variable and executes it
after gzip/CRC success. No remote staging file, new injector, or command retry is
introduced. A timeout or missing acknowledgement closes the connection; Shizuku
fallback remains restricted to a failed connection before any execution starts.
The existing read deadline includes packet acknowledgements and elapsed upload
time, without increasing cold proof or gate deadlines. Socket writes themselves
have no separate cancellation timeout; each packet is bounded to limit that work. Input and cumulative responses remain bounded.

Completion patterns are compiled once and scan only newly received data plus a
marker-sized overlap. Echoed packet text cannot satisfy anchored output markers.
Timeout diagnostics contain only packet number, byte/character counts and whether
the actual begin frame was seen. Six JVM tests exercise shell round trips,
nonzero status, corrupt-packet nonexecution, multipart admission, input bounds and
fragmented/echoed output; they execute only on the authorized Mini. Actual full
preparation and preparation after a new boot still require field evidence.

## Bounded reads of the background Telnet queue

Candidate `0b108ff8` passed all six transport JVM cases and signed Release, but
field preparation again timed out at the same response call: packet 1/1,
1728 wire bytes, 11647 received characters and begin frame observed. Native
CLI exit/ARMED proof and policy/framework remained healthy. An inert fixture
reconstructed from the exact source completed on the head unit in 0.726 seconds
with a valid end frame. Extra bytes were interactive shell redraw, not duplicate
execution. A separate echo-off fixture did not remove the redraw, so no echo-mode
change is shipped. Preparation was paused and GWM OFF confirmed at 00:05:43 UTC.

Commons Net 3.12's threaded input can expose an InterruptedIOException from its
socket reader during a bulk read, after bytes have already been copied. Catching
that timeout and discarding the call's partial buffer can lose framing bytes.
This is a source-established hazard, not a proven attribution of the saved field
failure. The socket background reader now uses timeout zero. The single consumer
polls available() and requests at most those already-buffered bytes, sleeping
briefly under the unchanged outer deadline when none are available. No future
bytes are needed to satisfy such a read. Real I/O failures remain terminal;
empty-queue EOF may be reported at the outer deadline. Disconnect still closes
the socket and its background reader. No replay is admitted after connection.

Two additional JVM tests cover nonblocking read admission and fragmented frames
through the actual Commons Net client on a loopback-only test socket. A timeout
also carries a bounded private raw tail, written only to the app's private
filesDir/carplay-root-last-timeout.txt. Shared failure messages retain counts and
frame flags only. This evidence is never a release asset. Full physical READY
and automatic preparation after reboot remain required before promotion.

## Confirmed cause of the residency response timeout

Candidate `ac7ee9ac` preserved the raw timeout output privately. It shows the OEM
command asking `mv: overwrite .../native-residency-challenge.json (Y/n):` before the
expected end frame. The previous challenge is intentionally mode 400; a later
plain mv enters an interactive protected-target prompt, consumes the remaining
script stdin and prevents normal completion. This directly identifies the cause.
Earlier inert fixtures created a first challenge and therefore missed replacement.
The queue-read hardening remains valid but did not remove this separate prompt.

Only repeated challenge publication now uses `mv -f next current`. This preserves
same-directory atomic replacement, mode 400, UID/GID, identity/nonce checks and all
deadlines. The shared path covers native and Java. Immutable first-write receipts
and other no-overwrite rules remain unchanged. On the actual head unit, an inert
fixture with an existing mode-400 destination completed two forced replacements
and returned end=0 in 0.88 seconds, without touching the production protocol.
Portable source-extracted tests reproduce the OEM prompt policy, reject the old
no-force publication and confirm two successive native/Java nonce updates while
retaining mode 400. The full lifecycle suite passes 38 cases. Production auto
preparation was paused and GWM OFF confirmed at 00:20:31 UTC. Signed build and
full preparation/new-boot READY are still required before public promotion.

## Full reboot validation and retained preflight evidence

`af43c4b5` completed real preparation: nine pinned hooks, Java armed, both CLI exits
zero and fresh matching post-exit nonce samples; policy/framework unchanged.
After the authorized full reboot, the new boot created an empty preflight marker
and did not admit a cold attempt. Historical READY was correctly rejected. Thread
dumps showed scheduled workers idle, not deadlocked; kernel entropy initialized
at 0.394 seconds, the immutable executable remained root500 and the framework/
policy were stable. The stripped release logs and memory-only intermediate probe
made the precise first failure unrecoverable. No repeated probe was authorized.
Automatic preparation was paused; GWM OFF confirmed at 00:41:49 UTC.

The revised preflight retains root-only start, stdout, stderr and numeric exit
files. A child-only file-size limit bounds each output file to at most 16 KiB;
stdout must additionally fit 512 bytes before parsing. The existing 15-second
root read budget contains a 10-second probe timeout. Missing timeout is detected
before admission/marker creation. Stderr warnings no longer contaminate the version
string; exit zero, exact stdout version and unchanged complete environment still
remain mandatory. Diagnostic files cannot substitute for canonical success.json.
Private per-stage records include before/after environment and typed root errors;
the first failure for a boot/revision survives later adoption checks.

The root command compound now receives stdin from /dev/null, protecting its parent
shell control stream from a child consuming it. Production and JVM cases use the
same frame generator. A padded test demonstrates the old missing-end-frame behavior
and validates isolation. This is protocol hardening, not a proven attribution of
the Frida version probe failure; upstream --version returns before its Application
stdin-reading path. No admission, callback ownership or cold deadline is bypassed.
Portable lifecycle/probe fixtures pass 41 cases; full signed Mini/JVM and new-boot
preparation are still pending.

## Final 145 field and publication result

Source7b5d79b2 passed the signed Mini build, full JVM suite and ten transport tests.
The exact APK was installed and preparation passed. A full Android reboot then
prepared automatically with fresh new-boot ownership, nine pinned native hooks,
Java armed, two matching post-exit nonce samples per role and unchanged framework/
policy. No manual configuration/reset was used after reboot. Probe stdout was
17.5.2, stderr empty, exit0, with identical before/after environment. This establishes
the final build's behavior; it does not retrospectively identify the discarded
intermediate preflight failure. The owner's phone route/HUD test remains pending.

Version74145 was publicly published and its complete APK hash verified before
preview-manifest promotion. Public tag references the existing public source base;
private history and device logs were not published. GWM OFF was freshly confirmed
at01:08:44UTC27Oct; automatic preparation remains enabled for the next start.
