# CarPlay automatic preparation across vehicle sleep cycles

## Observed failure mode

Preview 147 successfully prepared the bridge after a full Android restart, but the
owner reported that later vehicle restarts required the manual retry action. A
vehicle off/on cycle does not necessarily change the Android kernel `boot_id`.
The OEM `com.ts.carplay` process can instead be recreated with a new PID and
`/proc/<pid>/stat` start time inside the same Android boot.

The 147 controller counted its single automatic attempt by `boot_id`. Therefore a
successful attempt for an earlier OEM process consumed the budget for every later
OEM process in that Android boot. The manual action cleared the persisted attempt
state, which explains why it appeared to be required after each vehicle restart.

## Process-scoped correction

Preview 148 scopes the one-attempt budget to the complete OEM process identity:

- Android kernel `boot_id`;
- exact OEM PID;
- exact `/proc/<pid>/stat` start time.

The root-owned attempt marker and the failure circuit use the same identity. A
replacement OEM process receives one automatic attempt even if Android itself was
not rebooted. The same process still cannot receive a second automatic attempt.
For a warm controlled restart, the marker is moved from the source identity to the
new target identity before either injector is launched.

All existing gates remain mandatory: exact OEM firmware, fresh DCM/Bluetooth and
projection quiescence, visual absence, payload verification, cold-process checks,
resident proof, deadlines, owner identity and revision matching. A changed process
identity does not bypass any of those checks; it only prevents an old process from
spending the new process's attempt budget.

## Validation boundary

Pure tests cover same-process rejection, a replacement process within the same
Android boot, revision-scoped failure circuits and deterministic marker naming.
The portable lifecycle guard verifies that the runtime uses the exact process
identity. Physical confirmation still requires a normal vehicle off/on cycle,
without manually pressing retry, followed by the UI reaching “Bridge preparada
para esta sessão”.
