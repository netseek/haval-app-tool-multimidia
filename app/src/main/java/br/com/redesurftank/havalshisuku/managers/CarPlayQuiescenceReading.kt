package br.com.redesurftank.havalshisuku.managers

/** Private same-sample diagnostics. No device identifiers or OEM strings belong here. */
internal data class CarPlayQuiescenceReading(val quiet: Boolean?, val diagnostics: Map<String, Any>)

internal enum class CarPlayQuiescenceReason {
    QUIET, MAIN_THREAD, DCM_BINDER_UNAVAILABLE, DCM_FIRMWARE_UNVERIFIED,
    DCM_ONLINE_REPLY_UNKNOWN, DCM_RECORD_NOT_RETAINED, DCM_ACTIVE_PROJECTION,
    DCM_ACTIVE_PROJECTION_UNKNOWN, DCM_BINDER_CHANGED, DCM_SAMPLE_STALE, DCM_SETTLING,
    BT_INPUT_UNKNOWN, BT_BINDER_UNAVAILABLE, BT_SETTLING_OR_FIRMWARE_UNVERIFIED,
    BT_ADAPTER_REPLY_UNKNOWN, BT_ADAPTER_NOT_READY, BT_ADAPTER_CHANGED,
    BT_DEVICE_REPLY_UNKNOWN, BT_DEVICE_SET_UNKNOWN, BT_DEVICE_SET_CHANGED,
    BT_AGGREGATE_REPLY_UNKNOWN, BT_AGGREGATE_CONNECTED, BT_AGGREGATE_CHANGED,
    BT_PROFILE_REPLY_UNKNOWN, BT_PROFILE_NOT_DISCONNECTED, BT_PROFILE_STATE_UNKNOWN,
    BT_BINDER_CHANGED, BT_SAMPLE_STALE, BT_DISCONNECTED
}

internal enum class CarPlayProjectionListShape { NULL, EMPTY, NONEMPTY, INVALID }

internal object CarPlayQuiescenceDiagnostics {
    const val MAX_RECORDS = 8
    private const val MAX_CONNECTIONS = 8

    fun reading(quiet: Boolean?, reason: CarPlayQuiescenceReason, details: Map<String, Any> = emptyMap()) =
        CarPlayQuiescenceReading(quiet, details + ("reason" to reason.name))

    /** Whitelist scalar fields; addresses/names/node strings never reach the returned map. */
    fun record(facts: RetainedBluetoothDevicePolicy.Facts): Map<String, Any> {
        val result = linkedMapOf<String, Any>(
            "type" to facts.type, "accepted" to facts.accepted, "state" to facts.state,
            "available" to facts.available, "active" to facts.active,
            "active_states_count" to facts.activeStates.size,
            "connections_count" to facts.connections.size,
            "connections_truncated" to (facts.connections.size > MAX_CONNECTIONS),
            "usb_mode" to facts.usbDeviceMode,
            "usb_hid_present" to !facts.transportNodes.getOrNull(0).isNullOrEmpty(),
            "iap_present" to !facts.transportNodes.getOrNull(1).isNullOrEmpty(),
            "usb_ncm_present" to !facts.transportNodes.getOrNull(2).isNullOrEmpty(),
            "address_valid" to (RetainedBluetoothDevicePolicy.normalizeAddress(facts.address) != null)
        )
        facts.connections.entries.sortedBy { it.key }.take(MAX_CONNECTIONS).forEachIndexed { index, entry ->
            result["connection_${index}_type"] = entry.key
            result["connection_${index}_state"] = entry.value
        }
        return result
    }
}
