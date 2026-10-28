package br.com.redesurftank.havalshisuku.navigation

internal object CarPlayGuidanceReader {
    interface Input {
        fun int(): Int
        fun long(): Long
        fun string(): String?
        fun finish()
    }

    fun read(input: Input): RawCarPlayGuidance? {
        val presence = input.int()
        require(presence == 0 || presence == 1) { "invalid presence" }
        val guidance = if (presence == 0) null else RawCarPlayGuidance(
            input.int(), input.string(), input.int(), input.string(), input.string(),
            input.int(), input.long(), input.int(), input.int(), input.int(), input.int()
        )
        input.finish()
        return guidance
    }
}
