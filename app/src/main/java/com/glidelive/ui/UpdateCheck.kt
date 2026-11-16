package com.glidelive.ui

import java.io.File
import java.security.MessageDigest

/** Comprobaciones puras de una actualización bajada (para poder probarlas sin Android). */
object UpdateCheck {
    /** Primer valor hexadecimal del archivo .sha256 («hash  nombre»), en minúsculas. */
    fun parseHash(text: String): String = text.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Solo vale un hash completo de 64 caracteres y que coincida exactamente. */
    fun matches(expected: String, actual: String): Boolean = expected.length == 64 && expected == actual
}
