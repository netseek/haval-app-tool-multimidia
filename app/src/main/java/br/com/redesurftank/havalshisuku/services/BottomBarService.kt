package br.com.redesurftank.havalshisuku.services

import android.content.Context
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import br.com.redesurftank.havalshisuku.R
import br.com.redesurftank.havalshisuku.broadcastReceivers.AndroidAutoMonitorReceiver
import br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
import br.com.redesurftank.havalshisuku.models.BottomBarState
import br.com.redesurftank.havalshisuku.models.SharedPreferencesKeys
import br.com.redesurftank.havalshisuku.ui.components.BottomBarContent
import br.com.redesurftank.havalshisuku.ui.components.BottomBarMenus
import br.com.redesurftank.havalshisuku.ui.theme.HavalShisukuTheme
import br.com.redesurftank.havalshisuku.utils.ShizukuUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class BottomBarService : LifecycleService() {

    private var mWindowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var menuComposeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuWindowAdded = false

    private var monitoringJob: Job? = null
    private var autoHideJob: Job? = null
    private var lastPackage: String? = null

    data class BarSettings(val overscan: Int, val yOffset: Int)

    // Hardcoded overrides for density-aware apps that auto-scale overscan
    // These values are in DP and will be scaled by density
    /*
    private val APP_OVERRIDES =
            mapOf(
                    "com.google.android.youtube" to BarSettings(0, 0),
                    "com.google.android.apps.maps" to BarSettings(0, 60),
                    "com.google.android.apps.youtube.music" to BarSettings(0, 0),
                    "com.google.android.apps.messaging" to BarSettings(60, 0),
                    "deezer.android.app" to BarSettings(60, 0),
            )
    */

    private val IGNORE_PACKAGES =
            setOf<String>(
                    // "com.beantechs.applist",
                    // "com.beantechs.mediacenter"
                    )

    private val BOTTOM_BAR_BASE_HEIGHT_DP = 60f
    private val REFERENCE_OVERSCAN = 20

    override fun onCreate() {
        android.util.Log.e("BottomBarService", "SERVICE ONCREATE - STARTING")
        super.onCreate()

        // Initialize state from SharedPreferences
        val prefs =
                br.com.redesurftank.App.getDeviceProtectedContext()
                        .getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)
        BottomBarState.autoHideEnabled =
                prefs.getBoolean(SharedPreferencesKeys.BOTTOM_BAR_AUTO_HIDE.key, false)

        BottomBarState.isVisible = true

        // Initial check for Frida status
        updateFridaStatus(prefs)

        showBottomBar()
        observeMenuState()
        observeVisibility()
        observeAutoHide()
        registerUpdateReceiver()
        startDynamicOverscanMonitoring()
        ensureAccessibilityServiceEnabled()
        startAppMonitoring()

        // Initial timer start
        resetAutoHideTimer()

        // Start Android Auto Broadcast Discovery and Monitoring (Phase 1)
        lifecycleScope.launch(Dispatchers.IO) {
            // Wait for Shizuku to be ready
            var retry = 0
            while (!ShizukuUtils.isShizukuAvailable() && retry < 20) {
                delay(1000)
                retry++
            }

            val actions = mutableListOf<String>()
            if (ShizukuUtils.isShizukuAvailable()) {
                actions.addAll(DisplayAppLauncher.discoverAndroidAutoBroadcasts())
            } else {
                Log.e(
                        "AA_DISCOVERY",
                        "Shizuku not available after 20s, using fallback actions only"
                )
            }

            // Always include these critical ones just in case discovery fails or Shizuku is missing
            if (!actions.contains("ts.car.androidauto.view_state"))
                    actions.add("ts.car.androidauto.view_state")
            if (!actions.contains("com.ts.androidauto.adapter.resource.RECEIVER_CLICK_ACTION"))
                    actions.add("com.ts.androidauto.adapter.resource.RECEIVER_CLICK_ACTION")

            if (actions.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val filter = IntentFilter()
                    actions.forEach { filter.addAction(it) }
                    registerReceiver(AndroidAutoMonitorReceiver(), filter)
                    Log.w("AA_MONITOR", "Registered monitor for ${actions.size} actions")
                }
            }

            startBroadcastSniffer()
        }
    }

    private var isAppMonitoringActive = false
    private var appMonitorJob: Job? = null

    private fun startAppMonitoring() {
        if (appMonitorJob?.isActive == true) return
        isAppMonitoringActive = true

        appMonitorJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    Log.w("BottomBarService", "App monitoring watchdog STARTED")
                    var cycleCount = 0
                    while (isActive && isAppMonitoringActive) {
                        cycleCount++

                        if (cycleCount % 6 == 0) { // Every 60 seconds
                            Log.i("BottomBarService", "WATCHDOG HEARTBEAT #$cycleCount")
                        }

                        if (DisplayAppLauncher.hasAnyForceFocusApp()) {
                            try {
                                val aaPackage = "com.ts.androidauto.app"
                                val aaTask = DisplayAppLauncher.findTaskForPackage(aaPackage)

                                if (aaTask != null &&
                                                (aaTask.displayId == 3 || aaTask.displayId == 1)
                                ) {
                                    val topApp =
                                            DisplayAppLauncher.getTopPackageOnDisplay(
                                                    aaTask.displayId
                                            )
                                    val isUserApp =
                                            DisplayAppLauncher.getAllConfigs().any {
                                                it.packageName == topApp
                                            }

                                    if (topApp != aaPackage && !isUserApp) {
                                        // AA is covered by a system app — send full poke
                                        Log.w(
                                                "BottomBarService",
                                                "Watchdog: app ($topApp) covering AA on display ${aaTask.displayId}. Restoring..."
                                        )
                                        DisplayAppLauncher.syncInterconnectionFocus(
                                                "polling_covered"
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("BottomBarService", "Error in watchdog polling", e)
                            }
                        }
                        delay(10000) // Poll every 10 seconds — coverage detection only
                    }
                }
    }

    private fun startBroadcastSniffer() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Sniff for the first 60 seconds of service life
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 60000) {
                try {
                    val output =
                            ShizukuUtils.runCommandAndGetOutput(
                                    arrayOf(
                                            "sh",
                                            "-c",
                                            "dumpsys activity broadcasts | grep -i 'androidauto\\|mirror\\|video.*focus\\|projection'"
                                    )
                            )
                    if (output.isNotBlank()) {
                        Log.d("AA_SNIFFER", "Recent relevant broadcasts:\n$output")
                    }
                } catch (e: Exception) {
                    Log.e("AA_SNIFFER", "Error sniffing broadcasts", e)
                }
                delay(10000) // Every 10 seconds
            }
            Log.w("AA_SNIFFER", "Broadcast discovery sniffer finished")
        }
    }

    private fun registerUpdateReceiver() {
        val filter =
                android.content.IntentFilter("br.com.redesurftank.havalshisuku.UPDATE_BAR_POSITION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    private val updateReceiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    val overscan = intent?.getIntExtra("overscan", -1) ?: -1
                    val offset = intent?.getIntExtra("offset", -101) ?: -101

                    if (overscan != -1 || offset != -101) {
                        // Real-time update from Apply button
                        val currentPackage = BottomBarState.currentPackage
                        if (currentPackage.isNotEmpty()) {
                            val settings =
                                    BarSettings(
                                            overscan =
                                                    if (overscan != -1) overscan
                                                    else (currentAppSettings?.overscan ?: 0),
                                            yOffset =
                                                    if (offset != -101) offset
                                                    else (currentAppSettings?.yOffset ?: 0)
                                    )
                            currentAppSettings = settings
                            applyAppSettings(settings)
                        }
                    } else {
                        // Reload from SharedPreferences (Save button or generic refresh)
                        lastPackage = null // Force reload in monitoring loop
                    }
                }
            }

    private fun observeAutoHide() {
        lifecycleScope.launch {
            // Reset timer on any state change that might indicate activity
            snapshotFlow {
                listOf(
                        BottomBarState.isVisible,
                        BottomBarState.isMenuExpanded,
                        BottomBarState.isSettingsMenuExpanded,
                        BottomBarState.isOverrideMenuExpanded
                )
            }
                    .collectLatest { resetAutoHideTimer() }
        }
    }

    fun resetAutoHideTimer() {
        autoHideJob?.cancel()
        if (!BottomBarState.autoHideEnabled || !BottomBarState.isVisible) return

        autoHideJob =
                lifecycleScope.launch {
                    delay(30000) // 30 seconds
                    if (BottomBarState.isVisible &&
                                    !BottomBarState.isMenuExpanded &&
                                    !BottomBarState.isSettingsMenuExpanded &&
                                    !BottomBarState.isOverrideMenuExpanded
                    ) {
                        BottomBarState.isVisible = false
                    }
                }
    }

    private fun ensureAccessibilityServiceEnabled() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Wait for Shizuku
                var retry = 0
                while (!ShizukuUtils.isShizukuAvailable() && retry < 20) {
                    delay(1000)
                    retry++
                }

                if (!ShizukuUtils.isShizukuAvailable()) return@launch

                val currentServices =
                        ShizukuUtils.runCommandAndGetOutput(
                                        arrayOf(
                                                "sh",
                                                "-c",
                                                "settings get secure enabled_accessibility_services"
                                        )
                                )
                                .trim()
                val ourService =
                        "${packageName}/br.com.redesurftank.havalshisuku.services.AccessibilityService"

                if (!currentServices.contains(ourService)) {
                    val newServices =
                            if (currentServices == "null" || currentServices.isEmpty()) {
                                ourService
                            } else {
                                "$currentServices:$ourService"
                            }
                    ShizukuUtils.runCommandAndGetOutput(
                            arrayOf(
                                    "sh",
                                    "-c",
                                    "settings put secure enabled_accessibility_services '$newServices'"
                            )
                    )
                    ShizukuUtils.runCommandAndGetOutput(
                            arrayOf("sh", "-c", "settings put secure accessibility_enabled 1")
                    )
                    Log.w("AccessibilityService", "Auto-enabled accessibility service via Shizuku")
                } else {
                    Log.d("AccessibilityService", "Accessibility service is already enabled")
                }
            } catch (e: Exception) {
                Log.e("AccessibilityService", "Failed to auto-enable accessibility service", e)
            }
        }
    }

    private var currentAppSettings: BarSettings? = null

    private fun startDynamicOverscanMonitoring() {
        monitoringJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            val currentPackage = getTopPackageOnDisplay(0)
                            if (currentPackage != null) {
                                withContext(Dispatchers.Main) {
                                    if (BottomBarState.currentPackage != currentPackage) {
                                        BottomBarState.currentPackage = currentPackage
                                        // Auto-select the current app if it's not a launcher or in the ignore list
                                        if (!IGNORE_PACKAGES.contains(currentPackage) &&
                                                        !isLauncher(currentPackage)
                                        ) {
                                            BottomBarState.selectedPackage = currentPackage
                                        }
                                    }
                                }
                            } else {
                                // If we can't find Display 0 package, use tool package as fallback
                                // to apply default overscan
                                withContext(Dispatchers.Main) {
                                    BottomBarState.currentPackage =
                                            this@BottomBarService.packageName
                                }
                            }

                            // Background Cleanup: Remove apps that are no longer running from the
                            // restored set
                            if (BottomBarState.restoredApps.isNotEmpty()) {
                                val stackList =
                                        ShizukuUtils.runCommandAndGetOutput(
                                                arrayOf("am", "stack", "list")
                                        )
                                val missingApps =
                                        BottomBarState.restoredApps.filter { pkg ->
                                            !stackList.contains(pkg)
                                        }
                                if (missingApps.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        BottomBarState.restoredApps.removeAll(missingApps)
                                    }
                                }
                            }

                            val prefs =
                                    br.com.redesurftank.App.getDeviceProtectedContext()
                                            .getSharedPreferences(
                                                    "haval_prefs",
                                                    Context.MODE_PRIVATE
                                            )

                            if (currentPackage != null && currentPackage != lastPackage) {
                                lastPackage = currentPackage

                                // Default overscan is back to REFERENCE_OVERSCAN (60)
                                val storedDefault =
                                        prefs.getInt(
                                                SharedPreferencesKeys.PERSISTENT_BOTTOM_BAR_OVERSCAN
                                                        .key,
                                                REFERENCE_OVERSCAN
                                        )

                                // Also update autoHideEnabled from prefs
                                withContext(Dispatchers.Main) {
                                    BottomBarState.autoHideEnabled =
                                            prefs.getBoolean(
                                                    SharedPreferencesKeys.BOTTOM_BAR_AUTO_HIDE.key,
                                                    false
                                            )
                                }

                                val settings = getSettingsForPackage(currentPackage, storedDefault)
                                currentAppSettings = settings
                                applyAppSettings(settings)
                            }

                            // Update Frida status reactive to switches
                            updateFridaStatus(prefs)
                        } catch (e: Exception) {
                            Log.e("BottomBarService", "Error in monitoring loop", e)
                        }
                        delay(1000)
                    }
                }
    }

    private fun updateFridaStatus(prefs: android.content.SharedPreferences) {
        val hooksEnabled = prefs.getBoolean(SharedPreferencesKeys.ENABLE_FRIDA_HOOKS.key, false)

        // Only require the main Frida switch to be enabled as requested
        val switchesOn = hooksEnabled

        lifecycleScope.launch(Dispatchers.IO) {
            // UI shows Frida menu if main switch is ON
            withContext(Dispatchers.Main) { BottomBarState.isFridaRunning = switchesOn }
        }
    }

    private fun getTopPackageOnDisplay(displayId: Int): String? {
        return DisplayAppLauncher.getTopPackageOnDisplay(displayId)
    }

    private fun isLauncher(packageName: String): Boolean {
        val intent =
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME)
        val launchers = packageManager.queryIntentActivities(intent, 0)
        return launchers.any { it.activityInfo.packageName == packageName }
    }

    private fun getSettingsForPackage(packageName: String, defaultOverscan: Int): BarSettings {
        val prefs =
                br.com.redesurftank.App.getDeviceProtectedContext()
                        .getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)

        val dynamicOverridesJson =
                prefs.getString(SharedPreferencesKeys.BOTTOM_BAR_OVERRIDES.key, null)
        val dynamicOverrides: Map<String, BarSettings> =
                if (dynamicOverridesJson != null) {
                    try {
                        val type = object : TypeToken<Map<String, BarSettings>>() {}.type
                        Gson().fromJson(dynamicOverridesJson, type)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

        // Priority: Dynamic Overrides -> Default
        return dynamicOverrides[packageName]
                // ?: APP_OVERRIDES[packageName]
                ?: BarSettings(overscan = defaultOverscan, yOffset = 0)
    }

    private fun applyAppSettings(settings: BarSettings) {
        val wm = mWindowManager ?: return
        val cv = composeView ?: return
        val lp = params ?: return
        val density = this.resources.displayMetrics.density

        if (!BottomBarState.isVisible) {
            Log.d(
                    "BottomBarService",
                    "Bottom bar hidden, ignoring dynamic overscan request: ${settings.overscan}"
            )
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuUtils.runCommandAndGetOutput(arrayOf("wm", "overscan", "0,0,0,0"))
            }
            return
        }

        val isRestored = lastPackage != null && BottomBarState.restoredApps.contains(lastPackage)
        val multiplier = if (isRestored) 3.0f else 1.0f

        val overscanValueRaw = settings.overscan
        val overscanValuePx = (overscanValueRaw.toFloat() * density * multiplier).toInt()
        val yOffsetPx = (settings.yOffset * density).toInt()

        Log.w(
                "BottomBarService",
                "[OVERSCAN_SYNC] App: $lastPackage | Overscan: ${overscanValueRaw}dp(${overscanValuePx}px) | Offset: ${settings.yOffset}dp(${yOffsetPx}px) | Visible: ${BottomBarState.isVisible}"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            ShizukuUtils.runCommandAndGetOutput(arrayOf("wm", "overscan", "0,0,0,$overscanValuePx"))
            withContext(Dispatchers.Main) {
                // Apply custom yOffset relative to the logical bottom (where y=0 is the edge)
                lp.y = yOffsetPx
                try {
                    wm.updateViewLayout(cv, lp)
                } catch (e: Exception) {
                    Log.e(
                            "BottomBarService",
                            "Error updating window layout during app settings change",
                            e
                    )
                }
            }
        }
    }

    private fun observeVisibility() {
        lifecycleScope.launch {
            snapshotFlow { BottomBarState.isVisible }.collectLatest { visible ->
                updateBarVisibility(visible)
                // Force recompute touchable regions
                composeView?.requestLayout()
                menuComposeView?.requestLayout()
            }
        }
        // Periodic invalidation to keep touchable regions in sync
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                composeView?.requestLayout()
                menuComposeView?.requestLayout()
            }
        }
    }

    private fun observeMenuState() {
        lifecycleScope.launch {
            snapshotFlow {
                BottomBarState.isMenuExpanded ||
                        BottomBarState.isSettingsMenuExpanded ||
                        BottomBarState.isOverrideMenuExpanded
            }
                    .collectLatest { expanded ->
                        updateMenuWindow(expanded)
                        // Force recompute touchable regions when menu state changes
                        composeView?.requestLayout()
                        menuComposeView?.requestLayout()
                    }
        }
    }

    private fun updateBarVisibility(visible: Boolean) {
        val wm = mWindowManager ?: return
        val cv = composeView ?: return
        val lp = params ?: return

        val density = resources.displayMetrics.density

        lifecycleScope.launch(Dispatchers.IO) {
            val overscanCmd: Array<String>
            if (visible) {
                val settings =
                        currentAppSettings
                                ?: run {
                                    val prefs =
                                            br.com.redesurftank.App.getDeviceProtectedContext()
                                                    .getSharedPreferences(
                                                            "haval_prefs",
                                                            Context.MODE_PRIVATE
                                                    )
                                    val storedDefault =
                                            prefs.getInt(
                                                    SharedPreferencesKeys
                                                            .PERSISTENT_BOTTOM_BAR_OVERSCAN
                                                            .key,
                                                    REFERENCE_OVERSCAN
                                            )
                                    BarSettings(overscan = storedDefault, yOffset = 0)
                                }

                val isRestored =
                        lastPackage != null && BottomBarState.restoredApps.contains(lastPackage)
                val multiplier = if (isRestored) 3.0f else 1.0f

                val overscanValuePx = (settings.overscan.toFloat() * density * multiplier).toInt()
                val yOffsetPx = (settings.yOffset * density).toInt()

                withContext(Dispatchers.Main) {
                    lp.height = (60 * density).toInt()
                    lp.y = 0
                }
                overscanCmd = arrayOf("wm", "overscan", "0,0,0,$overscanValuePx")
            } else {
                // Trigger zone - keep 40dp (20dp on screen) area touchable
                withContext(Dispatchers.Main) {
                    lp.height = (60 * density).toInt()
                    lp.y = -(20 * density).toInt()
                }
                overscanCmd = arrayOf("wm", "overscan", "0,0,0,0")
            }

            ShizukuUtils.runCommandAndGetOutput(overscanCmd)

            withContext(Dispatchers.Main) {
                try {
                    wm.updateViewLayout(cv, lp)
                } catch (e: Exception) {
                    Log.e("BottomBarService", "Error updating window layout", e)
                }
            }
        }
    }

    private fun updateMenuWindow(show: Boolean) {
        val wm = mWindowManager ?: return
        val mv = menuComposeView ?: return
        val mp = menuParams ?: return

        if (!isMenuWindowAdded) {
            try {
                // Initialize as hidden if first added
                if (!show) {
                    mp.width = 0
                    mp.height = 0
                    mp.flags = mp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                wm.addView(mv, mp)
                isMenuWindowAdded = true
            } catch (e: Exception) {
                Log.e("BottomBarService", "Error adding menu window", e)
                return
            }
        }

        try {
            if (show) {
                mp.width = WindowManager.LayoutParams.MATCH_PARENT
                mp.height = WindowManager.LayoutParams.MATCH_PARENT
                mp.flags = mp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                mp.width = 0
                mp.height = 0
                mp.flags = mp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            wm.updateViewLayout(mv, mp)
        } catch (e: Exception) {
            Log.e("BottomBarService", "Error updating menu window layout", e)
        }
    }

    private fun showBottomBar() {
        mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themedContext = ContextThemeWrapper(this, R.style.Theme_HavalShisuku)

        composeView =
                ComposeView(themedContext)
                        .apply {
                            setContent { HavalShisukuTheme { BottomBarContent() } }
                            setupTouchableRegions(this, isMenuWindow = false)
                        }
                        .also { it.setupForService() }

        menuComposeView =
                ComposeView(themedContext)
                        .apply {
                            setContent { HavalShisukuTheme { BottomBarMenus() } }
                            setupTouchableRegions(this, isMenuWindow = true)
                        }
                        .also { it.setupForService() }

        val density = resources.displayMetrics.density
        val barHeight = (BOTTOM_BAR_BASE_HEIGHT_DP * density).toInt()

        val layoutType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }

        params =
                WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                barHeight,
                                layoutType,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                                PixelFormat.TRANSLUCENT
                        )
                        .apply {
                            // Immersive mode flags to hide system bars
                            systemUiVisibility =
                                    (android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE or
                                            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                            android.view.View
                                                    .SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            // On show, we derive y from the currently applied overscan value if
                            // possible,
                            // but setting it to -defaultOverscan below in show logic.
                            // For initial params, we can use 0 and it will be updated.
                            y = 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                layoutInDisplayCutoutMode =
                                        WindowManager.LayoutParams
                                                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                        }

        menuParams =
                WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                layoutType,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                                PixelFormat.TRANSLUCENT
                        )
                        .apply {
                            // Immersive mode flags to hide system bars
                            systemUiVisibility =
                                    (android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE or
                                            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                            android.view.View
                                                    .SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            y = 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                layoutInDisplayCutoutMode =
                                        WindowManager.LayoutParams
                                                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                        }

        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                mWindowManager?.addView(composeView, params)
                val settings =
                        currentAppSettings
                                ?: run {
                                    val prefs =
                                            br.com.redesurftank.App.getDeviceProtectedContext()
                                                    .getSharedPreferences(
                                                            "haval_prefs",
                                                            Context.MODE_PRIVATE
                                                    )
                                    val storedDefault =
                                            prefs.getInt(
                                                    SharedPreferencesKeys
                                                            .PERSISTENT_BOTTOM_BAR_OVERSCAN
                                                            .key,
                                                    REFERENCE_OVERSCAN
                                            )
                                    BarSettings(overscan = storedDefault, yOffset = 0)
                                }

                val overscanValuePx = (settings.overscan * density).toInt()
                val yOffsetPx = (settings.yOffset * density).toInt()

                val lp = params
                if (lp != null) {
                    lp.y = yOffsetPx
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    ShizukuUtils.runCommandAndGetOutput(
                            arrayOf("wm", "overscan", "0,0,0,$overscanValuePx")
                    )
                }
            } catch (e: Exception) {
                Log.e("BottomBarService", "Error adding views", e)
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private fun setupTouchableRegions(composeView: ComposeView, isMenuWindow: Boolean = false) {
        val observer = composeView.viewTreeObserver
        try {
            val listenerClass =
                    Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val setTouchableInsetsMethod =
                    infoClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val touchableRegionField = infoClass.getField("touchableRegion")

            val proxy =
                    Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) {
                            _,
                            method,
                            args ->
                        if (method.name == "onComputeInternalInsets") {
                            val info = args[0]
                            // 3 is TOUCHABLE_INSETS_REGION
                            setTouchableInsetsMethod.invoke(info, 3)
                            val region = touchableRegionField.get(info) as Region
                            region.setEmpty()

                            val density = resources.displayMetrics.density
                            val windowWidth = resources.displayMetrics.widthPixels

                            if (isMenuWindow) {
                                // Menu window is MATCH_PARENT (full screen height)
                                val anyMenuExpanded =
                                        BottomBarState.isMenuExpanded ||
                                                BottomBarState.isSettingsMenuExpanded ||
                                                BottomBarState.isOverrideMenuExpanded
                                Log.d(
                                        "BottomBarService",
                                        "TouchRegion[MENU] anyMenuExpanded=$anyMenuExpanded"
                                )
                                if (anyMenuExpanded) {
                                    val screenHeight = resources.displayMetrics.heightPixels
                                    region.union(Rect(0, 0, windowWidth, screenHeight))
                                }
                            } else {
                                // Bar window is 60dp tall
                                val windowHeight = (60 * density).toInt()
                                val topHandleHeight = (15 * density).toInt()
                                val hiddenTriggerHeight = (40 * density).toInt()
                                val visibleBarTouchHeight = (80 * density).toInt()

                                Log.d(
                                        "BottomBarService",
                                        "TouchRegion[BAR] isVisible=${BottomBarState.isVisible}, windowWidth=$windowWidth, windowHeight=$windowHeight, visibleBarTouchHeight=$visibleBarTouchHeight"
                                )

                                if (BottomBarState.isVisible) {
                                    // Main Bar touchable area - full width, bottom 80dp
                                    region.union(
                                            Rect(
                                                    0,
                                                    windowHeight - visibleBarTouchHeight,
                                                    windowWidth,
                                                    windowHeight
                                            )
                                    )
                                    // Top Handle for swipe gesture
                                    region.union(Rect(0, 0, windowWidth, topHandleHeight))
                                } else {
                                    // Hidden: only a small trigger zone at the bottom for swipe-up
                                    region.union(
                                            Rect(
                                                    0,
                                                    windowHeight - hiddenTriggerHeight,
                                                    windowWidth,
                                                    windowHeight
                                            )
                                    )
                                }
                            }
                        }
                        null
                    }

            val addMethod =
                    observer.javaClass.getMethod(
                            "addOnComputeInternalInsetsListener",
                            listenerClass
                    )
            addMethod.invoke(observer, proxy)
        } catch (e: Exception) {
            Log.e("BottomBarService", "Failed to setup touchable regions via reflection", e)
        }
    }

    private fun ComposeView.setupForService() {
        this.setViewTreeLifecycleOwner(this@BottomBarService)
        val viewModelStore = ViewModelStore()
        this.setViewTreeViewModelStoreOwner(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = viewModelStore
                }
        )
        val savedStateRegistryOwner =
                object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = this@BottomBarService.lifecycle
                    private val savedStateRegistryController =
                            SavedStateRegistryController.create(this)
                    override val lifecycle = lifecycleRegistry
                    override val savedStateRegistry =
                            savedStateRegistryController.savedStateRegistry
                    init {
                        savedStateRegistryController.performRestore(null)
                    }
                }
        this.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
    }

    override fun onDestroy() {
        monitoringJob?.cancel()
        unregisterReceiver(updateReceiver)
        super.onDestroy()
        ShizukuUtils.runCommandAndGetOutput(arrayOf("wm", "size", "reset"))
        ShizukuUtils.runCommandAndGetOutput(arrayOf("wm", "overscan", "0,0,0,0"))
        try {
            composeView?.let { mWindowManager?.removeView(it) }
            if (isMenuWindowAdded) {
                menuComposeView?.let { mWindowManager?.removeView(it) }
            }
        } catch (e: Exception) {}
    }
}
