# Handoff: PR15 review fixes

## Scope

This change answers three static-review findings on PR15. It does not enable the
native hook, advertise a secondary display, package the experimental native library,
produce an OTA, install anything in the vehicle or merge the pull request.

## Changes

- Secondary decoder submission is tri-state. MediaCodec `TRY_AGAIN_LATER` retains
  the decoder lease and stream generation; fatal errors still retire only secondary.
  The transient packet is dropped because the observed synchronous OEM callback has
  no retry contract, so later keyframe/render recovery remains a field-test gate.
- Experimental CarPlay HUD output now requires a fresh explicit AA-inactive
  observation. AA active, unknown, stale and future-dated states all fail closed.
- The single HUD timer tracks the earliest CarPlay-data or AA-observation expiry.
  An application-context `ACTION_SCREEN_ON` receiver recomputes policy immediately
  after wake without waking the central or changing either projection session.
- Tests cover transient/fatal decoder outcomes, lease retention, AA availability
  states, independent expiry deadlines and recomputation after a long elapsed gap.

## Validation performed on the coordinator

- PASS: native bridge and decoder-lease ASan/UBSan tests.
- PASS: the same bridge and lease tests under ThreadSanitizer.
- PASS: secondary-session and secondary-runtime ASan/UBSan tests.
- PASS: all 15 OEM runtime adapter modes under ASan/UBSan.
- PASS: 20 Python tests discovered; 11 passed and 9 were explicitly skipped because
  private captured binaries/native converter were not supplied to that invocation.
- PASS: `git diff --check`.

Android/JVM/NDK compilation must run from the immutable commit through the authorized
Mac Mini queue. No local Gradle/Android build is permitted or claimed here.

## Required vehicle evidence

Identify the exact app version and record separately: secondary advertisement accepted,
secondary start/configuration, second-stream frames, first rendered frame on display 3
and continuity of principal CarPlay. HUD/navigation callbacks do not prove clean-map
video. Repeat reconnect, actual long sleep/wake and switch to AA; anonymize route,
location and device identity before sharing evidence.
