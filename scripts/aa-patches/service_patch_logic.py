"""
v2.6 Service-APK patch — keep AA's video pipeline alive across video-focus loss
================================================================================

Companion to scripts/aa-patches/patch_logic.py (which targets the AA App APK).
This script patches the AA *Service* APK
(`com.ts.androidauto.projectionservice`, `AndroidAutoService.apk`) — new ground.

Problem
-------
When the car's AVM (Around View Monitor / 360 camera) is triggered — or anything
else grabs system video focus on Display 0 — AA's projection pipeline gets a
"hide video" cascade:

    LinkController.hide()
      -> AapPhoneManager.hide(device)
        -> AapController.hide(device)
          -> AapVideoManager.hideVideo()
              [mIsVideoShowing = false]
              [setVidoeFocus(false)]                 (sic — typo in vendor code)
              [releaseVideo()]      <-- TEARS DOWN THE CODEC
              [StateManager.setVideoShowing(false)]
              [onVideoFocusStatusChanged(NATIVE)]

`releaseVideo()` releases the MediaCodec decoder. Once it's gone, every display
that was rendering AA frames (including the cluster on Display 3) goes blank
because there are no decoded frames coming out. When AVM exits, AA's AAP mode
handler fires, the decoder is recreated, and the SurfaceView re-attaches —
that's the visible "black flash" the user sees on AVM exit.

Fix
---
NOP `AapVideoManager.hideVideo()` — replace the entire method body with
`return-void`. The cascade still gets called (state machines upstream keep
ticking), but the destructive decoder teardown never happens. Codec stays alive
across the focus transition; cluster keeps streaming during AVM; no black flash
on AVM exit because there's nothing to re-attach.

Trade-offs (accepted for first cut)
-----------------------------------
- `mIsVideoShowing` stays true even when video is "hidden" — consumers that
  read this flag may report stale state. Acceptable: most consumers re-poll.
- `StateManager.setVideoShowing(false)` is skipped — audio focus consumers
  won't be told video stopped. AVM is silent, so audio focus shouldn't matter
  in practice; if AA-audio is actively playing, the audio focus framework
  should preempt it independently of this flag.
- `onVideoFocusStatusChanged(NATIVE)` is skipped — UI consumers that listen
  for this callback won't get notified. Likely affects HMI status indicators
  inside the Service APK; observable on-car if anything goes weird.

If any of the above turns out to bite, the smaller-blast-radius alternative
is to NOP only the `invoke-virtual ... releaseVideo()V` call inside
`hideVideo()`, leaving the flag flips intact. That's "Option B" in the design
notes — implement here if Option A causes regressions.

Pipeline
--------
Intermediate artifacts go in `scripts/.build/service/` per the working
conventions documented in `scripts/README.md`.

    1. Disassemble the stock Service APK:
       java -jar tools/apktool_3.0.2.jar d -f -o scripts/.build/service \\
           scripts/aa-patches/stock/AndroidAutoService_stock.apk

    2. Run this script:
       python scripts/aa-patches/service_patch_logic.py

    3. Reassemble + align + sign:
       java -jar tools/apktool_3.0.2.jar b -o \\
           scripts/.build/AndroidAutoService_v26_unsigned.apk \\
           scripts/.build/service
       (then zipalign + apksigner the same way as the App-APK pipeline)

    4. Deploy: see scripts/aa-patches/README.md "Working conventions".
       NOTE: deploying a patched Service APK forces AA to disconnect; the
       phone will need to re-pair. Schedule a test window accordingly.

Build dir is `scripts/.build/service/`. The output base name is
`AndroidAutoService_v26_*` to match the milestone numbering.
"""

import os
import re
import sys

# ---------------------------------------------------------------------------
# Config (per scripts/README.md "Working conventions")
# ---------------------------------------------------------------------------

BUILD_DIR = "scripts/.build/service"
SMALI_TARGET = f"{BUILD_DIR}/smali/com/ts/androidauto/aap/video/AapVideoManager.smali"

# Sentinel comment embedded in the patched method so we can detect prior
# application and skip cleanly on idempotent re-runs.
V2_6_HIDE_NOP_SENTINEL = "# V26_HIDEVIDEO_NOP"


# ---------------------------------------------------------------------------
# Helpers (mirroring patch_logic.py style)
# ---------------------------------------------------------------------------

def read_file(path: str) -> str:
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write_file(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


# ---------------------------------------------------------------------------
# Patch: NOP AapVideoManager.hideVideo()
# ---------------------------------------------------------------------------

def apply_hide_video_nop(content: str):
    """Replace the body of `.method public hideVideo()V` with `return-void`.

    Returns (new_content, applied: bool). Idempotent via sentinel check.
    """
    print("[v2.6] AapVideoManager.hideVideo() -> NOP:")

    if V2_6_HIDE_NOP_SENTINEL in content:
        print("  [SKIP] sentinel present — already patched")
        return content, False

    # Match the whole method block. `hideVideo()V` in this APK declares
    # `.locals 3` (verified via on-disk smali). Pattern captures:
    #   group 1: the `.method public hideVideo()V\n    .locals N` header
    #   group 2: the body up to (but not including) `.end method`
    pattern = re.compile(
        r"(\.method public hideVideo\(\)V\s*\n\s*\.locals \d+)"
        r"(.*?)"
        r"(\.end method)",
        re.DOTALL,
    )

    def replacement(m: re.Match) -> str:
        return (
            f"{m.group(1)}\n\n"
            f"    {V2_6_HIDE_NOP_SENTINEL}\n"
            f"    return-void\n"
            f"{m.group(3)}"
        )

    new_content, n = pattern.subn(replacement, content)
    if n == 0:
        print("  [WARN] hideVideo() signature not found — APK changed?")
        return content, False

    print("  [OK]   hideVideo() body replaced with `return-void`")
    return new_content, True


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def main() -> int:
    print("=" * 64)
    print("  v2.6 Service-APK patch — NOP AapVideoManager.hideVideo()")
    print("=" * 64)

    if not os.path.exists(SMALI_TARGET):
        print()
        print(f"ERROR: smali target not found: {SMALI_TARGET}")
        print()
        print("Did you disassemble the stock Service APK first?")
        print("  java -jar tools/apktool_3.0.2.jar d -f -o "
              f"{BUILD_DIR} "
              "scripts/aa-patches/stock/AndroidAutoService_stock.apk")
        return 1

    content = read_file(SMALI_TARGET)
    new_content, applied = apply_hide_video_nop(content)

    if applied:
        write_file(SMALI_TARGET, new_content)
        print()
        print("Wrote patched smali. Next steps:")
        print(
            f"  1. Reassemble:  java -jar tools/apktool_3.0.2.jar b "
            f"-o scripts/.build/AndroidAutoService_v26_unsigned.apk {BUILD_DIR}"
        )
        print(
            "  2. Align:       & \"C:\\Users\\vanes\\AppData\\Local\\Android\\Sdk\\"
            "build-tools\\36.1.0\\zipalign.exe\" -f 4 "
            "scripts\\.build\\AndroidAutoService_v26_unsigned.apk "
            "scripts\\.build\\AndroidAutoService_v26_aligned.apk"
        )
        print(
            "  3. Sign:        & \"C:\\Users\\vanes\\AppData\\Local\\Android\\Sdk\\"
            "build-tools\\36.1.0\\apksigner.bat\" sign --ks "
            "C:\\Users\\vanes\\.android\\debug.keystore --ks-pass pass:android "
            "--out scripts\\.build\\AndroidAutoService_v26_signed.apk "
            "scripts\\.build\\AndroidAutoService_v26_aligned.apk"
        )
        print(
            "  4. Deploy: see scripts/README.md \"Connecting to the Car\" "
            "section. Note: pushing a patched Service APK forces AA to "
            "disconnect; the phone needs to re-pair."
        )
        return 0

    print()
    print("No changes applied (sentinel detected or signature mismatch).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
