# Experimental CarPlay text on the native HUD

## Current field result — 145, 2026-10-27

The owner saw no native-HUD text during the five-minute Apple Maps test. Capture
completed with 265 callbacks, no errors/drops and 244 text-eligible state-1 payloads.
The native window existed on display4096, layoutAR and navigation enabled. This
proves reception and window creation, not an active policy publication or frame.

Source review identifies a possible suppression path: when the Autolink command
Binder has never been bound and no positive AA evidence exists, the passive poll
may return null continuously. The HUD requires a fresh explicit AA-inactive
observation, so valid CarPlay text remains suppressed in that case. The actual
policy fields were not recorded during this test; do not claim that path as the
established field cause. AA subsequently became active after the CarPlay test,
so the later Binder/stream snapshot cannot establish its state during the test.

There is also a confirmed rendering gap: CarPlay text-only bypasses AR geometry
and uses HERO/FAR, whose renderers draw distance but do not draw the street name.
That gap alone does not explain a completely absent distance. The preliminary,
unpublished v9-only146 did not change these paths. The corrected146 uses a pinned
passive observer, a dedicated text renderer and bounded private publication/frame
diagnostics, as described in [the correction](carplay-146-output-readiness.md).
Physical validation remains pending; the original field cause is not uniquely proven.

## Scope

Preparation on private 74126, retaining 74125 HEV/readability fixes and all AA JSON,
cluster transport, assets and theme contracts. This is native HUD text only, not clean
map video, not a new directions publisher and not Apple Maps identity detection.
The existing native HUD is physical display 4096 (windshield), explicitly separate
from cluster display 3. This code does NOT display CarPlay guidance on the cluster.
That distinction was communicated before publication; the user explicitly chose to
receive 74127 for a windshield-HUD test. Cluster integration remains a separate task.
The TS callback contains no navigation-app identifier. Apple Maps delivered active
payloads in two vehicle sessions; Waze alone only inactive defaults in both tests.

The third 74126 session delivered 109 callbacks, 98 in state 1, five in state 6,
two in state 2 and four in states 0/3. State 2 retained maneuver/road/distance fields:
inactive state must override populated fields. The mixed-app session cannot establish
Waze delivery. No raw roads or private JSONL files are included in Git.

## Opt-in lifecycle and display

Resources > CarPlay navigation diagnostic: first start a capture, then enable
“Texto CarPlay no HUD nativo · experimental”. The toggle is off by default, is not
persisted, and resets for every capture, stop, failure, timeout or process restart.
Markers record the user's on/off actions in the existing private capture.
It depends on the owner's existing native HUD configuration (Navigation or AR).
No layout/resolution is selected automatically and Original layout stays unchanged.
Operate UI only parked or through a passenger; install only parked in P.

Only state 1 with nonblank after-maneuver road and next-distance text, with known
metres/kilometres display unit, can produce text. The road has the same after-maneuver
meaning as the existing AA HUD's street value. No numeric distance conversion,
remaining distance, ETA/time, angle, maneuver IDs, lanes, exits or arrows are guessed.
Native HudOverlayModel/Renderer accept an empty turn token and draw no direction arrow.
The policy tests verify both the empty icon and absence of AR/guide angles.

Shared theme cards instead have a generic arrow fallback for unknown/empty tokens.
Consequently CarPlay output is deliberately not sent to app.navigation.directions or
VirtualTelemetryManager. All themes/bridge keys, types and AA payloads stay identical;
no contract migration is required. Themes do not gain CarPlay TBT in this phase.

## Single native-HUD selection

CarPlayExperimentalHud serializes only native-HUD selection on the main handler.
The AA controller's existing two HUD delivery sites route through it; AA link/map
demand, telemetry Publisher, throttling, JSON snapshot and CLUSTER recovery stay intact.
An active AA Directions always wins without losing any field. CarPlay is fail-closed
while AA availability is unknown, active, timestamped in the future or older than five
seconds. Only a fresh explicit observation that AA is inactive permits CarPlay output;
an inactive Directions payload by itself is not treated as link-state evidence. The
existing 1.5-second AA poll and fresh navigation evidence feed this independent gate
without changing or clearing the AA session. Nested AA lane lists
are copied defensively before posting and when retained. Identical AA updates still
reach HudOverlayController so its received-time/progress behavior is preserved.

Capture ID plus binding generation isolate CarPlay events. Stale callbacks and old
capture stops cannot overwrite a new session. Binding loss/renewal clears CarPlay data;
it does not create or restart the OEM service. Disabled capture still performs its
original diagnostics. Enabling again waits for a fresh callback, not cached route text.

Only one expiry runnable is pending, armed to the earliest CarPlay-data or AA-observation
deadline. CarPlay text expires five seconds after the latest accepted callback or when
the last explicit AA-inactive observation becomes five seconds old. A process-lifetime,
application-context `ACTION_SCREEN_ON` receiver immediately recomputes the policy after
wake using `elapsedRealtime`; it does not wake the central, hold a wake lock or touch the
OEM session. This is a conservative display
safety policy, not an inferred OEM route-end or transport-disconnect timeout. State 0,
2, 3, 4, 5, 6, unknown/null, or invalid text/unit withdraw CarPlay input immediately.
The existing native HUD debounces inactive cards for another five seconds; therefore
a visible card can linger up to about ten seconds after silence. No new arrow is drawn.
Explicit stop/off also follows that existing five-second HUD hide delay. AA active
input is never cleared by a CarPlay stop, arrival, silence or switch-off.

The callback Binder, bounded gate/queue, Parcel reader, file bounds, permission checks,
subscription 7/8 and existing diagnostic stop/rebind lifecycle remain unchanged.
No network upload, exported control, boot startup, wake lock, extra worker thread, raw
street logger, OEM patch, video focus, Surface change or AA session spoofing is added.

## Validation and vehicle test

CarPlayHudPolicyTest covers disabled defaults, required capture, output suppression for
all non-active states, arrival with stale fields, known/unknown units, localized text
preservation, no inferred arrow/ETA, AA full-field priority, unknown/active/stale/future
AA availability, independent AA/data expiry boundaries, long-sleep recomputation,
binding/capture generation isolation, new-session reset, off/on fresh-input requirement,
rebinding cleanup and defensive lane copies. Mac Mini release/R8/lint vital passed
for immutable commit 3fc8ce4f203655f7e0fb0128199793a6aed6b558; 826 tests,
zero failures/errors, one skipped, including all 14 new policy tests. No live
navigation test of this new code is claimed.

After signed build and parked installation: keep AA route off, select the desired native
HUD manually, start diagnostics, enable experiment, then start Apple Maps. Check street/
distance without direction arrow, recalc/arrival/end and silent/off cleanup. Re-enable AA
navigation and verify its full original HUD and map behavior wins; a CP stop must not
clear AA. End capture and verify the experiment defaults off in the next session.
Collect logs privately. A five-minute drive validates neither long-sleep resumption nor
reconnection after a sleep cycle; record those as separate version-identified tests.
Do not call this functional turn arrows or clean-map support.
