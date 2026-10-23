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

    fun begin(id: String, enableImmediately: Boolean = false) {
        capture = id
        generation = null
        enabled = enableImmediately
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
        androidAutoAvailable = active
        androidAutoObservedAtMs = observedAtMs
    }

    fun onCarPlay(id: String, number: Long, raw: RawCarPlayGuidance?, nowMs: Long) {
        if (!enabled || capture != id || generation != number) return
        receivedAtMs = nowMs
        carPlay = textDirections(raw)
    }

    fun snapshot(nowMs: Long): AndroidAutoNavigationTelemetry.Directions {
        if (androidAuto.active) return androidAuto
        if (!hasFreshInactiveAndroidAutoObservation(nowMs)) {
            return AndroidAutoNavigationTelemetry.inactive()
        }
        val received = receivedAtMs ?: return AndroidAutoNavigationTelemetry.inactive()
        return if (enabled && generation != null && nowMs >= received && nowMs - received < FRESHNESS_MS) carPlay
        else AndroidAutoNavigationTelemetry.inactive()
    }

    fun nextExpiryMs(): Long? {
        val received = receivedAtMs?.takeIf { enabled && generation != null && carPlay.active } ?: return null
        val observed = androidAutoObservedAtMs?.takeIf { androidAutoAvailable == false } ?: return null
        return minOf(received + FRESHNESS_MS, observed + ANDROID_AUTO_OBSERVATION_FRESHNESS_MS)
    }

    private fun hasFreshInactiveAndroidAutoObservation(nowMs: Long): Boolean {
        val observed = androidAutoObservedAtMs ?: return false
        val age = nowMs - observed
        return androidAutoAvailable == false && age >= 0L && age < ANDROID_AUTO_OBSERVATION_FRESHNESS_MS
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
