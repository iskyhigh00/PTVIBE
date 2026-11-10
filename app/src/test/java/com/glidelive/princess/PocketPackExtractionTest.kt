package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PocketPackExtractionTest {
    private fun archive(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun extractsOnlyExpectedFilesAndNeverFollowsTraversalPaths() {
        val root = Files.createTempDirectory("pocket-test").toFile()
        try {
            PocketPackExtraction.extract(ByteArrayInputStream(archive("models/a.bin" to "abc", "../escaped" to "bad")), root, mapOf("models/a.bin" to 3L))
            assertEquals("abc", root.resolve("models/a.bin").readText())
            assertFalse(root.parentFile.resolve("escaped").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsMissingAndTruncatedModels() {
        val root = Files.createTempDirectory("pocket-test").toFile()
        try {
            assertThrows(IllegalStateException::class.java) {
                PocketPackExtraction.extract(ByteArrayInputStream(archive()), root, mapOf("models/a.bin" to 3L))
            }
            assertThrows(IllegalStateException::class.java) {
                PocketPackExtraction.extract(ByteArrayInputStream(archive("models/a.bin" to "ab")), root, mapOf("models/a.bin" to 3L))
            }
            assertThrows(IllegalStateException::class.java) {
                PocketPackExtraction.extract(ByteArrayInputStream(archive("models/a.bin" to "abcd")), root, mapOf("models/a.bin" to 3L))
            }
        } finally { root.deleteRecursively() }
    }
}
