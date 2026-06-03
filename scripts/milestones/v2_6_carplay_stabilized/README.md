# Milestone: v2.6 CarPlay Stabilized

This milestone encapsulates the fully stabilized CarPlay patching pipeline for the Haval head unit, created on **2026-05-27**.

## Changes Integrated

This build successfully integrates all **PR 80** projection, resolution, and window configuration parameters, combined with **our active branch's** critical runtime stability fixes:

1. **Focus, State, and Port Preservation (from PR 80):**
   - Integrates the `configureCarPlayProjection` setup protocols and native focus intent broadcast hooks.
   - Leverages improved layout parameters designed to display side-by-side with wide-screen map projections (`.display-mapa`).
2. **IllegalAccessError Crash Prevention (from our branch):**
   - Injects the `mFragment` and `mSurfaceView` public visibility smali overrides, preventing fatal runtime JVM `IllegalAccessError` crashes on start.
3. **Black Screen Recovery on Switch (from our branch):**
   - Hooks into the resume lifecycle to reset `mHasShown` to false, ensuring that rendering surfaces attach properly when swapping displays.
