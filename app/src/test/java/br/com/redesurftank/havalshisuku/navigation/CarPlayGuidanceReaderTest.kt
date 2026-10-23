package br.com.redesurftank.havalshisuku.navigation

import org.junit.Assert.*
import org.junit.Test

class CarPlayGuidanceReaderTest {
    private class Input(private val values: List<Any?>) : CarPlayGuidanceReader.Input {
        private var position = 0
        override fun int(): Int = values[position++] as Int
        override fun long(): Long = values[position++] as Long
        override fun string(): String? = values[position++] as String?
        override fun finish() = check(position == values.size)
    }

    private val fields: List<Any?> = listOf(1, 53, "1,234", -17, null, " 500 ", 4, Long.MAX_VALUE, 6, -1, 1, 181)

    @Test fun readsExactWireOrderAndRetainsUnknownUnitsAndNullableStrings() {
        val guidance = CarPlayGuidanceReader.read(Input(fields))!!
        assertEquals(RawCarPlayGuidance(53, "1,234", -17, null, " 500 ", 4, Long.MAX_VALUE, 6, -1, 1, 181), guidance)
    }

    @Test fun nullPresenceMustContainNoTrailingPayload() {
        assertNull(CarPlayGuidanceReader.read(Input(listOf(0))))
        assertTrue(runCatching { CarPlayGuidanceReader.read(Input(listOf(0, 1))) }.isFailure)
    }

    @Test fun rejectsEveryTruncatedFieldSequence() {
        for (length in fields.indices) {
            assertTrue("length=$length", runCatching { CarPlayGuidanceReader.read(Input(fields.take(length))) }.isFailure)
        }
    }

    @Test fun rejectsUnknownPresenceAndAdditionalFields() {
        for (presence in listOf(-1, 2, Int.MAX_VALUE)) {
            assertTrue(runCatching { CarPlayGuidanceReader.read(Input(listOf(presence))) }.isFailure)
        }
        assertTrue(runCatching { CarPlayGuidanceReader.read(Input(fields + 5)) }.isFailure)
    }

    @Test fun timeMustBeReadAsLongWithoutNarrowing() {
        val wrong = fields.toMutableList().apply { set(7, 1) }
        assertTrue(runCatching { CarPlayGuidanceReader.read(Input(wrong)) }.isFailure)
    }
}
