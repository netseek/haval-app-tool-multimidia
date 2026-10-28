# CarPlay cold direct arm for preview 149

The owner reported that automatic and manual preparation remained blocked with no
phone physically present. A fresh vehicle boot showed no CarPlay or Android Auto
visual task and no mapped bridge, while the profiled DCM retained a phone row with
active capability `128` and active state `4`.

Static inspection of the exact DCM APK identifies capability `128` as USB Android
Auto and state `4` as connected. The same signature is valid during a real Android
Auto session, so it cannot be globally ignored. It is nevertheless stale in the
captured no-phone boot and prevents the old aggregate quiescence policy from arming
the bridge.

## Narrow admission

Preview 149 separates the two bootstrap actions:

- A cold attach may proceed when the exact OEM process identity is live, all three
  reviewed OEM modules are still absent, the bridge is not mapped, and one fresh
  ActivityManager sample explicitly shows neither CarPlay nor Android Auto. A cold
  process cannot yet own a running projection. Retained DCM rows do not veto this
  non-destructive path.
- A warm restart remains unchanged. It still requires full DCM/Bluetooth quiescence,
  fresh visual absence and the existing coherent safety packet before sending the
  OEM process `SIGTERM`.

The cold path never kills or restarts the OEM process. Before launcher admission and
again before gate release, it rechecks process boot/PID/starttime, cold module state,
exact bridge mapping and explicit visual absence. Its safety packet carries a
short-lived `coldAttach=true` proof tied to the same attempt ticket and process
identity. The root-side release script reads one immutable packet and verifies that
proof, timestamp and identity. The native launcher continues to verify the same
eligible ticket before installing hooks.

If modules are already warm, a connected/unknown projection still blocks automatic
restart. The bridge continues to reject warm interception by design. Navigation
callbacks and experimental HUD remain available through the existing direct Binder
capture, but clean-map video requires a bridge prepared while the process was cold.

## Compatibility and validation

No Android Auto controller, OEM APK, theme contract, cluster geometry, WebView or
CarPlay D0/D3 handoff is changed. This preserves the regression contract's rules
against force-stop, active-session restart and automatic SystemUI intervention.

Unit coverage distinguishes cold attach from warm restart and verifies that the
cold safety packet cannot borrow ordinary quiescence, a wrong identity, a stale
timestamp or a revoked attempt. Android/JVM/Release validation must run only on the
authorized Mac Mini. Physical validation still requires a full central start and
proof that preview 149 reaches READY without a manual reset.
