# CarPlay APK Patching Pipeline

This directory mirrors the Android Auto patch workflow for the TS CarPlay app.

Before changing CarPlay focus, Surface, camera, HVAC, or display-transfer
behavior, read the regression contract:

- `docs/carplay-cluster-regression-contract.md`

## Safety Status

The v2.4 patch currently archived here is unsafe for automatic mounting. In the
2026-05-24 vehicle test it produced a live `CarPlayDisplayActivity`/`SurfaceView`
on display 0 with either no frames or a white/dirty buffer after the native
CarPlay shortcut and controlled restarts. Until a replacement patch is validated,
the main app keeps the stock `TsCarPlayApp.apk` asset and blocks CarPlay patch
install/mount at runtime.

The target app on the Haval head unit is:

- package: `com.ts.carplay.app`
- APK path: `/system/app/TsCarPlayApp/TsCarPlayApp.apk`
- activity: `com.ts.carplay.app.ui.display.view.CarPlayDisplayActivity`

## What the Patch Does

- Expands `CarPlayDisplayActivity` `configChanges` so stack moves/resizes do not recreate the activity.
- Changes `CarPlayDisplayActivity` from `singleInstance` to `standard`, so
  Impulse can create the destination Activity before removing the source
  Activity during display handoff. This avoids a no-Surface gap that leaves
  video black until the phone cable is reconnected.
- Changes the projection `SurfaceView` to `match_parent`, so the native
  CarPlay surface follows the Activity window size while staying full-height.
- The Impulse launcher keeps CarPlay fullscreen on both display 0 and cluster
  3: `0,0,1920,720`. The native patch must also keep Display 3 at `720`;
  mixing `540` and `720` video/surface profiles leaves stale buffers on this
  head unit.
- Forces `SurfaceHolder.setFixedSize()` from the current display target.
  In the supported Haval layout, `CarPlayManager.setSurfaceSize()` receives
  `1920x720` for display 0 and cluster 3.
- Changes the CarPlay video config to read
  `persist.haval.carplay.video.height`. The launcher keeps this property at
  `720` for display 0 and cluster 3.
- When CarPlay is sent back to a secondary display, Impulse recreates the
  CarPlay display Activity instead of preserving the old stack with
  `am display move-stack`; reusing that stack can keep a stale Surface transform.
- Forces `onWindowFocusChanged()` to behave as focused.
- Changes `onPause()` to keep broadcasting `foreground` instead of `background`.
- Makes `requestVideoFocus(1/2)` ignore the stock `finish()` path that tears down the surface.
- Blocks the remaining self-finish paths from `switchFragmentType()` and
  `FINISH_ACTIVITY` broadcasts.
- Blocks the service-side `VideoModel.finishCarPlayActivity()` and keeps
  normal central UI focus changes from calling `changeVideoFocus(0, ...)`,
  so touching the MMI/app list does not blank the CarPlay projection.
- Keeps CarPlay video focus when the native service reports
  `backcamera/backupCamera`, so AVM/RVC can appear on the central display
  without blanking the cluster projection.
- Keeps CarPlay video focus for `com.beantechs.hvac/uiNotification`, so the
  native A/C overlay does not blank the cluster projection while it is open.
- Adds `FLAG_NOT_FOCUSABLE` to `CarPlayDisplayActivity`, so the native CarPlay
  shortcut does not steal global window focus from Display 0 or hide the native
  navigation bar.
- Mirrors the Android Auto focus-preservation behavior: `destroySurface()`,
  `surfaceDestroyed()`, `hide()`, and `onDestroyView()` no longer release or
  detach the Surface, so a camera/AC overlay can leave the last CarPlay frame
  static instead of forcing a renderer teardown/recreate.
- `DETACH_RENDER` is not part of the normal display-transfer path anymore;
  transfers preserve the current Surface and rely on the new Activity to
  reattach with `REFRESH_RENDER`. Calling `CarPlayManager.setSurface(null)` can
  leave video black until the phone cable is reconnected.
- Adds an `onConfigurationChanged()` hook that requests a relayout.

## Build

Run from the repository root:

```bash
mkdir -p build_carplay

./tools/headunit-dev/headunit.sh pull-file \
  /system/app/TsCarPlayApp/TsCarPlayApp.apk TsCarPlayApp.apk

java -jar tools/apktool_3.0.2.jar d -f -o build_carplay/ts-app \
  tools/headunit-dev/output/pulled-files/TsCarPlayApp.apk

python3 scripts/carplay-patches/patch_logic.py

java -jar tools/apktool_3.0.2.jar b \
  -o build_carplay/TsCarPlayApp_unsigned.apk build_carplay/ts-app

zipalign -f 4 build_carplay/TsCarPlayApp_unsigned.apk \
  build_carplay/TsCarPlayApp_aligned.apk

apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out build_carplay/TsCarPlayApp_signed.apk \
  build_carplay/TsCarPlayApp_aligned.apk
```

For the in-app mount flow, copy the signed APK to:

```text
app/src/main/assets/carplay_patches/TsCarPlayApp.apk
```

Then rebuild/deploy the Haval app and use the CarPlay patch card in the
Install Apps screen.

## Direct Vehicle Deploy

For rapid testing, deploy the signed APK directly. This starts a local HTTP
server and asks the head unit to download the APK with `curl`, avoiding the
slow telnet/base64 path:

```bash
python3 scripts/carplay-patches/deploy_to_car.py \
  --apk build_carplay/TsCarPlayApp_signed.apk
```
