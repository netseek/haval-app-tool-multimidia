package br.com.redesurftank.havalshisuku.experimental

import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import org.apache.commons.net.telnet.TelnetClient
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/** Local transports only. No remote host, credentials, downloads or vehicle commands. */
internal class CarPlayBootstrapRoot {
    class CommandTimeout(message: String, val rawTail: String) : IllegalStateException(message)
    class CommandFailure(message: String, val rawTail: String) : IllegalStateException(message)

    fun run(command: String, timeoutMs: Long = 5_000): String {
        require(command.all { it.code < 128 }) { "Non-ASCII root command" }
        val tag = "cpb_" + UUID.randomUUID().toString().replace("-", "")
        val framed = frameCommand(command, tag)
        // Decode completely before executing, so a damaged packet cannot execute
        // a partially decoded command. Lowercase-safe input stays below TTY limits.
        val packets = wirePackets(framed, tag)
        val completed = Regex("(?m)^${tag}_end=[0-9]+\r?\n")
        val decodeFailed = Regex("(?m)^${tag}_decode_failed\r?\n")
        val client = TelnetClient()
        var executionStarted = false
        try {
            client.connectTimeout = 600
            client.connect("127.0.0.1", 23)
            executionStarted = true
            // Commons Net's background reader must not inject timeout exceptions
            // into a bulk read after it has already copied some response bytes.
            // The caller polls only bytes already available under its own deadline.
            client.soTimeout = 0
            val buffer = ByteArray(8192)
            val result = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            for ((index, packet) in packets.withIndex()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Root packet deadline expired before upload" }
                client.outputStream.write(packet.wire.toByteArray(StandardCharsets.US_ASCII))
                client.outputStream.flush()
                val expected = packet.acknowledgement?.let { Regex("(?m)^${Regex.escape(it)}\r?\n") } ?: completed
                var acknowledged = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    val count = readAvailable(client.inputStream, buffer)
                    if (count == 0) { Thread.sleep(5); continue }
                    check(count >= 0) { "Local root console closed before its response" }
                    val previousSize = result.length
                    result.append(String(buffer, 0, count, StandardCharsets.UTF_8))
                    if (result.length > 1_048_576) error("Root response too large")
                    val scanStart = completionScanStart(previousSize, tag)
                    if (expected.find(result, scanStart) != null) {
                        acknowledged = true
                        break
                    }
                    if (decodeFailed.find(result, scanStart) != null)
                        error("Local root packet could not be decoded; command was not executed")
                }
                if (!acknowledged) throw CommandTimeout(
                    "Local root command timed out (packet=${index + 1}/${packets.size}, " +
                        "wireBytes=${packet.wire.length}, receivedChars=${result.length}, " +
                        "beginSeen=${result.indexOf("${tag}_begin") >= 0})", result.toString().takeLast(16_384))
            }
            return parse(result.toString(), tag)
        } catch (error: Exception) {
            // The same local operation can use Shizuku when Telnet is unavailable.
            // Never retry a command whose execution already began: that could duplicate
            // an injector. Only a failed connection is eligible for the other transport.
            if (executionStarted) throw error
        } finally {
            runCatching { client.disconnect() }
        }
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Local root unavailable"
        }
        val service = checkNotNull(IShizukuService.Stub.asInterface(Shizuku.getBinder()))
        val process = checkNotNull(service.newProcess(arrayOf("sh", "-c", "{\n$framed\n} 2>&1"), null, null))
        var input: ParcelFileDescriptor.AutoCloseInputStream? = null
        try {
            // Close every remote pipe. Leaving copies behind exhausts the root service.
            process.outputStream?.close()
            process.errorStream?.close()
            val stream = ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(process.inputStream))
            input = stream
            val bytes = ByteArrayOutputStream()
            val readFailure = AtomicReference<Throwable?>()
            val reader = Thread({
                try {
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        check(bytes.size() + count <= 1_048_576) { "Root response too large" }
                        bytes.write(chunk, 0, count)
                    }
                } catch (error: Throwable) { readFailure.set(error) }
            }, "carplay-root-response").apply { isDaemon = true; start() }
            reader.join(timeoutMs)
            check(!reader.isAlive && readFailure.get() == null) { "Root response unavailable or timed out" }
            return parse(bytes.toString(StandardCharsets.UTF_8.name()), tag)
        } finally {
            runCatching { process.destroy() } // short shell only; detached owners remain alive
            runCatching { input?.close() }
        }
    }

    private fun parse(raw: String, tag: String): String {
        val text = raw.replace("\r", "")
        val start = text.indexOf("\n${tag}_begin\n")
        val end = Regex("(?m)^${tag}_end=([0-9]+)$").find(text)
        check(start >= 0 && end != null && end.range.first > start) { "Root response framing missing" }
        if (end.groupValues[1] != "0") throw CommandFailure(
            "Local root command failed (exit=${end.groupValues[1]})", text.takeLast(16_384))
        return text.substring(start + tag.length + 8, end.range.first).trimEnd('\n')
    }

    companion object {
        internal fun frameCommand(command: String, tag: String) = """
            printf '\n${tag}_begin\n'
            ( $command ) < /dev/null
            cpb_rc=§?
            printf '\n${tag}_end=%s\n' "§cpb_rc"
        """.trimIndent().replace('§', '$')

        internal fun rawFailure(error: Exception): String? = when (error) {
            is CommandTimeout -> error.rawTail
            is CommandFailure -> error.rawTail
            else -> null
        }

        internal fun readAvailable(input: InputStream, buffer: ByteArray): Int {
            val available = input.available()
            return if (available > 0) input.read(buffer, 0, minOf(available, buffer.size)) else 0
        }

        internal fun completionScanStart(previousSize: Int, tag: String): Int =
            maxOf(0, previousSize - tag.length - 32)

        internal data class WirePacket(val wire: String, val acknowledgement: String? = null)

        internal fun wirePackets(framed: String, tag: String): List<WirePacket> {
            require(tag.matches(Regex("cpb_[a-f0-9]{32}")))
            require(framed.length <= 131_072 && framed.all { it.code in 1..127 }) { "Invalid root frame" }
            val compressed = ByteArrayOutputStream().also { bytes ->
                GZIPOutputStream(bytes).use { it.write(framed.toByteArray(StandardCharsets.US_ASCII)) }
            }.toByteArray()
            val hex = compressed.joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hex.length <= 65_536) { "Root frame exceeds the bounded upload size" }
            fun decode(source: String) =
                "if cpb_decoded=\$(printf '%s' $source | toybox xxd -r -p | gzip -d); then " +
                    "printf '%s\\n' \"\$cpb_decoded\" | sh; " +
                    "else printf '\\n${tag}_decode_failed\\n'; fi; unset cpb_packet cpb_decoded\n"
            val compact = decode("'$hex'")
            if (compact.length < 3800) return listOf(WirePacket(compact))
            // Each append executes and is acknowledged before sending another line.
            // Only the last packet decodes/executes the command, without remote files.
            val packets = hex.chunked(2000).mapIndexed { index, chunk ->
                val assignment = if (index == 0) "cpb_packet='$chunk'" else "cpb_packet=\"\${cpb_packet}$chunk\""
                val acknowledgement = "${tag}_part=$index"
                WirePacket("$assignment; printf '\\n$acknowledgement\\n'\n", acknowledgement)
            } + WirePacket(decode("\"\$cpb_packet\""))
            check(packets.all { it.wire.length < 3800 })
            return packets
        }

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
