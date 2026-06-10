# AA Steering-Wheel PREVIOUS — direct LinkCommand IPC (Validated Milestone)

**Date**: 2026-06-10
**Status**: Deployed and validated on car (wheel ◁◁ now goes back / restarts track on Android Auto)
**Commit**: `210df42` (on `feature/new-screen-enhancements-v6`)
**Type**: ⚠️ **Impulse-side fix — NOT an `AndroidAutoApp.apk` smali patch.**

> This is the first milestone that lives entirely in **Impulse app code**
> ([`ServiceManager.java`](../../../app/src/main/java/br/com/redesurftank/havalshisuku/managers/ServiceManager.java)),
> not in a bind-mounted patch APK. There is no APK to sign, snapshot, or
> mount for it — it ships as part of the Impulse APK itself. The existing
> `v1.x` / `v2.x` milestones are all `AndroidAutoApp.apk` display/focus
> patches and use a different (APK-snapshot) format; this one is its own
> track: **steering-wheel media-key routing**.

## Symptom

On the steering wheel, with Android Auto as the active media source:

| Wheel key | Before | After |
|---|---|---|
| ▷▷ NEXT | ✅ worked | ✅ worked |
| ⏯ PLAY/PAUSE | ✅ worked | ✅ worked |
| ◁◁ PREVIOUS | ❌ **did nothing** | ✅ goes back / restarts track |

## Root cause (proven by logs + on-car observation)

The head-unit **MediaCenter** (`IPlayService`) is the broker the wheel keys
go through. For the Android Auto source (`402`) it forwards:

- `playNextBySource(402)` → AA `LinkCommand.next()` ✅
- `pauseMediaBySource(402)` / `resumeMediaBySource(402)` → AA `LinkCommand.pause()`/`play()` ✅
- `playPreviousBySource(402)` → **silently dropped.** Returns `ok=true` but
  forwards *nothing* to AA. The projection logs nothing and the track never moves.

The **patched AA projection is not the culprit** — it handles previous fully
symmetrically to next:

```
LinkController.previous() → AapPhoneManager.previous() → AapController.previous()
    → HandleInputEvent.sendPreviousKey() → InputSource KEYCODE_MEDIA_PREVIOUS
```

(verified in the decompiled service smali — `next`/`previous` paths are
mirror images). The break is entirely in the head unit's MediaCenter, which
we cannot patch.

> **Mount-context caveat (important for any future comparison):** the only
> valid baseline is the **patched** AA mount. Stock AA mounted as-is does not
> give working wheel controls at all, so you cannot diff "patched-previous"
> against "stock-previous" — they are not comparable. Keep this in mind
> before concluding anything from a stock comparison.

## The fix

Bypass MediaCenter for AA-previous only: Impulse binds to the patched AA
projection service as a **client** and invokes its `LinkCommand.previous()`
over Binder directly — the exact entry point NEXT reaches successfully via
MediaCenter. NEXT and PLAY/PAUSE are left on their existing working path
(through MediaCenter); only PREVIOUS-on-AA is rerouted.

### Wire details

| Thing | Value |
|---|---|
| Service component | `com.ts.androidauto.projectionservice/.AndroidAutoService` |
| Bind action | `com.ts.androidauto.action.AndroidAutoService` |
| AIDL descriptor | `com.ts.androidauto.sdk.aidl.LinkCommand` |
| `LinkCommand.next()` txn | `0x18` |
| `LinkCommand.previous()` txn | `0x19` (the one we call) |
| `LinkCommand.play()` / `pause()` txn | `0x1c` / `0x1d` |
| Call shape | `writeInterfaceToken(desc)` → `transact(0x19, …, 0)` (non-oneway, no args) → `readException()` |

### Code (in `ServiceManager.java`, commit `210df42`)

- **Constants / fields** (~L264): `AA_PKG`, `AA_SERVICE`, `AA_ACTION`,
  `AA_LINKCMD_DESC`, `TXN_LINK_NEXT=0x18`, `TXN_LINK_PREVIOUS=0x19`,
  `aaLinkCommandBinder`, `aaLinkConnection`.
- **`handleWheelMediaKey(...)`** (~L898): on AA source, calls
  `ensureAaLinkBound()`; for `!next` on AA, tries `aaLinkPrevious()` and
  returns on success, else falls back to `playSkipBySource` (the old path).
- **`ensureAaLinkBound()`** (~L924): lazy `bindService(..., BIND_AUTO_CREATE)`.
  Only called when AA is **already** the active source, so the projection
  process is alive and bind just attaches — it does **not** start AA from
  nothing or disturb the mount.
- **`aaLinkPrevious()`** (~L949): the raw `transact(0x19)` with interface
  token, returns `true` on success.
- Cleanup path unbinds `aaLinkConnection` and nulls the binder.

## On-car verification

```
# PREVIOUS on AA, after fix:
ServiceManager  [WheelMedia] key next=false currentAudioSource=402 …
ServiceManager  [WheelMedia] AA LinkCommand connected binder=…
ServiceManager  [WheelMedia] AA previous via LinkCommand.previous() ok
AAPLinkController  previous
…HandleInputEvent  sendPreviousKey
```

User confirmed by eye: **"yes, back works."**

### Single-press semantics (matches native / on-screen touch)

A single ◁◁ press does what the native AA touch control does — **restart the
current track if mid-track, go to the previous track if early in the track.**
This is the projection's own behavior; no extra Impulse logic was added or
wanted.

## Reproducing the diagnosis (for future debugging)

The device drops INFO/VERBOSE logs globally — only W/E are captured by
default. Re-enable per-tag to see the projection's own logs:

```powershell
$adb = "C:\Users\vanes\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell setprop log.tag.AAPLinkController VERBOSE
& $adb shell setprop log.tag.AAPHandleInputEvent VERBOSE
# (auto-reset on reboot; harmless to leave set)
```

Relevant projection tags: `AAPLinkController`, `AAPHandleInputEvent`,
`AAPAapController`, `AAPAapPhoneManager`, `AndroidAutoLib` (aggregate).

## Deployment

Nothing special — this is plain Impulse app code. Build + install the
Impulse APK as usual; **no APK mount or AA re-pair is involved** (deploying
the Impulse app does not disconnect AA the way deploying the AA Service APK
does).

```powershell
.\scripts\Deploy-To-Car.ps1    # build + install Impulse (Haval Shisuku)
```

The patched AA projection APK still needs to be mounted as before — that's
what exposes the `LinkCommand` binder this fix calls into. This milestone
does not change or re-mount it.

## Relationship to other milestones

- Independent of the `v1.x` (focus) / `v2.x` (display geometry)
  `AndroidAutoApp.apk` patch tracks — different layer, different APK.
- Depends only on the patched AA **projection service** exposing the
  `LinkCommand` AIDL (already true on the shipped mount).
