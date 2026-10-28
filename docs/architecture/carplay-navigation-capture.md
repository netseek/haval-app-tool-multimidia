# CarPlay navigation capture — Impulse 74126

## 74126 diagnostic follow-up

Based on private 74125 commit `0fcb11d03671eb89f3689bb55386bb1ea9963386`, retaining its
HEV overlay crash fix and readability updates unchanged. The first real session was
confirmed by the user as Waze: 546.715 seconds, 63 records, two identical inactive/default
payloads, zero reported errors/drops and a manual capture_stop. Binder delivery and the
102-check Android Parcel self-test were observed, but active route delivery was not.
Raw private captures, routes and downloadable reports are not committed here.

Capture now counts null, inactive/pending (states 0/2/3/4), candidate and unconfirmed
payloads separately. Candidates require provisional eligible state 1/5/6 and at least
one nonblank road/distance string or nonnegative maneuver code. This is only a diagnostic
heuristic, not verified maneuver semantics, current route freshness or proof of map video.
Unknown states and eligible states without content remain unconfirmed. Raw values are
unchanged; counters cover the entire session, even across a rebind. Connection changes
clear the latest classification, not historical counts. Guidance records add payloadKind;
heartbeats and stop records add these counts and monotonic durationMs without removing
existing fields. The screen freezes session duration on stop and labels received data
conservatively instead of claiming that every non-null payload is useful guidance.

Added route_start/route_end markers complement Apple Maps/Waze/incident markers. Use
before starting/after ending the phone route; marker times reflect user actions, not
automatically detected route transitions. No export/network endpoint, publisher, OEM
patch, video/Surface/focus operation or automatic retry of a silent live binding is added.

Follow-up Apple Maps vehicle session: 197.647 seconds, 204 records, 177 callbacks,
zero reported errors/drops and a manual stop. Raw states: 0 (8), 1 (158), 3 (4),
5 (2), 6 (5). State-1 maneuvers: 2 (120), 1 (6), 29 (22), 47 (10), with nonempty
roads and distance strings, median interval 1000 ms. A maneuver 11 also appeared
in preparation states. This confirms route data delivery for Apple Maps, not map video
or validated arrow/ETA semantics. The device recorded 74124 in both sessions.
Transient 1/5/0/3/1 transitions occurred; state 0 is not proof of phone disconnection.
The app and connection/start ordering differed between the Waze and Apple Maps tests,
so application-only causality is not established. Next compare Waze under the same
capture-before-connection/route sequence. 74125 can collect the same raw fields; 74126
improves observability only. Installed OEM hashes differ from the static reference,
but this did not prevent Apple Maps delivery and does not explain Waze's inactive data.

## Scope and provenance

The 74122 private source base incorporates Netseek PR 14 at
`15ce75fa07d4cbe4bedd00f308a9ce2e298a6cf0`, followed by an opt-in diagnostic receiver.
The upstream arbiter is still unused at runtime. No new navigation publisher, HUD or
theme contract is introduced. The receiver uses the service descriptor, callback
descriptor, oneway transaction 7/8 and eleven-field order documented in
[the preparation evidence](carplay-tbt-preparation.md). Installed OEM hashes and actual
delivery remain vehicle checks; the APK files are not replaced by this feature.

## Lifecycle and transport

Resources > Diagnóstico de navegação CarPlay opens the capture controls. Starting
creates one worker thread for at most sixty minutes. The default is off, not persisted;
leaving the screen keeps recording while the existing Impulse process runs. There is
no boot startup, receiver, new service, exported entry point, wake lock or network upload.
The existing foreground service owns process lifetime; process death ends capture and
must be followed by a new manual start. Last completed records remain on disk.

The receiver binds explicitly to `com.ts.carplay/com.ts.carplay.CarPlayService` using
flags zero, without requesting auto-creation or UI. It verifies the Binder descriptor
and accepts callbacks only from that package's UID. A separate callback Binder is
created per binding. Registration and removal use the same callback object, never the
music subscription. Sending oneway registration is not proof of connected iPhone or
navigation delivery. Only actual callbacks are counted as such.

Service loss discards the old binding, unlinks death, unbinds even a pending connection,
and retries at 15/30/45/60 seconds. Pending connections time out after fifteen seconds,
observed by a ten-second heartbeat. Connected, silent subscriptions are not recycled.
Silence is recorded as callback age, without guessing a route-end threshold. Old
connection callbacks and already queued events cannot contaminate a new binding.
Stop immediately closes the admission gate; the worker unsubscribes, drains bounded
queued tasks, closes the capture and exits. A restart waits for the old worker to end.

## Parsing and resource bounds

Callbacks enforce the interface token and UID before processing. A maximum of 64
callback/marker jobs may queue; overflow increments a visible loss counter. Parcel
input is consumed on the incoming Binder thread, never retained past `onTransact`.
Payloads are limited to 16 KiB and strings to 2,048 UTF-16 code units. Scalar and string
reads check available bytes, presence must be 0 or 1, trailing bytes and file descriptors
are rejected. Unknown enum numbers, null strings and longs are retained unchanged.
The first malformed callback stops capture rather than guessing another OEM layout.

Before any subscription, a device-side self-test uses actual Android Parcels: null
presence, an eleven-field fixture including Unicode, null and a large long, every
byte-truncated prefix, and trailing data. This is a parser self-test, not proof of
the service schema or real Binder delivery. JVM tests separately cover field order,
typed/truncated inputs, bounded concurrent admission and rotating-file failures.

## Private records and retrieval

Records are JSON Lines in device-protected `noBackupFilesDir/carplay-navigation/`:

`/data/user_de/0/br.com.redesurftank.havalshisuku/no_backup/carplay-navigation/`

Only app-private storage is used; no external storage, automatic backup or general
diagnostic/report logger receives raw streets. JSON escaping prevents route strings
from injecting log lines. Three files (`capture-0.jsonl`, `capture-1.jsonl`,
`capture-2.jsonl`) retain at most 3 MiB total; 0 is newest. Older records roll off.
Retrieve all files promptly through the authorized root Telnet/Tailscale session and
retain the VPS copy privately. Each line has version, UUID session, wall and monotonic
time. Guidance additionally includes receipt times, binding generation and all eleven
raw fields (or explicit null). Heartbeats include counts, drops, connection and age.

Package evidence includes the source APK SHA-256 read from the installed path. A failed
hash read is recorded explicitly and does not establish firmware identity. Markers
`apple_maps`, `waze` and `incident` allow route observations to be correlated. File
write failure ends capture and appears on the screen; data loss counters must be checked
before interpreting gaps as service behavior. A missing capture_stop followed by app
restart indicates an interrupted recording, not necessarily a CarPlay disconnect.

## Vehicle procedure

1. Install the signed OTA while stopped. Open CarPlay normally, then Resources >
   Diagnóstico de navegação CarPlay > Iniciar captura.
2. Confirm `parcel_selftest_pass`, `subscription_sent`, package hashes and increasing
   callbacks. `subscription_sent` alone does not establish a usable guidance feed.
3. Mark Apple Maps, start a short route with turns/roundabout, let a passenger note
   maneuver times, and end the route. Never operate the capture screen while driving.
4. While stopped, mark Waze and repeat. Do not assume both apps provide the same feed.
5. Stop capture and retrieve files before another long recording rotates them out.
   Keep the unit online at arrival for collection. No new display behavior is expected.

Still required after build: actual head-unit self-test, external bind permission,
registration compatibility, real delivery for each iPhone navigation app, unsubscribe
behavior, and reconnection. Only then can maneuver mappings and a HUD experiment be
designed. No live vehicle result is claimed by the source tests or release build.
