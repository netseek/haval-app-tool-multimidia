package br.com.redesurftank.havalshisuku.navigation

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlayCaptureFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun boundsTotalDiskUseAndPreservesLatestCompleteRecordsInOrder() {
        val directory = temporary.newFolder()
        val capture = CarPlayCaptureFiles(directory, 12)
        repeat(10) { capture.append("{\"n\":$it}") }
        val files = (2 downTo 0).map { File(directory, "capture-$it.jsonl") }
        assertTrue(files.sumOf { it.length() } <= 36L)
        assertEquals(listOf("{\"n\":7}", "{\"n\":8}", "{\"n\":9}"), files.flatMap { it.readLines() })
    }

    @Test fun rotationCountsUtf8BytesAndKeepsRecordsWhole() {
        val directory = temporary.newFolder()
        val capture = CarPlayCaptureFiles(directory, 5)
        capture.append("éé")
        capture.append("óó")
        assertEquals("éé\n", File(directory, "capture-1.jsonl").readText())
        assertEquals("óó\n", File(directory, "capture-0.jsonl").readText())
    }

    @Test fun refusesOversizedOrMultilineDataWithoutLosingExistingCapture() {
        val directory = temporary.newFolder()
        val capture = CarPlayCaptureFiles(directory, 8)
        capture.append("first")
        for (invalid in listOf("12345678", "a\nb", "a\rb")) {
            assertTrue(runCatching { capture.append(invalid) }.isFailure)
        }
        assertEquals("first\n", File(directory, "capture-0.jsonl").readText())
    }

    @Test fun restartingWriterAppendsAndPropagatesFilesystemFailure() {
        val directory = temporary.newFolder()
        CarPlayCaptureFiles(directory, 12).append("first")
        CarPlayCaptureFiles(directory, 12).append("next")
        val file = File(directory, "capture-0.jsonl")
        assertEquals("first\nnext\n", file.readText())
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertTrue(runCatching { CarPlayCaptureFiles(directory, 12).append("error") }.isFailure)
    }
}
