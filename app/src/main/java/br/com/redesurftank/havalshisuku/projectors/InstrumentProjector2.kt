package br.com.redesurftank.havalshisuku.projectors

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import br.com.redesurftank.App
import br.com.redesurftank.havalshisuku.R
import br.com.redesurftank.havalshisuku.managers.ServiceManager
import br.com.redesurftank.havalshisuku.models.CarConstants
import br.com.redesurftank.havalshisuku.models.ClusterKey
import br.com.redesurftank.havalshisuku.models.SharedPreferencesKeys
import br.com.redesurftank.havalshisuku.models.ServiceManagerEventType
import br.com.redesurftank.havalshisuku.bridge.IBridgeContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.get
import kotlinx.coroutines.*

class InstrumentProjector2(private val outerContext: Context, display: Display) :
        BaseProjector(outerContext, display), IBridgeContext {

    private val TAG = "InstrumentProjector2"
    private val CURRENT_BRIDGE_VERSION = 1
    private val DEBUG_EXTERNAL_APP_HTML = "/data/local/tmp/app.html"
    private val FORCE_MAP_DISPLAY_AS_DEFAULT_FOR_TESTS = false
    private val MAP_DISPLAY_TEST_VALUE = "Mapa"
    private val PROJECTION_NATIVE_PANEL_RESTORE_HOLD_MS = 1200L
    private val PROJECTION_PROJECTOR_WARMUP_BYPASS_MS = 1600L
    private val PROJECTION_CARD_INPUT_ARM_WINDOW_MS = 1500L
    private val PROJECTION_D3_FALSE_NEGATIVE_HOLD_MS = 12_000L
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clockRunnable =
            object : Runnable {
                override fun run() {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    evaluateJsIfReady(webView, "control('clockTime', '$time')")
                    handler.postDelayed(this, 30000) // Update every 30s
                }
            }
    override val preferences: SharedPreferences =
            App.getDeviceProtectedContext()
                    .getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)
    override var webView: WebView? = null
    private val webViewsLoaded = mutableMapOf<WebView, Boolean>()
    private val pendingJsQueues = mutableMapOf<WebView, MutableList<String>>()
    private lateinit var root: FrameLayout

    // Dedup cache: skip a JS push when the same key:value pair was the most
    // recently pushed one. Car CAN bus often re-emits identical values back
    // to back; pushing them all the way through to the WebView causes wasted
    // DOM mutations + Chrome compositor renders. Cleared on bootstrap (init,
    // card-change, page-finished) where we want a fresh full sync.
    private val lastSentValues = java.util.concurrent.ConcurrentHashMap<String, String>()
    override val subscribedKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Cached EV power values for kW calculation
    private var batteryVoltage = 0f
    private var batteryCurrent = 0f
    private var isAnyAppOnDisplay3 = false
    private var isAnyAppOnDisplay1 = false
    private var currentCard = 0
    private var isWarningActive = false
    private var testDefaultDisplayOverrideActive = FORCE_MAP_DISPLAY_AS_DEFAULT_FOR_TESTS
    private val dismissedWarnings = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var isWarningDismissed = false
    private var lastWarningActiveTime = 0L
    private var projectionOverlayBypassActive: Boolean? = null
    private var hvacNativePanelActive = false
    private var avmNativePreviewActive = false
    private var nativePanelBypassHoldUntilMs = 0L
    private var projectorWarmupBypassUntilMs = 0L
    private var projectionBypassRestoreScheduledUntilMs = 0L

    private fun isWarningValueActive(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim()
        return v != "0" && v != "{0,0,0,0}" && v != "{0,0,0,0,0}" && v != "" && v != "false"
    }

    private var hasAutoLaunched = false
    private val lastAppliedConfigs =
            mutableMapOf<String, br.com.redesurftank.havalshisuku.models.DisplayAppConfig>()

    private var activeThemeMetadata: br.com.redesurftank.havalshisuku.models.ThemeMetadata? = null
    private var themeBridge: br.com.redesurftank.havalshisuku.bridge.ThemeBridgeImpl? = null
    private var bridgePrefsListener: br.com.redesurftank.havalshisuku.bridge.PreferencePushListener? = null

    private var lastHeartbeatTime = System.currentTimeMillis()
    private var lastCarPlayInDash: Boolean? = null
    private var lastProjectionMirrorInDash: Boolean? = null
    private var lastProjectionPreparingD3: Boolean? = null
    private var lastProjectionCardOverlayAllowed: Boolean? = null
    private var projectionCardOverlayAllowed = false
    private var projectionActiveSinceMs = 0L
    private var lastClusterInputAtMs = 0L
    private var lastHealthyCarPlayD3AtMs = 0L
    private var lastCarPlayD3HoldLogAtMs = 0L
    private var lastProjectionVisibilityLog = ""
    private var lastProjectionDomDiagnosticAt = 0L
    private val watchdogRunnable =
            object : Runnable {
                override fun run() {
                    val now = System.currentTimeMillis()
                    // If no heartbeat for 15 seconds, and the projector should be visible, reload
                    if (now - lastHeartbeatTime > 15000 &&
                                    shouldShowProjector() &&
                                    ::root.isInitialized &&
                                    root.isVisible
                    ) {
                        Log.e(
                                TAG,
                                "WebView watchdog triggered: No heartbeat for ${now - lastHeartbeatTime}ms. Reloading..."
                        )
                        ensureUi { webView?.reload() }
                        lastHeartbeatTime =
                                System.currentTimeMillis() // Reset to avoid immediate re-trigger
                    }
                    refreshProjectionStateFromDisplay("WATCHDOG")
                    handler.postDelayed(this, 5000) // Check every 5s
                }
            }

    val monitoredWarningKeys =
            setOf(
                    CarConstants.CAR_BASIC_COOLANT_TEMP_WARNING.value,
                    CarConstants.CAR_BASIC_ENGINE_OIL_LOW_PRESSURE_WARNING.value,
                    CarConstants.CAR_BASIC_FATIGUE_WARNING.value,
                    // CarConstants.CAR_BASIC_MAINTENANCE_WARNING.value,
                    CarConstants.CAR_BASIC_OIL_LOW_WARNING.value,
                    CarConstants.CAR_BASIC_SEAT_BELT_WARNING.value,
                    CarConstants.CAR_BASIC_TIREPRESS_WARNING.value,
                    CarConstants.CAR_BASIC_TIRETEMP_WARNING.value,
                    CarConstants.CAR_BASIC_TPMS_WARNING.value,
                    CarConstants.CAR_IPK_INFO_BSD_LCA_WARNING_REQLEFT.value,
                    CarConstants.CAR_IPK_INFO_BSD_LCA_WARNING_REQRIGHT.value,
                    // CarConstants.CAR_IPK_INFO_DOW_WARNING_REQLEFT.value,
                    // CarConstants.CAR_IPK_INFO_DOW_WARNING_REQRIGHT.value,
                    // CarConstants.CAR_IPK_INFO_FCTA_WARNING.value,
                    // CarConstants.CAR_IPK_INFO_FCW_WARNING.value,
                    CarConstants.CAR_IPK_INFO_WARNING_TTS_NOTIFY.value,
                    CarConstants.CAR_IPK_LIGHT_DOOR_WARNING.value,
                    CarConstants.CAR_IPK_LIGHT_ENGINE_OIL_LOW_PRESSURE_WARNING.value,
                    // CarConstants.CAR_IPK_LIGHT_SEAT_BELT_WARNING_INDICATOR.value,
                    CarConstants.CAR_IPK_LIGHT_TPMS_WARNING.value,
                    CarConstants.CAR_IPK_LIGHT_FUEL_LOW.value
            )

    private val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in
                                listOf(
                                        SharedPreferencesKeys
                                                .ENABLE_INSTRUMENT_CUSTOM_MEDIA_INTEGRATION
                                                .key,
                                        SharedPreferencesKeys.ENABLE_INSTRUMENT_PROJECTOR.key,
                                        SharedPreferencesKeys.ENABLE_VIRTUAL_CLUSTER.key,
                                        SharedPreferencesKeys.VIRTUAL_CLUSTER_DISPLAY_ID.key,
                                        SharedPreferencesKeys.ACTIVE_CUSTOM_THEME.key,
                                        SharedPreferencesKeys.VIRTUAL_CLUSTER_THEME.key,
                                        SharedPreferencesKeys.CLUSTER_FUEL_DISPLAY_UNIT.key,
                                        SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_ACTIVE.key,
                                        SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_SCORE.key,
                                        SharedPreferencesKeys
                                                .ENABLE_INSTRUMENT_ODOMETER_AND_REVISION
                                                .key
                                )
                ) {
                    ensureUi {

                        if (key == SharedPreferencesKeys.ENABLE_INSTRUMENT_ODOMETER_AND_REVISION.key
                        ) {
                            val enabled = preferences.getBoolean(key, true)
                            val nextKm =
                                    preferences.getInt(
                                            SharedPreferencesKeys.INSTRUMENT_REVISION_KM.key,
                                            0
                                    )
                            evaluateJsIfReady(webView, "control('enableOdometer', $enabled)")
                            evaluateJsIfReady(
                                    webView,
                                    "control('enableRevisionWarning', ${enabled && nextKm > 0})"
                            )
                        }
                        if (key == SharedPreferencesKeys.ACTIVE_CUSTOM_THEME.key ||
                                        key == SharedPreferencesKeys.VIRTUAL_CLUSTER_THEME.key
                        ) {
                            Log.d(TAG, "Theme changed, reloading WebView")
                            webView?.loadDataWithBaseURL(
                                    getThemeBaseUrl(),
                                    readAppContent(outerContext),
                                    "text/html",
                                    "UTF-8",
                                    null
                            )
                        }
                        if (key == SharedPreferencesKeys.CLUSTER_FUEL_DISPLAY_UNIT.key) {
                            val unit = getClusterFuelDisplayUnit()
                            Log.d(TAG, "[HavalDev] Cluster fuel display unit changed: $unit")
                            evaluateJsIfReady(webView, "control('fuelDisplayUnit', '$unit')")
                        }
                        if (key == SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_ACTIVE.key) {
                            val active = preferences.getBoolean(key, false)
                            evaluateJsIfReady(webView, "control('tripAnalysisActive', $active)")
                        }
                        if (key == SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_SCORE.key) {
                            val score =
                                    preferences.getInt(
                                            SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_SCORE
                                                    .key,
                                            -1
                                    )
                            evaluateJsIfReady(
                                    webView,
                                    "control('tripAnalysisScore', ${if (score >= 0) score else "null"})"
                            )
                        }
                        root.isVisible =
                                shouldShowProjector() &&
                                        ServiceManager.getInstance().isMainScreenOn
                        updateVirtualClusterVisibility(reason = "PREFS_CHANGED")
                    }
                } else if (key == SharedPreferencesKeys.INSTRUMENT_REVISION_KM.key) {
                    val nextRevisionKm = preferences.getInt(key, 0)
                    val enabled =
                            preferences.getBoolean(
                                    SharedPreferencesKeys.ENABLE_INSTRUMENT_ODOMETER_AND_REVISION
                                            .key,
                                    true
                            )
                    ensureUi {
                        evaluateJsIfReady(webView, "control('nextRevisionKm', $nextRevisionKm)")
                        evaluateJsIfReady(
                                webView,
                                "control('enableRevisionWarning', ${enabled && nextRevisionKm > 0})"
                        )
                    }
                } else if (key == SharedPreferencesKeys.INSTRUMENT_REVISION_NEXT_DATE.key) {
                    val nextRevisionDate = preferences.getLong(key, 0L)
                    ensureUi {
                        evaluateJsIfReady(webView, "control('nextRevisionDate', $nextRevisionDate)")
                    }
                }
            }

    private fun shouldShowProjector(): Boolean {
        return preferences.getBoolean(
                SharedPreferencesKeys.ENABLE_INSTRUMENT_PROJECTOR.key,
                false
        ) &&
                preferences.getBoolean(
                        SharedPreferencesKeys.ENABLE_INSTRUMENT_CUSTOM_MEDIA_INTEGRATION.key,
                        false
                )
    }

    private fun isCarPlayInDash(): Boolean {
        val rawCarPlayOnD3 =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.isCarPlayOnDisplay(3)
        val now = SystemClock.elapsedRealtime()
        if (rawCarPlayOnD3) {
            lastHealthyCarPlayD3AtMs = now
            return true
        }

        return shouldHoldCarPlayD3State(now)
    }

    private fun isProjectionMirrorInDash(): Boolean {
        val rawProjectionOnD3 =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                        .isProjectionMirrorOnDisplay(3)
        if (rawProjectionOnD3) return true

        return shouldHoldCarPlayD3State(SystemClock.elapsedRealtime())
    }

    private fun isProjectionPreparingD3(): Boolean {
        return br.com.redesurftank.havalshisuku.managers.CarPlayDisplayOrchestrator.isPreparingD3()
    }

    private fun shouldHoldCarPlayD3State(now: Long): Boolean {
        val desiredCluster =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                        .isCarPlayDesiredOnCluster()
        val preparingD3 = isProjectionPreparingD3()
        val shouldHold =
                ProjectionD3StateHoldPolicy.shouldHoldCarPlayInDash(
                        lastHealthyCarPlayD3AtMs,
                        now,
                        desiredCluster,
                        preparingD3,
                        PROJECTION_D3_FALSE_NEGATIVE_HOLD_MS
                )
        if (shouldHold && now - lastCarPlayD3HoldLogAtMs > 2_000L) {
            Log.w(
                    TAG,
                    "[PROJECTION_D3_STATE_HOLD] Keeping Mapa active after transient D3 proof loss; " +
                            "lastHealthyAgoMs=${now - lastHealthyCarPlayD3AtMs} " +
                            "desiredCluster=$desiredCluster preparingD3=$preparingD3"
            )
            lastCarPlayD3HoldLogAtMs = now
        }
        return shouldHold
    }

    private fun isNativeProjectionPanelKey(key: String): Boolean {
        return key == CarConstants.CAR_HVAC_PANEL_DISPLAY_NOTIFY.value ||
                key == CarConstants.SYS_AVM_PREVIEW_STATUS.value
    }

    private fun isNativePanelValueActive(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return false
        return normalized.isNotEmpty() &&
                normalized != "0" &&
                normalized != "false" &&
                normalized != "off" &&
                normalized != "null" &&
                normalized != "{0,0,0,0}" &&
                normalized != "{0,0,0,0,0}"
    }

    private fun isNativeProjectionPanelActive(): Boolean {
        return hvacNativePanelActive || avmNativePreviewActive
    }

    private fun updateNativeProjectionPanelStateFromSignal(key: String, value: String): Boolean {
        val active = isNativePanelValueActive(value)
        val changed =
                when (key) {
                    CarConstants.CAR_HVAC_PANEL_DISPLAY_NOTIFY.value -> {
                        if (hvacNativePanelActive == active) {
                            false
                        } else {
                            hvacNativePanelActive = active
                            true
                        }
                    }
                    CarConstants.SYS_AVM_PREVIEW_STATUS.value -> {
                        if (avmNativePreviewActive == active) {
                            false
                        } else {
                            avmNativePreviewActive = active
                            true
                        }
                    }
                    else -> false
                }

        if (changed) {
            if (!active) {
                nativePanelBypassHoldUntilMs =
                        SystemClock.uptimeMillis() + PROJECTION_NATIVE_PANEL_RESTORE_HOLD_MS
            }
            Log.w(
                    TAG,
                    "Native projection panel state changed: key=$key value=$value active=$active hvac=$hvacNativePanelActive avm=$avmNativePreviewActive"
            )
        }
        return changed
    }

    private fun refreshNativeProjectionPanelStateFromCache(): Boolean {
        val sm = ServiceManager.getInstance()
        val hvacActive =
                isNativePanelValueActive(sm.getData(CarConstants.CAR_HVAC_PANEL_DISPLAY_NOTIFY.value))
        val avmActive =
                isNativePanelValueActive(sm.getData(CarConstants.SYS_AVM_PREVIEW_STATUS.value))
        val changed = hvacNativePanelActive != hvacActive || avmNativePreviewActive != avmActive
        if (changed) {
            hvacNativePanelActive = hvacActive
            avmNativePreviewActive = avmActive
            Log.w(
                    TAG,
                    "Native projection panel state refreshed from cache: hvac=$hvacNativePanelActive avm=$avmNativePreviewActive"
            )
        }
        return changed
    }

    private fun scheduleProjectionBypassRestore(untilMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (untilMs <= now || projectionBypassRestoreScheduledUntilMs >= untilMs) return

        projectionBypassRestoreScheduledUntilMs = untilMs
        handler.postDelayed(
                {
                    projectionBypassRestoreScheduledUntilMs = 0L
                    updateVirtualClusterVisibility(reason = "PROJECTION_BYPASS_RESTORE")
                },
                untilMs - now + 80L
        )
    }

    private fun isProjectionOverlayBypassActive(carPlayInDash: Boolean): Boolean {
        if (!carPlayInDash) return false

        // Camera/AVM/HVAC no longer hide the cluster Presentation. The native
        // CarPlay patch keeps the video route alive, and hiding this WebView
        // removes the protected Mapa overlay while the projection is healthy
        // on display 3.
        return false
    }

    private fun applyProjectionOverlayBypass(active: Boolean) {
        if (projectionOverlayBypassActive == active) return

        projectionOverlayBypassActive = active
        val alpha = if (active) 0f else 1f
        window?.let { win ->
            val attrs = win.attributes
            attrs.alpha = alpha
            win.attributes = attrs
        }
        Log.w(
                TAG,
                "Projection overlay bypass active=$active windowAlpha=$alpha hvac=$hvacNativePanelActive avm=$avmNativePreviewActive"
        )
    }

    private fun applyProjectorViewVisibility(visible: Boolean, bypassActive: Boolean) {
        if (!::root.isInitialized) return

        val alpha = if (bypassActive) 0f else 1f
        root.alpha = alpha
        root.isVisible = visible && !bypassActive
        webView?.alpha = alpha
        webView?.visibility = if (bypassActive) View.INVISIBLE else View.VISIBLE
    }

    private fun resetProjectionStateCache() {
        lastCarPlayInDash = null
        lastProjectionMirrorInDash = null
        lastProjectionPreparingD3 = null
        lastProjectionCardOverlayAllowed = null
    }

    private fun isProjectionActive(
            carPlayInDash: Boolean = isCarPlayInDash(),
            projectionMirrorInDash: Boolean = isProjectionMirrorInDash(),
            projectionPreparingD3: Boolean = isProjectionPreparingD3()
    ): Boolean {
        return carPlayInDash || projectionMirrorInDash || projectionPreparingD3
    }

    private fun refreshProjectionActiveWindow(
            carPlayInDash: Boolean,
            projectionMirrorInDash: Boolean,
            reason: String,
            projectionPreparingD3: Boolean = isProjectionPreparingD3()
    ): Boolean {
        val active = isProjectionActive(carPlayInDash, projectionMirrorInDash, projectionPreparingD3)
        val now = SystemClock.uptimeMillis()
        if (active && projectionActiveSinceMs == 0L) {
            projectionActiveSinceMs = now
            projectionCardOverlayAllowed = false
            lastProjectionCardOverlayAllowed = null
            Log.w(
                    TAG,
                    "[PROJECTION_CARD_OVERLAY] Projection became active; suppressing bootstrap card overlay reason=$reason"
            )
        } else if (!active && projectionActiveSinceMs != 0L) {
            projectionActiveSinceMs = 0L
            setProjectionCardOverlayAllowed(false, "$reason:projection_inactive")
        }
        return active
    }

    private fun setProjectionCardOverlayAllowed(
            allowed: Boolean,
            reason: String,
            force: Boolean = false
    ) {
        projectionCardOverlayAllowed = allowed
        if (force || lastProjectionCardOverlayAllowed != allowed) {
            Log.w(
                    TAG,
                    "[PROJECTION_CARD_OVERLAY] allowed=$allowed reason=$reason cardId=$currentCard loaded=${
                        webView?.let { webViewsLoaded.getOrDefault(it, false) } ?: false
                    }"
            )
            evaluateJsIfReady(webView, "control('projectionCardOverlayAllowed', $allowed)")
            lastProjectionCardOverlayAllowed = allowed
            if (isProjectionActive()) {
                scheduleProjectionDomDiagnostic("PROJECTION_CARD_OVERLAY")
            }
        }
    }

    private fun reconcileProjectionCardOverlayForCardChange(
            previousCard: Int,
            nextCard: Int,
            carPlayInDash: Boolean,
            projectionMirrorInDash: Boolean
    ) {
        val projectionActive =
                refreshProjectionActiveWindow(
                        carPlayInDash,
                        projectionMirrorInDash,
                        "CLUSTER_CARD_CHANGED"
                )
        if (!projectionActive) {
            setProjectionCardOverlayAllowed(false, "CLUSTER_CARD_CHANGED:no_projection")
            return
        }

        val now = SystemClock.uptimeMillis()
        val sinceProjectionActive = now - projectionActiveSinceMs
        val sinceInput = if (lastClusterInputAtMs == 0L) Long.MAX_VALUE else now - lastClusterInputAtMs
        val recentInput = sinceInput <= PROJECTION_CARD_INPUT_ARM_WINDOW_MS
        val shouldAllow =
                ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(
                        projectionActive,
                        projectionCardOverlayAllowed,
                        recentInput
                )

        setProjectionCardOverlayAllowed(
                shouldAllow,
                "CLUSTER_CARD_CHANGED:$previousCard->$nextCard recentInput=$recentInput sinceInputMs=$sinceInput sinceProjectionMs=$sinceProjectionActive reason=${
                    if (shouldAllow) "physical_input_or_overlay_session" else "no_recent_input"
                }"
        )
    }

    private fun armProjectionCardOverlayFromInput(keyName: String, keyCode: Int, action: Int) {
        lastClusterInputAtMs = SystemClock.uptimeMillis()
        val carPlayInDash = isCarPlayInDash()
        val projectionMirrorInDash = isProjectionMirrorInDash()
        val projectionActive =
                refreshProjectionActiveWindow(
                        carPlayInDash,
                        projectionMirrorInDash,
                        "CLUSTER_INPUT_KEY"
                )
        if (!projectionActive) {
            return
        }

        if (ProjectionCardOverlayPolicy.shouldArmFromClusterInput(projectionActive)) {
            setProjectionCardOverlayAllowed(
                    true,
                    "CLUSTER_INPUT_KEY:$keyName($keyCode) action=$action",
                    force = true
            )
        } else {
            Log.w(
                    TAG,
                    "[PROJECTION_CARD_OVERLAY] input ignored for overlay by policy cardId=$currentCard key=$keyName($keyCode) action=$action"
            )
        }
    }

    private fun pushProjectionStateToWebView(
            carPlayInDash: Boolean,
            projectionMirrorInDash: Boolean,
            projectionPreparingD3: Boolean = isProjectionPreparingD3(),
            force: Boolean = false
    ) {
        refreshProjectionActiveWindow(
                carPlayInDash,
                projectionMirrorInDash,
                "PROJECTION_STATE_PUSH",
                projectionPreparingD3
        )
        val sendCarPlay = force || lastCarPlayInDash != carPlayInDash
        val sendProjectionMirror = force || lastProjectionMirrorInDash != projectionMirrorInDash
        val sendProjectionPreparing = force || lastProjectionPreparingD3 != projectionPreparingD3
        val sendProjectionCardOverlay =
                force || lastProjectionCardOverlayAllowed != projectionCardOverlayAllowed
        if (sendCarPlay || sendProjectionMirror || sendProjectionPreparing || sendProjectionCardOverlay) {
            Log.w(
                    TAG,
                    "[PROJECTION_STATE_PUSH] force=$force carPlayInDash=$carPlayInDash projectionMirrorInDash=$projectionMirrorInDash projectionPreparingD3=$projectionPreparingD3 projectionCardOverlayAllowed=$projectionCardOverlayAllowed loaded=${
                        webView?.let { webViewsLoaded.getOrDefault(it, false) } ?: false
                    } lastCarPlayInDash=$lastCarPlayInDash lastProjectionMirrorInDash=$lastProjectionMirrorInDash lastProjectionPreparingD3=$lastProjectionPreparingD3 lastProjectionCardOverlayAllowed=$lastProjectionCardOverlayAllowed"
            )
        }
        if (sendCarPlay) {
            evaluateJsIfReady(webView, "control('carPlayInDash', $carPlayInDash)")
            lastCarPlayInDash = carPlayInDash
        }
        if (sendProjectionMirror) {
            evaluateJsIfReady(webView, "control('projectionMirrorInDash', $projectionMirrorInDash)")
            lastProjectionMirrorInDash = projectionMirrorInDash
        }
        if (sendProjectionPreparing) {
            evaluateJsIfReady(webView, "control('projectionPreparingD3', $projectionPreparingD3)")
            lastProjectionPreparingD3 = projectionPreparingD3
        }
        if (sendProjectionCardOverlay) {
            evaluateJsIfReady(
                    webView,
                    "control('projectionCardOverlayAllowed', $projectionCardOverlayAllowed)"
            )
            lastProjectionCardOverlayAllowed = projectionCardOverlayAllowed
        }
        if (carPlayInDash || projectionMirrorInDash || projectionPreparingD3 || force) {
            scheduleProjectionDomDiagnostic("PROJECTION_STATE_PUSH")
        }
    }

    private fun refreshProjectionStateFromDisplay(reason: String) {
        val carPlayInDash = isCarPlayInDash()
        val projectionMirrorInDash = isProjectionMirrorInDash()
        val projectionPreparingD3 = isProjectionPreparingD3()
        if (
                lastCarPlayInDash != carPlayInDash ||
                        lastProjectionMirrorInDash != projectionMirrorInDash ||
                        lastProjectionPreparingD3 != projectionPreparingD3
        ) {
            Log.w(
                    TAG,
                    "[$reason] Projection state changed: carPlayInDash=$carPlayInDash projectionMirrorInDash=$projectionMirrorInDash projectionPreparingD3=$projectionPreparingD3"
            )
            updateVirtualClusterVisibility(
                    carPlayInDash,
                    projectionMirrorInDash,
                    reason,
                    projectionPreparingD3
            )
        }
    }

    private val eventListener =
            br.com.redesurftank.havalshisuku.listeners.IServiceManagerEvent { event, args ->
                ensureUi {
                    when (event) {
                        ServiceManagerEventType.CLUSTER_CARD_CHANGED -> {
                            val previousCard = currentCard
                            currentCard = args[0] as Int
                            lastAppliedConfigs
                                    .clear() // Invalidate cache on card change to force re-sync
                            // Re-assert projection state BEFORE the cardId change reaches JS,
                            // and force-push it (bypassing the dedup cache). Without this, a
                            // race exists where JS would re-render with `screen-aircon` /
                            // `screen-main-menu` while `carPlayInDash`/`projectionMirrorInDash`
                            // were stale-false in JS state, briefly removing the
                            // `theme-mirror-cluster` class and letting opaque component
                            // backgrounds repaint over the CarPlay frame.
                            val carPlayInDash = isCarPlayInDash()
                            val projectionMirrorInDash = isProjectionMirrorInDash()
                            val projectionPreparingD3 = isProjectionPreparingD3()
                            reconcileProjectionCardOverlayForCardChange(
                                    previousCard,
                                    currentCard,
                                    carPlayInDash,
                                    projectionMirrorInDash
                            )
                            pushProjectionStateToWebView(
                                    carPlayInDash,
                                    projectionMirrorInDash,
                                    projectionPreparingD3,
                                    force = true
                            )
                            evaluateJsIfReady(webView, "control('cardId', $currentCard)")
                            updateVirtualClusterVisibility(
                                    carPlayInDash,
                                    projectionMirrorInDash,
                                    "CLUSTER_CARD_CHANGED",
                                    projectionPreparingD3
                            )
                            syncSecondaryDisplayApps(3)
                            if (currentCard == 1 || currentCard == 3) {
                                isWarningDismissed = false
                                updateValuesWebView()
                            }
                        }
                        ServiceManagerEventType.CLUSTER_INPUT_KEY -> {
                            val keyName = args.getOrNull(0) as? String ?: "UNKNOWN"
                            val keyCode = args.getOrNull(1) as? Int ?: -1
                            val action = args.getOrNull(2) as? Int ?: -1
                            armProjectionCardOverlayFromInput(keyName, keyCode, action)
                        }
                        ServiceManagerEventType.STEERING_WHEEL_AC_CONTROL -> {
                            val action = args[0]
                            if (action is SteeringWheelAcControlType) {
                                when (action) {
                                    SteeringWheelAcControlType.FAN_SPEED ->
                                            evaluateJsIfReady(webView, "focus('fan')")
                                    SteeringWheelAcControlType.TEMPERATURE ->
                                            evaluateJsIfReady(webView, "focus('temp')")
                                    SteeringWheelAcControlType.POWER ->
                                            evaluateJsIfReady(webView, "focus('power')")
                                }
                            } else if (action is String) {
                                evaluateJsIfReady(webView, "control('acAction', '$action')")
                            }
                            br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                    .preserveCarPlayClusterContract("STEERING_WHEEL_AC_CONTROL")
                            br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                    .preserveAndroidAutoNativePanelContract("STEERING_WHEEL_AC_CONTROL")
                        }
                        ServiceManagerEventType.DISPLAY_3_APP_STATE_CHANGED -> {
                            isAnyAppOnDisplay3 = args[0] as Boolean
                            Log.w(
                                    TAG,
                                    "Display 3 app state changed in cluster projector: $isAnyAppOnDisplay3"
                            )
                            if (!isAnyAppOnDisplay3) {
                                lastAppliedConfigs.clear()
                            }
                            updateVirtualClusterVisibility(reason = "DISPLAY_3_APP_STATE_CHANGED")
                            syncSecondaryDisplayApps(3)
                            pushVirtualDisplayState(3)
                        }
                        ServiceManagerEventType.DISPLAY_1_APP_STATE_CHANGED -> {
                            isAnyAppOnDisplay1 = args[0] as Boolean
                            Log.w(
                                    TAG,
                                    "Display 1 app state changed in cluster projector: $isAnyAppOnDisplay1"
                            )
                            updateVirtualClusterVisibility(reason = "DISPLAY_1_APP_STATE_CHANGED")
                            pushVirtualDisplayState(1)
                        }
                        ServiceManagerEventType.DISMISS_WARNING -> {
                            val timeSinceWarning = System.currentTimeMillis() - lastWarningActiveTime
                            Log.d(TAG, "Received DISMISS_WARNING event. timeSinceWarning=${timeSinceWarning}ms (onset=${lastWarningActiveTime})")
                            if (timeSinceWarning >= 2500) {
                                evaluateJsIfReady(webView, "clearWarnings()")
                                updateWarningUI(false)
                                isWarningDismissed = true

                                val sm = ServiceManager.getInstance()
                                for (key in monitoredWarningKeys) {
                                    val value = sm.getData(key)
                                    if (isWarningValueActive(value)) {
                                        dismissedWarnings[key] = value!!
                                    }
                                }
                            } else {
                                Log.w(TAG, "DISMISS_WARNING ignored: timeSinceWarning=${timeSinceWarning}ms < 2500ms lockout")
                            }
                        }
                        ServiceManagerEventType.APP_GEOMETRY_CHANGED -> {
                            updateVirtualClusterVisibility(reason = "APP_GEOMETRY_CHANGED")
                            syncSecondaryDisplayApps(3)
                        }
                        ServiceManagerEventType.RAW_KEY_EVENT -> {
                            val key = args[0] as br.com.redesurftank.havalshisuku.models.ClusterKey
                            evaluateJsIfReady(webView, "if (window.onKeyEvent) window.onKeyEvent('${key.name}');")
                        }
                        else -> {}
                    }
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.post(clockRunnable)
        handler.post(watchdogRunnable)
        preferences.registerOnSharedPreferenceChangeListener(prefsListener)
        bridgePrefsListener = br.com.redesurftank.havalshisuku.bridge.PreferencePushListener(this@InstrumentProjector2) { virtualKey, value ->
            themeBridge?.pushOnDataChanged(virtualKey, value)
        }
        preferences.registerOnSharedPreferenceChangeListener(bridgePrefsListener)
        ServiceManager.getInstance().addServiceManagerEventListener(eventListener)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
        )
        projectorWarmupBypassUntilMs =
                SystemClock.uptimeMillis() + PROJECTION_PROJECTOR_WARMUP_BYPASS_MS

        root = FrameLayout(outerContext).apply { setBackgroundColor(Color.TRANSPARENT) }
        setContentView(root)
        setupControlView(root)
        isAnyAppOnDisplay3 =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.isAnyAppOnDisplay(3)
        isAnyAppOnDisplay1 =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.isAnyAppOnDisplay(1)
        updateValuesWebView() // Queue initial values for state sync
        syncInitialWarnings() // Fresh JS state on init — warnings need to be primed
        refreshNativeProjectionPanelStateFromCache()
        updateVirtualClusterVisibility(reason = "ON_CREATE")
        setupDataListeners()
    }

    override fun onStop() {
        Log.w(TAG, "onStop: Cleaning up resources")
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(watchdogRunnable)
        scope.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        bridgePrefsListener?.let {
            preferences.unregisterOnSharedPreferenceChangeListener(it)
        }
        ServiceManager.getInstance().removeServiceManagerEventListener(eventListener)

        // Hardening: Explicitly destroy WebView to prevent leaks and broken channels
        webView?.let { wv: WebView ->
            Log.w(TAG, "Destroying WebView")
            root.removeView(wv)
            wv.stopLoading()
            wv.clearHistory()
            wv.clearCache(true)
            wv.loadUrl("about:blank")
            wv.onPause()
            wv.removeAllViews()
            wv.destroy()
            webView = null
        }

        super.onStop()
    }

    private fun setupDataListeners() {
        ServiceManager.getInstance().addDataChangedListener { key, value ->
            if (value == null) return@addDataChangedListener

            if (isNativeProjectionPanelKey(key)) {
                ensureUi {
                    if (updateNativeProjectionPanelStateFromSignal(key, value.toString())) {
                        updateVirtualClusterVisibility(reason = "NATIVE_PANEL_SIGNAL")
                    }
                }
            }

            // Same-value dedup. Cars commonly re-emit identical values
            // back-to-back (e.g. unchanged HVAC settings, sticky CAN
            // signals). Skipping them avoids round-tripping a no-op DOM
            // mutation through the WebView and Chrome compositor — that
            // was a big chunk of the ~30%+ sandbox-process CPU we saw.
            // Cache is cleared in updateValuesWebView() (init / card change
            // / page-finished) so the next telemetry burst is pushed
            // through even when values match the post-bootstrap snapshot.
            // NOTE: We intentionally do NOT gate on shouldShowProjector()
            // or isMainScreenOn here — even when the projector is briefly
            // hidden or the main screen is "off", we still want the
            // WebView's internal state to stay current so it's correct
            // the moment visibility returns.
            val previous = lastSentValues[key]
            if (previous == value) return@addDataChangedListener
            lastSentValues[key] = value

            ensureUi {
                // Symmetrical bridge push
                val themeBridge = this.themeBridge
                if (themeBridge != null) {
                    val themeKey = br.com.redesurftank.havalshisuku.bridge.BridgeContractTranslator.translateCanonicalToThemeKey(key)
                    if (subscribedKeys.contains(themeKey)) {
                        themeBridge.pushOnDataChanged(themeKey, value)
                    } else if (subscribedKeys.contains(key)) {
                        themeBridge.pushOnDataChanged(key, value)
                    }
                }

                // --- Warning Management Logic ---
                if (key in monitoredWarningKeys) {
                    val currentValue = value.toString()
                    if (dismissedWarnings[key] != currentValue) {
                        dismissedWarnings.remove(key)
                        if (isWarningValueActive(currentValue)) {
                            isWarningDismissed = false
                            if (!isWarningActive) {
                                lastWarningActiveTime = System.currentTimeMillis()
                                Log.d(TAG, "Warning onset detected in telemetry: key=$key value=$currentValue")
                            } else {
                                Log.d(TAG, "Telemetry warning update for key=$key value=$currentValue (already active, preserving onset)")
                            }
                            dismissedWarnings.clear()
                            syncInitialWarnings()
                        }
                        evaluateJsIfReady(webView, "updateWarning('$key', '$value')")
                    }
                }
            }
        }
    }

    private fun triggerAutoLaunch() {
        ensureUi {
            val defaultPackage =
                    preferences.getString(SharedPreferencesKeys.DEFAULT_DISPLAY_APP_PACKAGE.key, "")
                            ?: ""
            if (defaultPackage.isNotEmpty()) {
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getAllConfigs()
                        .find { it.packageName == defaultPackage }
                        ?.let { config ->
                            Log.d(TAG, "Auto-launching default app: $defaultPackage")
                            scope.launch {
                                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                        .launchApp(config)
                            }
                        }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupControlView(parent: FrameLayout) {
        if (webView == null) {
            webView =
                    WebView(this@InstrumentProjector2.context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                )
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = true
                        webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.let { wv: android.webkit.WebView ->
                                            Log.w(
                                                    TAG,
                                                    "WebView finished loading (PID: ${android.os.Process.myPid()}): $url"
                                            )

                                            // Mark the WebView as fully loaded first so that any new incoming
                                            // telemetry events are processed instantly instead of being queued.
                                            webViewsLoaded[wv] = true

                                            // Discard all stale, redundant telemetry updates queued during page load
                                            pendingJsQueues.remove(wv)

                                            // Perform a single, consolidated, full-state synchronization
                                            // using the latest car metrics to guarantee perfect UI consistency.
                                            updateValuesWebView()

                                            // Prime the warning state once so the UI reflects the current car warnings.
                                            syncInitialWarnings()

                                            // Pending JS is intentionally dropped on load; re-send projection
                                            // state immediately so CarPlay/AA display overrides never stay stale.
                                            resetProjectionStateCache()
                                            updateVirtualClusterVisibility(reason = "WEBVIEW_PAGE_FINISHED")

                                            // Inject Heartbeat
                                            wv.evaluateJavascript(
                                                    "setInterval(() => { if (window.Android && window.Android.heartbeat) window.Android.heartbeat(); }, 2000);",
                                                    null
                                            )
                                            // Inject polyfills if necessary
                                            br.com.redesurftank.havalshisuku.bridge.CompatTranslationLayer.injectPolyfillsIfNecessary(wv, activeThemeMetadata)
                                        }
                                    }
                                }
                        loadDataWithBaseURL(
                                getThemeBaseUrl(),
                                readAppContent(outerContext),
                                "text/html",
                                "UTF-8",
                                null
                        )
                        themeBridge = br.com.redesurftank.havalshisuku.bridge.ThemeBridgeImpl(this@InstrumentProjector2)
                        addJavascriptInterface(themeBridge!!, "Android")
                    }
            parent.addView(webView)
        }
    }



    private fun updateValuesWebView() {
        val sm = ServiceManager.getInstance()
        val webView = this.webView
        if (webView == null) return

        val updates = mutableMapOf<String, String>()

        val carPlayInDash = isCarPlayInDash()
        val projectionMirrorInDash = isProjectionMirrorInDash()
        val projectionPreparingD3 = isProjectionPreparingD3()
        refreshProjectionActiveWindow(
                carPlayInDash,
                projectionMirrorInDash,
                "WEBVIEW_STATE_SYNC",
                projectionPreparingD3
        )

        // Projection state must be established before card/screen state reaches
        // JS. Otherwise a stale card such as Display can render an opaque menu
        // over the native CarPlay Surface before mirror classes are active.
        updates["carPlayInDash"] = carPlayInDash.toString()
        updates["projectionMirrorInDash"] = projectionMirrorInDash.toString()
        updates["projectionPreparingD3"] = projectionPreparingD3.toString()
        updates["projectionCardOverlayAllowed"] = projectionCardOverlayAllowed.toString()
        updates["cardId"] = currentCard.toString()
        updates["display"] = getSavedClusterDisplay()
        Log.w(
                TAG,
                "[WEBVIEW_STATE_SYNC] carPlayInDash=$carPlayInDash projectionMirrorInDash=$projectionMirrorInDash projectionPreparingD3=$projectionPreparingD3 projectionCardOverlayAllowed=$projectionCardOverlayAllowed cardId=$currentCard display=${updates["display"]} loaded=${
                    webViewsLoaded.getOrDefault(webView, false)
                }"
        )

        // Gears
        updates["gearState"] = getGearLabel(sm.getData(CarConstants.CAR_BASIC_GEAR_STATUS.value))

        // AC and Core info
        updates["fan"] = sm.getData(CarConstants.CAR_HVAC_FAN_SPEED.value) ?: "0"
        updates["temp"] = sm.getData(CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.value) ?: "22"
        updates["power"] = sm.getData(CarConstants.CAR_HVAC_POWER_MODE.value) ?: "0"
        updates["recycle"] = sm.getData(CarConstants.CAR_HVAC_CYCLE_MODE.value) ?: "0"
        updates["auto"] = sm.getData(CarConstants.CAR_HVAC_AUTO_ENABLE.value) ?: "0"
        updates["aion"] = sm.getData(CarConstants.CAR_HVAC_ANION_ENABLE.value) ?: "0"

        val tempUnit = sm.getData(CarConstants.CAR_CONFIGURE_DEFAULT_TEMP_UNIT.value)
        updates["tempUnit"] = if (tempUnit == "1") "°F" else "°C"

        updates["outside_temp"] = formatTemp(sm.getData(CarConstants.CAR_BASIC_OUTSIDE_TEMP.value))
        updates["inside_temp"] = formatTemp(sm.getData(CarConstants.CAR_BASIC_INSIDE_TEMP.value))

        // Revision info
        val enableOdometerAndRevision =
                preferences.getBoolean(
                        SharedPreferencesKeys.ENABLE_INSTRUMENT_ODOMETER_AND_REVISION.key,
                        true
                )
        val nextRevisionKm = preferences.getInt(SharedPreferencesKeys.INSTRUMENT_REVISION_KM.key, 0)
        updates["enableOdometer"] = enableOdometerAndRevision.toString()
        updates["enableRevisionWarning"] =
                (enableOdometerAndRevision && nextRevisionKm > 0).toString()

        val odometer = sm.getData(CarConstants.CAR_BASIC_TOTAL_ODOMETER.value) ?: "0"
        updates["odometer"] = odometer

        updates["nextRevisionKm"] = nextRevisionKm.toString()
        updates["nextRevisionDate"] =
                preferences
                        .getLong(SharedPreferencesKeys.INSTRUMENT_REVISION_NEXT_DATE.key, 0L)
                        .toString()

        // Fuel and Battery Percentages/Range
        updates["fuelPercent"] =
                sm.getData(CarConstants.CAR_BASIC_REMAIN_FUEL_PERCENTAGE.value) ?: "0"
        updates["batteryPercent"] =
                sm.getData(CarConstants.CAR_EV_INFO_CUR_BATTERY_POWER_PERCENTAGE.value) ?: "0"
        updates["fuelRange"] =
                sm.getData(CarConstants.CAR_EV_INFO_FUEL_MODE_REMAIN_ODOMETER.value) ?: "0"
        updates["batteryRange"] =
                sm.getData(CarConstants.CAR_EV_INFO_ELECTRIC_MODE_REMAIN_ODOMETER.value) ?: "0"
        updates["fuelDisplayUnit"] = getClusterFuelDisplayUnit()
        val tripAnalysisActive =
                preferences.getBoolean(
                        SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_ACTIVE.key,
                        false
                )
        val tripAnalysisScore =
                preferences.getInt(SharedPreferencesKeys.TRIP_CONSISTENCY_CLUSTER_SCORE.key, -1)
        updates["tripAnalysisActive"] = tripAnalysisActive.toString()
        updates["tripAnalysisScore"] = if (tripAnalysisScore >= 0) tripAnalysisScore.toString() else "null"

        // Speed and Engine
        val speedStr = getAdjustedSpeed(sm.getData(CarConstants.CAR_BASIC_VEHICLE_SPEED.value))
        updates["carSpeed"] = speedStr
        updates["engineRPM"] = sm.getData(CarConstants.CAR_BASIC_ENGINE_SPEED.value) ?: "0"

        // Power and Regen Graph
        val outputPower =
                sm.getData(CarConstants.CAR_EV_INFO_ENERGY_OUTPUT_PERCENTAGE.value)?.toFloatOrNull()
                        ?: 0.0f
        val regenValue = kotlin.math.max(0.0f, -1 * outputPower)
        updates["evPowerFactor"] = outputPower.toString()
        updates["evPowerRegen"] = regenValue.toString()

        // Battery KW Calculation
        batteryVoltage =
                sm.getData(CarConstants.CAR_EV_INFO_POWER_BATTERY_VOLTAGE.value)?.toFloatOrNull()
                        ?: 0f
        batteryCurrent =
                sm.getData(CarConstants.CAR_EV_INFO_CUR_CHARGE_CURRENT.value)?.toFloatOrNull() ?: 0f
        val kw = batteryVoltage * batteryCurrent / 1000f
        updates["evPowerKw"] = kw.toString()

        // Consumption initial values
        updateGasConsumption(
                sm.getData(CarConstants.CAR_BASIC_INSTANT_FUEL_CONSUMPTION.value),
                updates
        )
        updates["instantEVConsumption"] =
                sm.getData(CarConstants.CAR_EV_INFO_INSTANT_ENERGY_CONSUMPTION.value) ?: "0"

        // Bootstrap: clear the dedup cache so the next telemetry burst is
        // pushed through even if values match. The batchEvaluateJs below
        // re-establishes the WebView's full state.
        lastSentValues.clear()

        batchEvaluateJs(webView, updates)
    }

    /**
     * Fire current warning values into the WebView once, on freshly-loaded
     * JS state only (init / page-finished). Intentionally NOT called from
     * CLUSTER_CARD_CHANGED: card changes preserve the WebView's JS state,
     * including the user's "dismissed" flag per warning (set when they press
     * back). Re-pushing the same value would make the JS see "value changed
     * from undefined -> X" again and re-show an alert the user already
     * acknowledged. The car may still have the underlying warning active —
     * we keep tracking it via the per-change listener — but we don't
     * artificially re-trigger it on UI state transitions.
     */
    private fun syncInitialWarnings() {
        val sm = ServiceManager.getInstance()
        val webView = this.webView ?: return
        for (key in monitoredWarningKeys) {
            val value = sm.getData(key) ?: "0"
            if (dismissedWarnings[key] == value) {
                continue
            }
            evaluateJsIfReady(webView, "updateWarning('$key', '$value')")
        }
    }

    private fun getClusterFuelDisplayUnit(): String {
        val unit =
                preferences.getString(
                        SharedPreferencesKeys.CLUSTER_FUEL_DISPLAY_UNIT.key,
                        "liters"
                ) ?: "liters"
        return if (unit == "percent") "percent" else "liters"
    }

    private fun batchEvaluateJs(view: WebView?, updates: Map<String, String>) {
        if (view == null || updates.isEmpty()) return
        val jsBuilder = StringBuilder("(function(){")
        updates.forEach { (key, value) ->
            val formattedValue =
                    if (value == "true" ||
                                    value == "false" ||
                                    value == "null" ||
                                    value.toDoubleOrNull() != null
                    )
                            value
                    else "'$value'"
            jsBuilder.append("control('$key', $formattedValue);")
        }
        jsBuilder.append("})()")
        evaluateJsIfReady(view, jsBuilder.toString())
    }

    private fun updateVirtualClusterVisibility(
            carPlayInDash: Boolean = isCarPlayInDash(),
            projectionMirrorInDash: Boolean = isProjectionMirrorInDash(),
            reason: String = "UPDATE_VIRTUAL_CLUSTER_VISIBILITY",
            projectionPreparingD3: Boolean = isProjectionPreparingD3()
    ) {
        val clusterEnabled =
                preferences.getBoolean(SharedPreferencesKeys.ENABLE_VIRTUAL_CLUSTER.key, true)
        val projectorVisible =
                shouldShowProjector() && ServiceManager.getInstance().isMainScreenOn
        val overlayBypassActive = isProjectionOverlayBypassActive(carPlayInDash)
        var isLeftCovered = false
        var isRightCovered = false

        logProjectionVisibility(
                reason,
                carPlayInDash,
                projectionMirrorInDash,
                projectionPreparingD3,
                clusterEnabled,
                projectorVisible,
                overlayBypassActive
        )

        applyProjectionOverlayBypass(overlayBypassActive)
        applyProjectorViewVisibility(projectorVisible, overlayBypassActive)

        if (projectionMirrorInDash || projectionPreparingD3) {
            isLeftCovered = true
            isRightCovered = true
        }

        val configs = br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getAllConfigs()

        for (displayId in listOf(1, 3)) {
            val res =
                    br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                            .getDisplayResolution(displayId)
            val fullWidth = res.first
            if (fullWidth <= 0) continue

            val appsOnDisplay =
                    configs.filter { config ->
                        if (br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                        .isProjectionMirrorPackage(config.packageName)
                        ) {
                            return@filter false
                        }
                        val task =
                                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                        .findTaskForPackage(config.packageName)
                        task != null && task.displayId == displayId
                    }

            for (app in appsOnDisplay) {
                val bounds =
                        br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                .getEffectiveBounds(app)
                val baseX = bounds[0]
                val baseWidth = bounds[2] - bounds[0]

                if (baseX <= (fullWidth * 0.1f).toInt()) {
                    isLeftCovered = true
                }

                val actualWidth =
                        if (displayId == 3 && (!isWarningDismissed && (currentCard == 0 || isWarningActive))) {
                            (fullWidth * 0.7f).toInt() - baseX
                        } else {
                            baseWidth
                        }

                if (baseX + actualWidth >= (fullWidth * 0.7f).toInt()) {
                    isRightCovered = true
                }
            }
        }

        val appInDashValue =
                when {
                    isLeftCovered && isRightCovered -> "true"
                    isLeftCovered -> "'left'"
                    isRightCovered -> "'right'"
                    else -> "false"
                }

        evaluateJsIfReady(webView, "control('clusterEnabled', $clusterEnabled)")
        evaluateJsIfReady(webView, "control('appInDash', $appInDashValue)")
        pushProjectionStateToWebView(carPlayInDash, projectionMirrorInDash, projectionPreparingD3)
    }

    private fun logProjectionVisibility(
            reason: String,
            carPlayInDash: Boolean,
            projectionMirrorInDash: Boolean,
            projectionPreparingD3: Boolean,
            clusterEnabled: Boolean,
            projectorVisible: Boolean,
            overlayBypassActive: Boolean
    ) {
        val snapshot =
                "carPlayInDash=$carPlayInDash projectionMirrorInDash=$projectionMirrorInDash projectionPreparingD3=$projectionPreparingD3 " +
                        "projectionCardOverlayAllowed=$projectionCardOverlayAllowed cardId=$currentCard clusterEnabled=$clusterEnabled projectorVisible=$projectorVisible " +
                        "overlayBypass=$overlayBypassActive anyD3=$isAnyAppOnDisplay3 anyD1=$isAnyAppOnDisplay1"
        if (snapshot != lastProjectionVisibilityLog ||
                        reason == "ON_CREATE" ||
                        reason == "WEBVIEW_PAGE_FINISHED" ||
                        reason == "CLUSTER_CARD_CHANGED"
        ) {
            Log.w(TAG, "[$reason] Projection visibility: $snapshot")
            lastProjectionVisibilityLog = snapshot
        }
    }

    private fun scheduleProjectionDomDiagnostic(reason: String) {
        val view = webView ?: return
        if (!webViewsLoaded.getOrDefault(view, false)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastProjectionDomDiagnosticAt < 1_500L) return
        lastProjectionDomDiagnosticAt = now

        handler.postDelayed(
                {
                    val js =
                            """
                            (function(){
                              try {
                                if (window.__havalProjectionDebug) {
                                  return JSON.stringify(window.__havalProjectionDebug());
                                }
                                var app = document.getElementById('app');
                                var menu = document.querySelector('.dashboard-menu-container');
                                return JSON.stringify({
                                  debugHook: false,
                                  appClass: app ? app.className : null,
                                  menuDisplay: menu ? getComputedStyle(menu).display : null,
                                  menuVisibility: menu ? getComputedStyle(menu).visibility : null,
                                  menuOpacity: menu ? getComputedStyle(menu).opacity : null
                                });
                              } catch (e) {
                                return 'error:' + e.message;
                              }
                            })()
                            """.trimIndent()
                    view.evaluateJavascript(js) { result ->
                        Log.w(TAG, "[$reason] Projection DOM: $result")
                    }
                },
                250L
        )
    }

    private fun syncSecondaryDisplayApps(displayId: Int) {
        val res =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getDisplayResolution(
                        displayId
                )
        val fullWidth = res.first
        if (fullWidth <= 0) return

        val appsOnDisplay =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getAllConfigs()
                        .filter { config ->
                            if (br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                            .isProjectionMirrorPackage(config.packageName)
                            ) {
                                return@filter false
                            }
                            val task =
                                    br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                            .findTaskForPackage(config.packageName)
                            task != null &&
                                    task.displayId == displayId &&
                                    (config.packageName != outerContext.packageName ||
                                            displayId != 1)
                        }

        for (app in appsOnDisplay) {
            val bounds =
                    br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getEffectiveBounds(
                            app
                    )
            val baseX = bounds[0]
            val baseY = bounds[1]
            val baseWidth = bounds[2] - bounds[0]
            val baseHeight = bounds[3] - bounds[1]

            val targetWidth =
                    if (displayId == 3 && (!isWarningDismissed && (currentCard == 0 || isWarningActive))) {
                        val calculated = (fullWidth * 0.7f).toInt() - baseX
                        kotlin.math.max(100, kotlin.math.min(baseWidth, calculated))
                    } else {
                        baseWidth
                    }

            val targetConfig =
                    app.copy(
                            x = baseX,
                            y = baseY,
                            width = targetWidth,
                            height = baseHeight,
                            displayId = displayId
                    )
            val lastConfig = lastAppliedConfigs[app.packageName]

            if (lastConfig == null ||
                            lastConfig.x != targetConfig.x ||
                            lastConfig.y != targetConfig.y ||
                            lastConfig.width != targetConfig.width ||
                            lastConfig.height != targetConfig.height ||
                            lastConfig.displayId != targetConfig.displayId
            ) {

                lastAppliedConfigs[app.packageName] = targetConfig
                Log.d(
                        TAG,
                        "Syncing app ${app.packageName} (Display $displayId): card=$currentCard warn=$isWarningActive -> width=$targetWidth"
                )
                scope.launch {
                    br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.resizeApp(
                            targetConfig
                    )
                }
            }
        }
    }

    private fun evaluateJsIfReady(webView: WebView?, js: String) {
        if (webView == null) return
        if (webViewsLoaded.getOrDefault(webView, false)) {
            if (!hasAutoLaunched) {
                hasAutoLaunched = true
                triggerAutoLaunch()
            }
            webView.evaluateJavascript(js, null)
        } else {
            pendingJsQueues.getOrPut(webView) { mutableListOf() }.add(js)
        }
    }

    private fun getThemeBaseUrl(): String {
        val customThemeName = getActiveCustomThemeName()
        if (customThemeName.isNotEmpty()) {
            val themeDir = File(File(outerContext.filesDir, "themes"), customThemeName)
            if (themeDir.exists()) {
                return "file://${themeDir.absolutePath}/"
            }
        }
        return "file:///android_asset/"
    }

    private fun readAppContent(context: Context): String {
        if (isDebuggableApp()) {
            tryLoadExternalDebugHtml()?.let { externalHtml ->
                return externalHtml
            }
        }

        val customThemeName = getActiveCustomThemeName()
        if (customThemeName.isNotEmpty()) {
            try {
                val themeManager =
                        br.com.redesurftank.havalshisuku.managers.ThemeManager.getInstance(
                                outerContext
                        )
                val metadata = themeManager.getThemeMetadata(customThemeName)
                activeThemeMetadata = metadata
                
                val decentralized = metadata?.decentralized ?: false
                Log.d(TAG, "Theme $customThemeName isThemeDecentralized = $decentralized")

                val mainFile = metadata?.mainFile ?: "index.html"

                val themeFile = themeManager.getThemeFile(customThemeName, mainFile)
                if (themeFile != null && themeFile.exists()) {
                    Log.d(
                            TAG,
                            "Loading custom HTML from: ${themeFile.absolutePath} (mainFile: $mainFile)"
                    )
                    return themeFile.readText()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading custom theme file, falling back to raw asset", e)
            }
        } else {
            activeThemeMetadata = null

        }

        Log.d(TAG, "Loading base HTML from resource: app.html")
        return context.resources.openRawResource(R.raw.app).bufferedReader().use { it.readText() }
    }

    private fun getActiveCustomThemeName(): String {
        val themeName =
                preferences.getString(SharedPreferencesKeys.ACTIVE_CUSTOM_THEME.key, "") ?: ""
        return if (themeName.equals("Default", ignoreCase = true)) "" else themeName
    }

    private fun tryLoadExternalDebugHtml(): String? {
        val externalFile = File(DEBUG_EXTERNAL_APP_HTML)
        if (!externalFile.exists() || !externalFile.isFile || !externalFile.canRead()) {
            Log.d(TAG, "[HavalDev] External debug HTML not available at $DEBUG_EXTERNAL_APP_HTML")
            return null
        }

        val html = externalFile.readText()
        val normalized = html.lowercase(Locale.ROOT)
        val isValidHtml =
                html.isNotBlank() &&
                        (normalized.contains("<html") || normalized.contains("<!doctype html"))

        if (!isValidHtml) {
            Log.w(
                    TAG,
                    "[HavalDev] External debug HTML exists but is invalid. Falling back to packaged app.html"
            )
            return null
        }

        Log.i(TAG, "[HavalDev] Loading external debug HTML from $DEBUG_EXTERNAL_APP_HTML")
        return html
    }

    private fun isDebuggableApp(): Boolean {
        return (outerContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    override fun carMainScreenOff() {
        ensureUi { root.visibility = View.INVISIBLE }
    }

    override fun carMainScreenOn() {
        ensureUi { root.visibility = View.VISIBLE }
    }

    fun getGearLabel(gear: String?): String {
        val gearLabel =
                when (gear.toString().toIntOrNull()) {
                    2 -> "D"
                    3 -> "P"
                    4 -> "R"
                    else -> "N"
                }
        return gearLabel
    }

    private fun formatTemp(value: String?): String {
        if (value == null || value == "--" || value == "-1" || value == "255") return "null"
        return try {
            val floatVal = value.toFloat()
            // Format to 1 decimal place. Use dot as decimal separator for JS.
            String.format(java.util.Locale.US, "%.1f", floatVal)
        } catch (e: Exception) {
            "null"
        }
    }

    private fun updateGasConsumption(value: Any?, updates: MutableMap<String, String>? = null) {
        val view = webView
        val stringValue = value.toString()
        var metricValue = 0.0f
        var consumptionValue = 0.0f
        var adjustedValue = 0.0f
        var adjustedValueIdle = 0.0f

        if (stringValue.startsWith("{") && stringValue.endsWith("}") && stringValue.contains(",")) {
            try {
                val cleanedString = stringValue.substring(1, stringValue.length - 1)
                val parts = cleanedString.split(',')
                if (parts.size >= 2) {
                    metricValue = parts[0].trim().toFloat()
                    consumptionValue = parts[1].trim().toFloat()
                }
            } catch (e: Exception) {
                metricValue = 0.0f
                consumptionValue = 0.0f
            }
        } else {
            consumptionValue = stringValue.toFloatOrNull() ?: 0.0f
            metricValue = 1.0f
        }

        var mode = "Running"
        if (metricValue == 4.0f) {
            if (consumptionValue > 0.0f) {
                adjustedValueIdle = kotlin.math.truncate(consumptionValue * 10) / 10
                adjustedValue = 0.0f
                mode = "Idle"
            }
        } else if (metricValue == 1.0f) {
            if (consumptionValue > 0.0f) {
                adjustedValue = kotlin.math.truncate(10 * 100 / consumptionValue) / 10
                adjustedValueIdle = 0.0f
                mode = "Running"
            }
        }

        if (updates != null) {
            updates["gasConsumptionMode"] = mode
            updates["gasConsumptionIdle"] = adjustedValueIdle.toString()
            updates["gasConsumption"] = adjustedValue.toString()
        } else {
            evaluateJsIfReady(
                    view,
                    "control('gasConsumptionMode', '$mode')"
            )
            evaluateJsIfReady(
                    view,
                    "control('gasConsumptionIdle', $adjustedValueIdle)"
            )
            evaluateJsIfReady(
                    view,
                    "control('gasConsumption', $adjustedValue)"
            )
        }
    }

    private fun getAdjustedSpeed(value: Any?): String {
        val speedValue = value?.toString()?.toDoubleOrNull() ?: 0.0
        val enableAdjustment =
                preferences.getBoolean(SharedPreferencesKeys.ENABLE_SPEED_ADJUSTMENT.key, false)
        val offset = preferences.getFloat(SharedPreferencesKeys.SPEED_ADJUSTMENT_OFFSET.key, 0f)

        // Formula to match the original instrument cluster
        val adjustedSpeed = speedValue * 1.07 - speedValue / 180 * 0.02
        val finalSpeed =
                if (enableAdjustment) {
                    adjustedSpeed * (1.0 + (offset / 100.0))
                } else {
                    adjustedSpeed
                }

        return finalSpeed.toInt().toString()
    }

    private fun getSavedClusterDisplay(): String {
        if (testDefaultDisplayOverrideActive) {
            return MAP_DISPLAY_TEST_VALUE
        }

        val savedDisplay =
                preferences.getString(
                        SharedPreferencesKeys.CURRENT_CLUSTER_DISPLAY.key,
                        "Normal"
                ) ?: "Normal"

        return normalizeClusterDisplay(savedDisplay)
    }

    private fun normalizeClusterDisplay(display: String): String {
        return when (display) {
            "Normal", "Esportivo", "Reduzido", "Clean", "Mapa" -> display
            else -> "Normal"
        }
    }

    override fun saveClusterDisplay(value: String) {
        testDefaultDisplayOverrideActive = false
        val normalizedDisplay = normalizeClusterDisplay(value)
        preferences.edit()
                .putString(SharedPreferencesKeys.CURRENT_CLUSTER_DISPLAY.key, normalizedDisplay)
                .apply()
        Log.d(TAG, "Cluster display saved: $normalizedDisplay")
    }

    override fun updateWarningUI(isActive: Boolean) {
        // State-change guard. The WebView's JS bridge (setWarningActive)
        // was observed firing every ~580ms while a warning is active,
        // producing a hot loop of cache invalidations, visibility
        // recomputes, syncSecondaryDisplayApps() calls, and JS round-trips
        // that pegged Impulse's main thread (~87% CPU). Doing real work
        // only on the actual boolean flip eliminates the loop without
        // changing semantics — the JS bridge can stay chatty; we no-op.
        if (isActive == isWarningActive) return

        if (isActive && !isWarningActive) {
            lastWarningActiveTime = System.currentTimeMillis()
            Log.w(TAG, "updateWarningUI: warning transition to active, setting onset time")
        }

        isWarningActive = isActive
        lastAppliedConfigs.clear() // Invalidate cache on warning toggle to force re-sync
        if (isActive) {
            Log.w(TAG, "Warning detected. currentCard=$currentCard. Triggering visibility update.")
        } else {
            Log.w(TAG, "Warnings cleared.")
        }

        updateVirtualClusterVisibility(reason = "WARNING_STATE_CHANGED")
        syncSecondaryDisplayApps(3)

        // Propagate current warning state
        evaluateJsIfReady(webView, "control('warningActive', $isActive)")
        themeBridge?.pushOnDataChanged("warningActive", isActive.toString())
    }

    override fun runOnUiThread(action: Runnable) {
        ensureUi { action.run() }
    }

    override fun setCardId(cardId: Int) {
        currentCard = cardId
        Log.d(TAG, "Card ID updated to $cardId")
        ensureUi {
            syncSecondaryDisplayApps(3)
        }
    }

    override fun updateHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis()
    }

    private fun pushVirtualDisplayState(displayId: Int) {
        val themeBridge = this.themeBridge ?: return
        val contextApp = outerContext.applicationContext
        
        val activeAppKey = "app.display.$displayId.active_app"
        val activeAppLabelKey = "app.display.$displayId.active_app_label"
        val activeAppIconKey = "app.display.$displayId.active_app_icon"
        
        if (subscribedKeys.contains(activeAppKey)) {
            val value = br.com.redesurftank.havalshisuku.bridge.VirtualTelemetryManager.getVirtualValue(contextApp, activeAppKey)
            themeBridge.pushOnDataChanged(activeAppKey, value)
        }
        if (subscribedKeys.contains(activeAppLabelKey)) {
            val value = br.com.redesurftank.havalshisuku.bridge.VirtualTelemetryManager.getVirtualValue(contextApp, activeAppLabelKey)
            themeBridge.pushOnDataChanged(activeAppLabelKey, value)
        }
        if (subscribedKeys.contains(activeAppIconKey)) {
            val value = br.com.redesurftank.havalshisuku.bridge.VirtualTelemetryManager.getVirtualValue(contextApp, activeAppIconKey)
            themeBridge.pushOnDataChanged(activeAppIconKey, value)
        }
    }
}
