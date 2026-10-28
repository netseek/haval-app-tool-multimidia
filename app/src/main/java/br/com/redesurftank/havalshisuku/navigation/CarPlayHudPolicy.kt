package br.com.redesurftank.havalshisuku.navigation

import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry

class CarPlayHudPolicy {
    var enabled = false
        private set
    private var capture: String? = null
    private var generation: Long? = null
    private var receivedAtMs: Long? = null
    private var carPlay = AndroidAutoNavigationTelemetry.inactive()
    private var androidAuto = AndroidAutoNavigationTelemetry.inactive()
    private var androidAutoAvailable: Boolean? = null
    private var androidAutoObservedAtMs: Long? = null
    private var passiveAndroidAuto: Boolean? = null
    private var passiveObservedAtMs: Long? = null

    fun begin(id: String, enableImmediately: Boolean = false) {
        capture = id
        generation = null
        enabled = enableImmediately
        clearPassiveObservation()
        clearCarPlay()
    }

    fun setEnabled(value: Boolean) {
        enabled = value && capture != null
        clearCarPlay()
    }

    fun connect(id: String, number: Long) {
        if (capture != id) return
        generation = number
        clearCarPlay()
    }

    fun disconnect(id: String, number: Long) {
        if (capture != id || generation != number) return
        generation = null
        clearCarPlay()
    }

    fun end(id: String) {
        if (capture != id) return
        capture = null
        generation = null
        enabled = false
        clearCarPlay()
    }

    fun onAndroidAuto(directions: AndroidAutoNavigationTelemetry.Directions) {
        androidAuto = directions.copy(lanes = directions.lanes.map { it.toList() })
    }

    fun onAndroidAutoAvailability(active: Boolean, observedAtMs: Long) {
        if (observedAtMs < (androidAutoObservedAtMs ?: Long.MIN_VALUE)) return
        androidAutoAvailable = active
        androidAutoObservedAtMs = observedAtMs
    }

    fun onPassiveAndroidAutoAvailability(active: Boolean?, observedAtMs: Long) {
        if (observedAtMs < (passiveObservedAtMs ?: Long.MIN_VALUE)) return
        passiveAndroidAuto = active
        passiveObservedAtMs = observedAtMs
    }

    fun clearPassiveObservation() { passiveAndroidAuto = null; passiveObservedAtMs = null }

    fun onCarPlay(id: String, number: Long, raw: RawCarPlayGuidance?, nowMs: Long) {
        if (!enabled || capture != id || generation != number) return
        receivedAtMs = nowMs
        carPlay = textDirections(raw)
    }

    fun snapshot(nowMs: Long, hostAndroidAutoActive: Boolean = false): AndroidAutoNavigationTelemetry.Directions {
        if (androidAuto.active) return androidAuto
        if (!isAndroidAutoKnownInactive(nowMs, hostAndroidAutoActive)) {
            return AndroidAutoNavigationTelemetry.inactive()
        }
        val received = receivedAtMs ?: return AndroidAutoNavigationTelemetry.inactive()
        return if (enabled && generation != null && nowMs >= received && nowMs - received < FRESHNESS_MS) carPlay
        else AndroidAutoNavigationTelemetry.inactive()
    }

    fun nextExpiryMs(): Long? {
        val received = receivedAtMs?.takeIf { enabled && generation != null && carPlay.active } ?: return null
        val observed = passiveObservedAtMs?.takeIf { passiveAndroidAuto == false } ?: return null
        return minOf(received + FRESHNESS_MS, observed + ANDROID_AUTO_OBSERVATION_FRESHNESS_MS)
    }

    /** An already-expired data lease must not erase the capture's independent hard stop. */
    fun nextRevalidationMs(nowMs: Long, captureDeadlineMs: Long?): Long? =
        listOfNotNull(nextExpiryMs(), captureDeadlineMs).filter { it > nowMs }.minOrNull()

    private fun fresh(observed: Long?, nowMs: Long): Boolean = observed != null &&
        nowMs - observed in 0L until ANDROID_AUTO_OBSERVATION_FRESHNESS_MS

    fun isAndroidAutoKnownInactive(nowMs: Long, hostAndroidAutoActive: Boolean = false): Boolean {
        if (androidAuto.active || hostAndroidAutoActive) return false
        if (androidAutoAvailable == true && fresh(androidAutoObservedAtMs, nowMs) ||
            passiveAndroidAuto == true && fresh(passiveObservedAtMs, nowMs)) return false
        // Legacy non-linked values include INIT/ACTIVATING/DEACTIVATING. They never authorize CP.
        return passiveAndroidAuto == false && fresh(passiveObservedAtMs, nowMs)
    }

    fun diagnostic(nowMs: Long, hostAndroidAutoActive: Boolean = false): Map<String, Any> {
        val next = snapshot(nowMs, hostAndroidAutoActive)
        val inactive = isAndroidAutoKnownInactive(nowMs, hostAndroidAutoActive)
        val reason = when {
            androidAuto.active -> "AA_DIRECTIONS"
            hostAndroidAutoActive -> "AA_HOST_ACTIVE"
            androidAutoAvailable == true && fresh(androidAutoObservedAtMs, nowMs) ||
                passiveAndroidAuto == true && fresh(passiveObservedAtMs, nowMs) -> "AA_LINK_ACTIVE"
            !enabled -> "EXPERIMENT_DISABLED"
            capture == null -> "CAPTURE_MISSING"
            generation == null -> "BINDING_MISSING"
            !inactive -> "AA_INACTIVE_NOT_CONFIRMED"
            receivedAtMs == null -> "CP_DATA_MISSING"
            !fresh(receivedAtMs, nowMs) -> "CP_DATA_NOT_FRESH"
            !carPlay.active -> "CP_GUIDANCE_INELIGIBLE"
            else -> "CARPLAY_TEXT"
        }
        return mapOf("reason" to reason, "aa_known_inactive" to inactive,
            "aa_directions_active" to androidAuto.active, "aa_host_active" to hostAndroidAutoActive,
            "aa_controller_age" to ageBucket(androidAutoObservedAtMs, nowMs),
            "aa_passive_age" to ageBucket(passiveObservedAtMs, nowMs),
            "cp_data_present" to (receivedAtMs != null), "cp_data_eligible" to carPlay.active,
            "cp_data_age" to ageBucket(receivedAtMs, nowMs), "generation_valid" to (generation != null),
            "published_active" to next.active)
    }

    private fun ageBucket(at: Long?, now: Long): String = when {
        at == null -> "missing"
        now < at -> "future"
        now - at < 1000 -> "under_1s"
        now - at < FRESHNESS_MS -> "under_5s"
        else -> "expired"
    }

    private fun clearCarPlay() {
        carPlay = AndroidAutoNavigationTelemetry.inactive()
        receivedAtMs = null
    }

    companion object {
        const val FRESHNESS_MS = 5_000L
        const val ANDROID_AUTO_OBSERVATION_FRESHNESS_MS = 5_000L

        fun textDirections(raw: RawCarPlayGuidance?): AndroidAutoNavigationTelemetry.Directions {
            if (raw == null || raw.routeGuidanceState != CarPlayRouteGuidanceState.ROUTE_SET) {
                return AndroidAutoNavigationTelemetry.inactive()
            }
            val road = raw.mAfterManeuverRoadName?.trim().orEmpty()
            val value = raw.mDistanceBetweenManeuverDisplayStr?.trim().orEmpty()
            val suffix = when (raw.distanceBetweenManeuverUnits) {
                CarPlayDistanceUnit.METERS -> "m"
                CarPlayDistanceUnit.KILOMETERS -> "km"
                else -> ""
            }
            if (road.isEmpty() || value.isEmpty() || suffix.isEmpty()) {
                return AndroidAutoNavigationTelemetry.inactive()
            }
            return AndroidAutoNavigationTelemetry.Directions(active = true, street = road, distance = "$value $suffix")
        }
    }
}
