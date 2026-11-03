package br.com.redesurftank.havalshisuku.navigation

internal class CarPlayCaptureGate(private val capacity: Int = 64) {
    private var open = true
    private var queued = 0
    private var droppedCount: Long = 0
    val dropped: Long
        @Synchronized get() = droppedCount

    init {
        require(capacity > 0)
    }

    @Synchronized fun acquire(): Boolean {
        if (!open) return false
        if (queued >= capacity) {
            droppedCount++
            return false
        }
        queued++
        return true
    }

    @Synchronized fun release() {
        check(queued > 0)
        queued--
    }

    @Synchronized fun close() {
        open = false
    }

    @Synchronized fun isOpen(): Boolean = open
}
