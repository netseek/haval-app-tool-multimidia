# Classic Theme Layout & Visual Architecture

This document describes the design, structure, and compilation workflow for the classic themes (`basic` and `basic-light`) within the Haval Shisuku / Impulse dashboard widgets.

## Reorganization History

Historically, the custom dark and light themes were named `air-control` and `air-control-light` (referenced internally as `Basico` and `Light`). In the recent architecture revision, these were relocated and clean-coded into:
- `basic` (Dark Mode classic theme)
- `basic-light` (Light Mode classic theme)
- `default` (Sporty layout theme)

During this transition, the classic themes lost their top status bar backgrounds and the center-top status container (displaying the clock, current gear, and EV mode).

---

## Root Cause & Bug Restoration

### 1. Basic (Dark Theme) Status Bar Recovery
- **Issue**: Sporty-layout specific styling overrides (`.display-esportivo`) were carried over from the legacy `air-control` implementation.
- **Root Cause**: The dark stylesheet (`basic/src/styles/night.style.css`) contained aggressive negation overrides (`#app:not(.display-esportivo)`) that forcefully hid the `.dashboard-top-center` container (`display: none !important`) and stripped `.mask-top-bar` and `.dashboard-bottom-gauges` of their linear gradients (`background-image: none !important`).
- **Fix**: Removed the negations for top/bottom bar backgrounds and the clock/gear/mode containers, while retaining the hard-stops for actual sporty-only gauges (`.dashboard-speed-esportivo-widget` and `.dashboard-sport-fixed-overlay`) so they remain hidden in classic mode.

### 2. Basic-Light (Light Theme) Visual Depth Restoration
- **Issue**: The status bars (`.mask-top-bar` and `.mask-bottom-bar`) were visually flat or perceived as missing, blending completely with the background.
- **Root Cause**: The light stylesheet (`basic-light/src/styles/light.style.css`) used solid white gradients (`#ffffff` and `rgba(255,255,255,0.5)`) and solid white shadows (`var(--mask-circle-bg) = #ffffff`) over a pure white page background, resulting in zero contrast.
- **Fix**: Updated both status bars with elegant translucent overlay gradients and soft, premium shadows (`box-shadow: 0 4px 25px rgba(0, 0, 0, 0.06)`) to establish visual separation and a premium glassmorphic feel on the white backdrop.

---

## Structural Integrity

The core UI structure and layout initialization remains identical between the two classic themes, shared via:
- `basic/src/core/components/display/mask.js` (Background layer containing `.mask-top-bar`)
- `basic/src/core/components/dashboardInfo.js` (Foreground layer containing `.dashboard-top-center`)
- `basic/src/core/main.js` (Layout tree compilation)

The top-center widgets are injected into the DOM container at layout time, ensuring they float above the background mask overlay layers (`z-index: 140` vs. `z-index: 1`).

---

## Compilation & Packaging Workflow

All custom themes are built as unified, self-contained single-page HTML files (all CSS/JS inlined with dynamic assets Base64-encoded).

### 1. Build and Compile
From each theme directory (`cluster-widgets/basic` or `cluster-widgets/basic-light`), execute the Parcel compiler and inliner:
```powershell
npm run build
```
This automatically:
- Builds the optimized assets into `./dist/`.
- Runs `./inline.js` to parse and embed all external scripts, stylesheets, and assets.
- Copies the final unified file directly to the Android resources directory (`app/src/main/res/raw/app.html` or `app_light.html`).

### 2. Theme Package Distribution
For runtime theme discovery, the unified output must be manually placed inside the `Themes` package directory:
- **Basic (Dark)**: Copy `basic/dist/app.html` to `cluster-widgets/Themes/Basic/index.html`.
- **Basic Light**: Copy `basic-light/dist/app_light.html` to `cluster-widgets/Themes/BasicLight/app_light.html`.
- **Version Bump**: Increment the `<version>` tag in `theme.xml` by `0.0.1` (e.g., to `1.0.4`) to trigger the app's internal cache-refresh and update system.

---

## Odometer & Maintenance Warning Y-Axis Displacement

To ensure optimal layout balance when showing either a single line (odometer value only) or two lines (odometer value + maintenance/revision warnings), the widget uses a dynamic class-toggling mechanism.

### 1. JavaScript State Integration
In `odometerInfo.js`, the widget updates its container's class list dynamically based on the active display criteria:
- **Single-Line Mode**: Applied when only the odometer is active. The wrapper is assigned `.odometer-text-wrapper.single-line`.
- **Double-Line Mode**: Applied during startup flashing or active maintenance warnings. The wrapper is assigned `.odometer-text-wrapper.double-line`.

### 2. Styling and Y-Axis Displacement

The stylesheets (`night.style.css` / `light.style.css`) govern the exact vertical position via CSS transforms tailored for each theme's contrast and display parameters:

- **Basic (Dark Mode - `night.style.css`)**:
  - `.odometer-text-wrapper.single-line`: Uses `transform: translateY(-6px);` with larger text sizing to center the odometer perfectly.
  - `.odometer-text-wrapper.double-line`: Uses `transform: translateY(0px);` to balance the two text lines nicely.

- **Basic-Light (Light Mode - `light.style.css`)**:
  - `.odometer-text-wrapper.single-line`: Uses `transform: translateY(6px);` to center the single-line odometer.
  - `.odometer-text-wrapper.double-line`: Uses `transform: translateY(12px);` to shift the two lines, ensuring perfect clearance and alignment with surrounding visual details.

