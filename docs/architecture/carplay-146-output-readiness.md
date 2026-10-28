# CarPlay output corrections for preview 146

The first owner test on 145 proved automatic bootstrap readiness and reception of
265 Apple Maps callbacks, including 244 text-eligible payloads. It did not prove
video output or HUD publication: the map host rejected admission before creating
a consumer, and the owner saw no text. The original aggregate logs cannot identify
the unique failed predicate. This revision fixes independently reproducible source
and lifecycle gaps and records discriminating evidence for the next manual test.

## Actual projection observations and bounded admission

The map test samples one ActivityManager stack listing off the main thread every
500 ms, only during the explicit five-minute lease. It shares the existing 250 ms
cache without changing other readers' return value or cache TTL. The observation
timestamp is the actual command's start, including when another reader filled the
cache. Unknown, malformed, ambiguous, oversized or stale observations cannot admit
video. Every task record is validated, including partial non-CarPlay records that
could conceal a competing task. A visible D0 CarPlay task and absence of a D3
CarPlay task replace the unrefreshed orchestrator enum as evidence.

The main thread checks view ownership, display, attachment, visibility and size,
native safety panels/warnings, competing D3 apps and actual AA session/surface.
AA intent from a theme is recorded but does not itself block CarPlay when a fresh
independent observation explicitly proves AA inactive. Unknown AA still blocks.
No projection Activity is moved, launched, resized or stopped by this correction.

Admission waits before requesting the OEM endpoint. A received offer retains its
original five-second lifetime and exact endpoint/token; a transient negative
before consumer creation no longer discards it immediately. If it expires before
any consumer starts, the existing REVOKE/REQUEST protocol can request another,
with at least one second backoff and at most three total requests. Waiting for an
offer is separately bounded to 30 seconds; the overall lease remains five minutes.
Once any consumer starts, automatic retries stop, and confirmed retirement remains
mandatory. Late offers have no wire request ID and can still fail closed; no new
temporal-correlation guarantee is claimed. No OEM agent, native hook or ABI changed.

One process-lifetime serial worker prevents repeated tests from accumulating
blocked ActivityManager reader threads. A separate main-thread check expires
leases and pending offers even if a shell read stalls. Old results and callbacks
are fenced by the capture/request/offer identities.

## Passive Android Auto observation for the HUD and map

The prior AA poll may never provide a negative observation when its command Binder
has never been bound. The new capture-scoped observer uses only the already-running
service, with bind flags zero, and only after verifying the exact patched APK:

- SHA256 `c40147275d9bd0f32e89230d4fcce0e8b073877faf8e9d57f920033495e35cd7`.
- Size 2472405 bytes; explicit service component and LinkCommand descriptor.
- Read transaction 21 only, with exception/int framing and no trailing bytes.

Retained REA evidence establishes that this patched APK's `onServiceStart` is
NOP/RETURN_VOID. Its `onBind` may set the service's initialization marker and call
that empty method; it does not initialize projection through that call. This is
not a claim about stock or unknown firmware. Missing service, profile mismatch,
Binder replacement/death, malformed replies and slow reads remain unknown. There
are no connection, recovery, activation, media or HMI subscription commands.

The exact getter returns -1 for missing current device/controller/power. That
profiled result and statuses 1/5/6/10 require a stable Binder for one second and
two fresh negative samples at least 500 ms apart. Statuses 3/7/8 veto immediately;
0/2/4/9 and unrecognized values remain unknown. A dedicated serial worker avoids
blocking the capture timer. A 1.5-second watchdog invalidates a stalled read and
late results cannot authorize a later session. The observer unbinds on stop or
the absolute capture deadline. Its negative evidence expires after five seconds.

Only this fresh, settled passive negative can admit CarPlay. A legacy `false`
notification cannot turn an unknown/transitional passive result into permission.
AA directions, positive link evidence and actual AA session/surface always win.
The AA publisher, JSON, recovery controller and OEM APKs are unchanged.

## Text rendering and private diagnostics

Text-only guidance has a dedicated `TEXT` style in Navigation and AR. It draws
the supplied street and distance inside the existing HUD free box, ellipsizes the
street and fits/clips long distance text. It never guesses maneuver IDs, arrows,
lanes, route geometry or ETA. Existing AA maneuver styles and radar priority remain.

The test UI reports plain-language waiting reasons and asks the owner to return
to CarPlay on the main screen after pressing Start. Private diagnostics distinguish
map admission reasons, observer state, source/data ages, actual publication and
the frame drawn by the native display-4096 view. They contain booleans, age buckets
and enums, with change/rate limits; no road, distance text, GPS or device identifiers
are added. Existing explicitly requested raw callback capture remains private.
The cluster diagnostic lease is acquired before enabling map admission.

## Validation and manual procedure

Regression models cover actual/ambiguous task listings, offer waiting/expiry and
request limits, no retry after consumer start, firmware/epoch/settling/status
classification, legacy-negative rejection, fresh AA priority and cleanup after
data expiry. JVM/Android builds run only on the authorized Mac Mini. The release
handoff records the exact immutable source, job, signed artifact and results.

The car is offline. No remote installation, restart, injection or power command
is part of this release phase, and no physical output success is claimed.

1. Install the published corrected 146, then restart the central with phones disconnected.
2. Wait for “Bridge preparada para esta sessão”.
3. Connect the iPhone and start an Apple Maps route.
4. Start the five-minute test in Impulse, then return to CarPlay on the main screen.
5. Check street/distance on the HUD and the experimental map on the cluster. If
   either waits or fails, retain the plain-language reason and retrieve the saved
   private diagnostics when the central next has connectivity.
