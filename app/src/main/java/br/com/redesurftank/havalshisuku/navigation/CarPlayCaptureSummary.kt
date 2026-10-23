package br.com.redesurftank.havalshisuku.navigation

enum class CarPlayCapturePayloadKind {
    EMPTY, INACTIVE, CANDIDATE, UNCONFIRMED
}

data class CarPlayCaptureSummary(
    val empty: Long = 0,
    val inactive: Long = 0,
    val candidates: Long = 0,
    val unconfirmed: Long = 0,
    val lastKind: CarPlayCapturePayloadKind? = null
) {
    fun received(raw: RawCarPlayGuidance?): CarPlayCaptureSummary = when (classify(raw)) {
        CarPlayCapturePayloadKind.EMPTY -> copy(empty = empty + 1, lastKind = CarPlayCapturePayloadKind.EMPTY)
        CarPlayCapturePayloadKind.INACTIVE -> copy(inactive = inactive + 1, lastKind = CarPlayCapturePayloadKind.INACTIVE)
        CarPlayCapturePayloadKind.CANDIDATE -> copy(candidates = candidates + 1, lastKind = CarPlayCapturePayloadKind.CANDIDATE)
        CarPlayCapturePayloadKind.UNCONFIRMED -> copy(unconfirmed = unconfirmed + 1, lastKind = CarPlayCapturePayloadKind.UNCONFIRMED)
    }

    companion object {
        fun classify(raw: RawCarPlayGuidance?): CarPlayCapturePayloadKind {
            if (raw == null) return CarPlayCapturePayloadKind.EMPTY
            return when (raw.routeGuidanceState) {
                CarPlayRouteGuidanceState.NO_ROUTE,
                CarPlayRouteGuidanceState.ARRIVED,
                CarPlayRouteGuidanceState.LOADING,
                CarPlayRouteGuidanceState.LOCATING -> CarPlayCapturePayloadKind.INACTIVE
                CarPlayRouteGuidanceState.ROUTE_SET,
                CarPlayRouteGuidanceState.REROUTING,
                CarPlayRouteGuidanceState.PROCEED_TO_ROUTE -> {
                    val hasContent = raw.mManeuverType >= 0 ||
                        !raw.mAfterManeuverRoadName.isNullOrBlank() ||
                        !raw.mDistanceBetweenManeuverDisplayStr.isNullOrBlank() ||
                        !raw.mDistanceRemainingDisplayStr.isNullOrBlank()
                    if (hasContent) CarPlayCapturePayloadKind.CANDIDATE else CarPlayCapturePayloadKind.UNCONFIRMED
                }
                CarPlayRouteGuidanceState.UNKNOWN -> CarPlayCapturePayloadKind.UNCONFIRMED
            }
        }
    }
}
