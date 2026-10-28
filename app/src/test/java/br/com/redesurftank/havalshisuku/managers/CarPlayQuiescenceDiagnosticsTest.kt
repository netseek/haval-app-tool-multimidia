package br.com.redesurftank.havalshisuku.managers

import org.junit.Assert.*
import org.junit.Test

class CarPlayQuiescenceDiagnosticsTest {
    private fun facts() = RetainedBluetoothDevicePolicy.Facts(
        type = 2, accepted = 1, state = 1, connections = mapOf(2 to 3),
        available = 0, active = 0, activeStates = emptyMap(), address = "12:34:56:78:9A:BC",
        usbDeviceMode = false, transportNodes = listOf(null, "", null)
    )

    @Test fun recordDiagnosticsContainOnlyNumbersAndBooleansWithoutIdentifiersOrNodePaths() {
        val input = facts().copy(transportNodes = listOf("/dev/private-hid", "/dev/private-iap", "/dev/private-ncm"))
        val summary = CarPlayQuiescenceDiagnostics.record(input)
        assertTrue(summary.values.all { it is Int || it is Boolean })
        assertEquals(true, summary["address_valid"])
        for (node in listOf("usb_hid_present", "iap_present", "usb_ncm_present")) assertEquals(true, summary[node])
        assertFalse(summary.toString().contains(input.address!!))
        assertFalse(summary.toString().contains("/dev/"))
        assertNull(RetainedBluetoothDevicePolicy.addressIfCandidate(input))
    }

    @Test fun connectionSummariesAreBoundedAndKeepNumericCountsWhenMoreFieldsExist() {
        val summary = CarPlayQuiescenceDiagnostics.record(facts().copy(connections = (20 downTo 0).associateWith { 3 }))
        assertEquals(21, summary["connections_count"])
        assertEquals(true, summary["connections_truncated"])
        assertEquals(0, summary["connection_0_type"])
        assertEquals(7, summary["connection_7_type"])
        assertFalse(summary.containsKey("connection_8_type"))
        assertTrue(summary.size < 32)
    }

    @Test fun invalidAddressIsReportedOnlyAsValidityAndNeverBecomesAnAbsenceCandidate() {
        for (address in listOf("not-a-device-address", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) {
            val input = facts().copy(address = address)
            val summary = CarPlayQuiescenceDiagnostics.record(input)
            assertEquals(false, summary["address_valid"])
            assertFalse(summary.toString().contains(address))
            assertNull(RetainedBluetoothDevicePolicy.addressIfCandidate(input))
        }
    }

    @Test fun diagnosticSnapshotDoesNotChangeTheBooleanOrRetainTheMutableAssembler() {
        val details = mutableMapOf<String, Any>("dcm_record_count" to 1)
        for (quiet in listOf(true, false, null)) {
            val reading = CarPlayQuiescenceDiagnostics.reading(quiet, CarPlayQuiescenceReason.DCM_RECORD_NOT_RETAINED, details)
            assertEquals(quiet, reading.quiet)
            assertEquals("DCM_RECORD_NOT_RETAINED", reading.diagnostics["reason"])
            assertEquals(1, reading.diagnostics["dcm_record_count"])
            details["dcm_record_count"] = 2
            assertEquals(1, reading.diagnostics["dcm_record_count"])
            details["dcm_record_count"] = 1
        }
    }
}
