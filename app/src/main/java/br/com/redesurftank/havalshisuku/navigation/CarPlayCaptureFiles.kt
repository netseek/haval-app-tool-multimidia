package br.com.redesurftank.havalshisuku.navigation

import java.io.File

internal class CarPlayCaptureFiles(
    private val directory: File,
    private val maxBytes: Int = 1_048_576
) {
    init {
        require(maxBytes > 0)
        check(directory.isDirectory || directory.mkdirs()) { "capture directory unavailable" }
    }

    fun append(line: String) {
        require(!line.contains('\n') && !line.contains('\r'))
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "capture record too large" }
        val current = File(directory, "capture-0.jsonl")
        check(!current.exists() || current.isFile) { "capture path is not a file" }
        if (current.length() + bytes.size > maxBytes) {
            val oldest = File(directory, "capture-2.jsonl")
            check(!oldest.exists() || oldest.isFile) { "capture path is not a file" }
            check(!oldest.exists() || oldest.delete()) { "capture rotation failed" }
            for (index in 1 downTo 0) {
                val source = File(directory, "capture-$index.jsonl")
                check(!source.exists() || source.isFile) { "capture path is not a file" }
                check(!source.exists() || source.renameTo(File(directory, "capture-${index + 1}.jsonl"))) {
                    "capture rotation failed"
                }
            }
        }
        current.appendBytes(bytes)
    }
}
