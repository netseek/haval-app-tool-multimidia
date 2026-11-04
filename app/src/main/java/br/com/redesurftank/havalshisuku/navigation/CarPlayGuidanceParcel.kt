package br.com.redesurftank.havalshisuku.navigation

import android.os.Parcel

internal class CarPlayGuidanceParcel(private val parcel: Parcel) : CarPlayGuidanceReader.Input {
    init {
        require(parcel.dataAvail() in 4..MAX_BYTES) { "invalid payload size" }
        require(!parcel.hasFileDescriptors()) { "unexpected file descriptors" }
    }

    override fun int(): Int {
        require(parcel.dataAvail() >= 4) { "truncated int" }
        return parcel.readInt()
    }

    override fun long(): Long {
        require(parcel.dataAvail() >= 8) { "truncated long" }
        return parcel.readLong()
    }

    override fun string(): String? {
        val position = parcel.dataPosition()
        val length = int()
        require(length in -1..MAX_CHARS) { "invalid string length" }
        val encodedBytes = if (length == -1) 0 else ((length + 1) * 2 + 3) and -4
        require(parcel.dataAvail() >= encodedBytes) { "truncated string" }
        parcel.setDataPosition(position)
        val value = parcel.readString()
        require(if (length == -1) value == null else value?.length == length) { "invalid string" }
        require(parcel.dataPosition() == position + 4 + encodedBytes) { "unexpected string encoding" }
        return value
    }

    override fun finish() {
        require(parcel.dataAvail() == 0) { "unexpected trailing data" }
    }

    companion object {
        const val MAX_BYTES = 16_384
        const val MAX_CHARS = 2_048

        fun selfTest(): Int {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInt(0)
                parcel.setDataPosition(0)
                check(CarPlayGuidanceReader.read(CarPlayGuidanceParcel(parcel)) == null)
            } finally {
                parcel.recycle()
            }
            val fixture = RawCarPlayGuidance(53, "1,2", 0, "Rua sintética 🧪", null, 2, Long.MAX_VALUE, 6, -1, 0, 181)
            val complete = Parcel.obtain()
            try {
                complete.writeInt(1)
                complete.writeInt(fixture.mManeuverType)
                complete.writeString(fixture.mDistanceBetweenManeuverDisplayStr)
                complete.writeInt(fixture.mDistanceBetweenManeuverDisplayUnits)
                complete.writeString(fixture.mAfterManeuverRoadName)
                complete.writeString(fixture.mDistanceRemainingDisplayStr)
                complete.writeInt(fixture.mDistanceRemainingDisplayUnits)
                complete.writeLong(fixture.mTimeRemainingToDestination)
                complete.writeInt(fixture.mRouteGuidanceState)
                complete.writeInt(fixture.mJunctionType)
                complete.writeInt(fixture.mDrivingSide)
                complete.writeInt(fixture.mJunctionElementExitAngle)
                complete.setDataPosition(0)
                check(CarPlayGuidanceReader.read(CarPlayGuidanceParcel(complete)) == fixture)
                val length = complete.dataSize()
                for (size in 0 until length) {
                    val truncated = Parcel.obtain()
                    try {
                        truncated.appendFrom(complete, 0, size)
                        truncated.setDataPosition(0)
                        check(runCatching { CarPlayGuidanceReader.read(CarPlayGuidanceParcel(truncated)) }.isFailure)
                    } finally {
                        truncated.recycle()
                    }
                }
                complete.setDataPosition(length)
                complete.writeInt(7)
                complete.setDataPosition(0)
                check(runCatching { CarPlayGuidanceReader.read(CarPlayGuidanceParcel(complete)) }.isFailure)
                for (presence in listOf(-1, 2)) {
                    val invalid = Parcel.obtain()
                    try {
                        invalid.writeInt(presence)
                        invalid.setDataPosition(0)
                        check(runCatching { CarPlayGuidanceReader.read(CarPlayGuidanceParcel(invalid)) }.isFailure)
                    } finally {
                        invalid.recycle()
                    }
                }
                val invalidString = Parcel.obtain()
                try {
                    invalidString.writeInt(1)
                    invalidString.writeInt(0)
                    invalidString.writeInt(MAX_CHARS + 1)
                    invalidString.setDataPosition(0)
                    check(runCatching { CarPlayGuidanceReader.read(CarPlayGuidanceParcel(invalidString)) }.isFailure)
                } finally {
                    invalidString.recycle()
                }
                return length + 6
            } finally {
                complete.recycle()
            }
        }
    }
}
