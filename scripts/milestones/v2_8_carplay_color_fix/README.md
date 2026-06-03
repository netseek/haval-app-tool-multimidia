# Milestone: v2.8 CarPlay Color Fix

This milestone implements the CarPlay display resolution negotiation override to fix harsh and desaturated colors, created on **2026-05-27**.

## Changes Integrated

This build resolves the long-standing color depth degradation issue caused by non-standard CarPlay display size negotiations:

1. **Resolution Negotiation Override (Option A - 1920x720):**
   - Intercepts parameters passed to `DisplayContract$Presenter;->show(Landroid/view/Surface;II)Z` in both `CarPlayDisplayActivity.smali` and `CarPlayDisplayFragment$2.smali`.
   - Forces width to `1920` (0x780) and height to `720` (0x2d0) during the connection handshake with the iPhone.
   - This standard ultrawide aspect ratio forces the iPhone to send a high-bitrate video stream with full 24-bit color depth, rather than falling back to low-quality, desaturated fallback modes.
2. **Physical Aspect Ratio Preservation:**
   - Retains the physical layout layout at `2048x720` so the left sidebar dock rail remains cropped, preventing black gaps on the right.
   - Android's native hardware composer handles the slight ~6% horizontal stretch, which is virtually imperceptible while driving.
3. **Focus Preservation Integration:**
   - Includes the native focus retention changes from `v2.7` which intercepts the `LinkStatusModel.hide()` method, blocking `setSurface(null)` calls to safely retain projection status without CPU-heavy polling loops.
