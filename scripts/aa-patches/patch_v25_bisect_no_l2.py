"""
Bisect variant of patch_logic.py — applies all v2.x patches EXCEPT L2.

Purpose
-------
Task #5/#6: wheel media keys regression on v2.5 patched AA App APK.

Follow-up to patch_v25_bisect_no_l1l2.py. That earlier bisect removed BOTH
L1 (force `mIsOnPause = false`) and L2 (force `onWindowFocusChanged(true)`)
and AA failed to *connect* at all — an ambiguous result, because we could
not tell whether L1, L2, the combination, or a transient glitch caused it.

Hypothesis refinement:
    - L1 is a lifecycle flag — most likely what AA's connection/foreground
      logic depends on. KEEP it so AA still connects.
    - L2 forces AapActivity.onWindowFocusChanged(hasFocus=true) on every
      call. That makes the activity always believe it has window focus,
      which can desync it from the system input dispatcher that
      `injectKeyCodeToSystem` (wheel keyCode 87) targets. PRIME suspect for
      the wheel-key regression — REMOVE only this one.

This script applies:
    L1  onPause force mIsOnPause = false   (KEPT — connection stability)
    L3  hideSurface NOP
    L4  releaseSurface NOP
    L5  onPauseSetVisibility NOP
    L6  finish() block
    L7  setDisplayParams window-bounds prologue (v2.0 / v2.5)
    L8  onConfigurationChanged injection
    L9  AndroidManifest configChanges expansion
    +   updateDisplayParams passthrough (v2.1)
    +   setDisplayParams crop padding bars (v2.2)
    +   setDisplayParams xcrop sidebar (v2.4)
    +   setDisplayParams diag (v2.5)

And SKIPS:
    L2  onWindowFocusChanged force hasFocus = true

Build dir: scripts/.build/app/ (per scripts/README.md "Working conventions").
Output goes to scripts/.build/AndroidAutoApp_v25_bisect_no_l2_*.apk.

Imports the unchanged patch helpers from patch_logic.py so we stay in sync
with the canonical patch definitions for L1, L3-L9.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import patch_logic as pl


BUILD_DIR = "scripts/.build/app"
SMALI_BASE = f"{BUILD_DIR}/smali/com/ts/androidauto/app/display"
AAP_ACTIVITY = f"{SMALI_BASE}/AapActivity.smali"
MANIFEST = f"{BUILD_DIR}/AndroidManifest.xml"


def apply_v1_focus_patches_no_l2(content):
    """Same as pl.apply_v1_focus_patches but skips L2 (onWindowFocusChanged).
    L1 and L3-L6 are applied identically to upstream."""
    print("[v1.x BISECT] Focus / lifecycle stability (L2 SKIPPED):")
    count = 0

    # L1 onPause: force mIsOnPause = false (KEPT)
    if pl.V2_ONPAUSE_SENTINEL in content:
        print("  [SKIP] L1 onPause: force mIsOnPause = false (already applied)")
    else:
        content, ok = pl.patch_regex(
            content,
            r"(\.method protected onPause\(\)V.*?invoke-super \{p0\}, Landroid/app/Activity;->onPause\(\)V)",
            r"\1\n\n    " + pl.V2_ONPAUSE_SENTINEL + "\n    const/4 v0, 0x0\n\n"
            r"    iput-boolean v0, p0, "
            r"Lcom/ts/androidauto/app/display/AapActivity;->mIsOnPause:Z\n",
            "L1 onPause: force mIsOnPause = false",
        )
        count += int(ok)

    print("  [SKIP] L2 onWindowFocusChanged force hasFocus=true  (bisect: intentionally skipped)")

    # L3 hideSurface NOP
    content, ok = pl.patch_regex(
        content,
        r"(\.method private hideSurface\(\)V\s*\.locals \d+)\s*\n(.*?)\.end method",
        r"\1\n\n    return-void\n.end method",
        "L3 hideSurface: NOP",
    )
    count += int(ok)

    # L4 releaseSurface NOP
    content, ok = pl.patch_regex(
        content,
        r"(\.method private releaseSurface\(\)V\s*\.locals \d+)\s*\n(.*?)\.end method",
        r"\1\n\n    return-void\n.end method",
        "L4 releaseSurface: NOP",
    )
    count += int(ok)

    # L5 onPauseSetVisibility NOP
    content, ok = pl.patch_regex(
        content,
        r"(\.method private onPauseSetVisibility\(\)V\s*\.locals \d+)\s*\n(.*?)\.end method",
        r"\1\n\n    return-void\n.end method",
        "L5 onPauseSetVisibility: NOP",
    )
    count += int(ok)

    # L6 block finish() self-termination (sentinel-guarded same as upstream)
    if pl.V2_FINISH_SENTINEL in content:
        print("  [SKIP] L6 finish() calls blocked (already applied)")
    else:
        content, ok = pl.patch_direct(
            content,
            "invoke-virtual {p0}, Lcom/ts/androidauto/app/display/AapActivity;->finish()V",
            f"{pl.V2_FINISH_SENTINEL} invoke-virtual {{p0}}, "
            "Lcom/ts/androidauto/app/display/AapActivity;->finish()V",
            "L6 finish() calls: blocked",
        )
        count += int(ok)

    return content, count


def main():
    print("=" * 62)
    print("  v2.5 BISECT (no L2) — AA App wheel-key regression test")
    print("=" * 62)

    if not os.path.exists(AAP_ACTIVITY):
        print(f"\nERROR: AapActivity.smali not found at {AAP_ACTIVITY}")
        print("\nDisassemble first:")
        print(
            f"  java -jar tools/apktool_3.0.2.jar d -f -o {BUILD_DIR} "
            "scripts/aa-patches/stock/AndroidAutoApp_stock.apk"
        )
        sys.exit(1)

    content = pl.read_file(AAP_ACTIVITY)
    total = 0

    # v1 layers (L2 skipped, L1 + L3-L6 applied)
    content, n = apply_v1_focus_patches_no_l2(content)
    total += n

    # v2.x layers — unchanged from patch_logic
    content, n = pl.apply_v2_setdisplayparams_patch(content)
    total += n

    content, n = pl.apply_v2_1_updatedp_patch(content)
    total += n

    content, n = pl.apply_v2_1_onconfig_patch(content)
    total += n

    content, n = pl.apply_v2_2_crop_patch(content)
    total += n

    content, n = pl.apply_v2_4_xcrop_patch(content)
    total += n

    content, n = pl.apply_v25_diag_patch(content)
    total += n

    pl.write_file(AAP_ACTIVITY, content)

    total += pl.apply_v2_1_manifest_patch(MANIFEST)

    print()
    print(f"Done. {total} change(s) applied.")
    if total == 0:
        print("INFO: All patches were already present.")
    print()
    print("Next steps:")
    print(
        f"  1. Reassemble:  java -jar tools/apktool_3.0.2.jar b -o "
        f"scripts/.build/AndroidAutoApp_v25_bisect_no_l2_unsigned.apk {BUILD_DIR}"
    )
    print(
        '  2. Align:       & "C:\\Users\\vanes\\AppData\\Local\\Android\\Sdk\\'
        'build-tools\\36.1.0\\zipalign.exe" -f 4 '
        "scripts\\.build\\AndroidAutoApp_v25_bisect_no_l2_unsigned.apk "
        "scripts\\.build\\AndroidAutoApp_v25_bisect_no_l2_aligned.apk"
    )
    print(
        '  3. Sign:        & "C:\\Users\\vanes\\AppData\\Local\\Android\\Sdk\\'
        'build-tools\\36.1.0\\apksigner.bat" sign --ks '
        "C:\\Users\\vanes\\.android\\debug.keystore --ks-pass pass:android "
        "--out scripts\\.build\\AndroidAutoApp_v25_bisect_no_l2_signed.apk "
        "scripts\\.build\\AndroidAutoApp_v25_bisect_no_l2_aligned.apk"
    )


if __name__ == "__main__":
    main()
