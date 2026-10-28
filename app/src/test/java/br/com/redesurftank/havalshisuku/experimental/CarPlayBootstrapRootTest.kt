package br.com.redesurftank.havalshisuku.experimental

import org.junit.Assert.*
import org.junit.Test
import org.apache.commons.net.telnet.TelnetClient
import java.io.InputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CarPlayBootstrapRootTest {
    private val tag = "cpb_0123456789abcdef0123456789abcdef"

    private fun frame(command: String) = CarPlayBootstrapRoot.frameCommand(command, tag)

    private fun compactWire(framed: String, tag: String) =
        CarPlayBootstrapRoot.wirePackets(framed, tag).single().wire

    private fun execute(wire: String): String {
        // The Mini has xxd directly; the target exposes the same utility via toybox.
        val process = ProcessBuilder("sh", "-c", wire.replace("toybox xxd", "xxd"))
            .redirectErrorStream(true).start()
        try {
            assertTrue("Shell transport did not finish", process.waitFor(5, TimeUnit.SECONDS))
            return process.inputStream.bufferedReader().readText()
        } finally { process.destroyForcibly() }
    }

    @Test fun `poll never requests absent bytes or a future portion of a bulk read`() {
        var ready = false
        val input = object : InputStream() {
            override fun available() = if (ready) 3 else 0
            override fun read(): Int = throw AssertionError("Unbounded single-byte read")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(ready)
                assertEquals(3, length)
                "end".toByteArray().copyInto(buffer, offset)
                ready = false
                return 3
            }
        }
        val buffer = ByteArray(8192)
        assertEquals(0, CarPlayBootstrapRoot.readAvailable(input, buffer))
        ready = true
        assertEquals(3, CarPlayBootstrapRoot.readAvailable(input, buffer))
        assertEquals("end", String(buffer, 0, 3))
        assertEquals(0, CarPlayBootstrapRoot.readAvailable(input, buffer))
    }

    @Test fun `Commons background reader preserves fragmented frames without socket timeouts`() {
        val expected = "console echo\r\n${tag}_begin\r\n" + "response".repeat(200) + "\r\n${tag}_end=0\r\n"
        val writerFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val writer = Thread {
                try {
                    server.accept().use { peer ->
                        for (chunk in expected.chunked(17)) {
                            peer.getOutputStream().write(chunk.toByteArray())
                            peer.getOutputStream().flush()
                            Thread.sleep(3)
                        }
                    }
                } catch (error: Throwable) { writerFailure.set(error) }
            }.apply { isDaemon = true; start() }
            val client = TelnetClient()
            try {
                client.connect("127.0.0.1", server.localPort)
                client.soTimeout = 0
                val received = StringBuilder()
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (received.length < expected.length && System.nanoTime() < deadline) {
                    val count = CarPlayBootstrapRoot.readAvailable(client.inputStream, buffer)
                    if (count > 0) received.append(String(buffer, 0, count)) else Thread.sleep(1)
                }
                assertEquals(expected, received.toString())
                writer.join(1000)
                assertNull(writerFailure.get())
            } finally { client.disconnect() }
        }
    }

    @Test fun `compact packet preserves uppercase quotes and literal shell syntax`() {
        val literal = "-R qjs 'quoted' \$HOME `literal` \$(literal) %s"
        val wire = compactWire(frame("printf '%s\\n' " +
            CarPlayBootstrapRoot.quote(literal)), tag)
        assertTrue(wire.length < 3800)
        assertEquals(1, wire.count { it == '\n' })
        assertEquals(wire, wire.lowercase())
        val output = execute(wire)
        assertTrue(output.contains("\n$literal\n"))
        assertTrue(output.contains("\n${tag}_end=0\n"))
        assertFalse(output.contains("${tag}_decode_failed"))
    }

    @Test fun `command failure keeps its original status frame`() {
        val output = execute(compactWire(frame("exit 7"), tag))
        assertTrue(output.contains("\n${tag}_end=7\n"))
    }

    @Test fun `child stdin cannot consume the root shell control stream`() {
        val padded = frame("cat; printf 'child-done\\n'")
            .replace("\ncpb_rc=", "\n#" + "x".repeat(12_000) + "\ncpb_rc=")
        val output = execute(compactWire(padded, tag))
        assertTrue(output.contains("\nchild-done\n"))
        assertTrue(output.contains("\n${tag}_end=0\n"))
        assertFalse(output.contains("cpb_rc=\$?"))
    }

    @Test fun `unisolated stdin demonstrates loss of the final control frame`() {
        val unisolated = frame("cat").replace(" < /dev/null", "")
            .replace("\ncpb_rc=", "\n#" + "x".repeat(12_000) + "\ncpb_rc=")
        val output = execute(compactWire(unisolated, tag))
        assertTrue(output.contains("cpb_rc=\$?"))
        assertFalse(output.contains("\n${tag}_end=0\n"))
    }

    @Test fun `damaged gzip never executes even a fully decompressed prefix`() {
        val directory = Files.createTempDirectory("carplay-root-wire-").toFile()
        try {
            val marker = directory.resolve("must-not-exist")
            val wire = compactWire(frame("touch " +
                CarPlayBootstrapRoot.quote(marker.absolutePath)), tag)
            val hex = Regex("printf '%s' '([a-f0-9]+)'").find(wire)!!.groupValues[1]
            val index = hex.length - 16 // CRC32, after all compressed body bytes.
            val damaged = hex.substring(0, index) + (if (hex[index] == '0') '1' else '0') + hex.substring(index + 1)
            val output = execute(wire.replace(hex, damaged))
            assertFalse(marker.exists())
            assertTrue(output.contains("\n${tag}_decode_failed\n"))
            assertFalse(output.contains("\n${tag}_begin\n"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `oversized packet is rejected before any connection or execution`() {
        val random = Random(31)
        val body = buildString { repeat(200_000) { append(('a'.code + random.nextInt(26)).toChar()) } }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayBootstrapRoot.wirePackets(frame("printf '%s' '$body'"), tag)
        }
    }

    @Test fun `fragmented echoed traffic cannot impersonate or hide the final frame`() {
        val wire = compactWire(frame(":"), tag)
        val completed = Regex("(?m)^${tag}_end=[0-9]+\r?\n")
        val failed = Regex("(?m)^${tag}_decode_failed\r?\n")
        assertNull(completed.find(wire))
        assertNull(failed.find(wire)) // The decoder branch is visible in console echo.
        val response = StringBuilder(wire + "x".repeat(20_000) + "\r\n")
        val ending = "${tag}_end=0\r\n"
        ending.forEachIndexed { index, character ->
            val previousSize = response.length
            response.append(character)
            val match = completed.find(response, CarPlayBootstrapRoot.completionScanStart(previousSize, tag))
            assertEquals(index == ending.lastIndex, match != null)
        }
    }

    @Test fun `large command uploads acknowledged bounded lines before its sole execution`() {
        val directory = Files.createTempDirectory("carplay-root-packets-").toFile()
        try {
            val marker = directory.resolve("execution-count")
            val random = Random(83)
            val comment = buildString { repeat(7000) { append(('a'.code + random.nextInt(26)).toChar()) } }
            val packets = CarPlayBootstrapRoot.wirePackets(frame("# $comment\nprintf x >> " +
                CarPlayBootstrapRoot.quote(marker.absolutePath)), tag)
            assertTrue(packets.size > 2)
            assertTrue(packets.all { it.wire.length < 3800 && it.wire.count { ch -> ch == '\n' } == 1 })
            assertEquals(packets.size - 1, packets.mapNotNull { it.acknowledgement }.distinct().size)
            assertNull(packets.last().acknowledgement)
            val partial = execute(packets.dropLast(1).joinToString("") { it.wire })
            assertFalse(marker.exists())
            packets.dropLast(1).forEach { packet ->
                assertTrue(partial.contains("\n${packet.acknowledgement}\n"))
                assertNull(Regex("(?m)^${Regex.escape(packet.acknowledgement!!)}\r?\n").find(packet.wire))
            }
            val output = execute(packets.joinToString("") { it.wire })
            assertEquals("x", marker.readText())
            assertTrue(output.contains("\n${tag}_end=0\n"))
        } finally { directory.deleteRecursively() }
    }
}
