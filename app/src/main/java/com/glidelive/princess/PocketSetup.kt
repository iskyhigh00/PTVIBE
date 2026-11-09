package com.glidelive.princess

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Pinned upstream release. Android's installer remains responsible for installation consent. */
object PocketSetup {
    const val SPANISH_FILE = "PocketTTS-spanish-FP32.zip"
    private const val APK_FILE = "PocketTTS-Android-Engine-0.5.5.apk"
    private const val BASE = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.5/"
    private const val APK_BYTES = 11_703_560L
    private const val APK_SHA = "4ac728f2c1d92df24f80df8aaaa7a521fe37b22d2e06a9ff79619d756f2ad29b"
    private const val PACK_BYTES = 207_487_086L
    private const val PACK_SHA = "f83dc41bd0d5c7634d385fdadef496881d398ca99d1886ff25d90cb59e384412"
    enum class InstallResult { ALREADY_INSTALLED, NEEDS_PERMISSION, INSTALLER_OPENED }

    fun isInstalled(context: Context) = AndroidNarrator.engines(context).any { it.packageName == AndroidNarrator.POCKET_PACKAGE }

    suspend fun downloadApk(context: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = download(context, APK_FILE, APK_BYTES, APK_SHA, progress)
        validateApk(context, file)
        file
    }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, file: File) {
        check(file.inputStream().use { PocketArtifactCheck.matches(it, APK_BYTES, APK_SHA) }) { "El instalador de Pocket está incompleto o dañado. Vuelve a descargarlo." }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        check(info?.packageName == AndroidNarrator.POCKET_PACKAGE && info.versionName == "0.5.5") { "El archivo no corresponde a Pocket TTS 0.5.5." }
    }

    fun requestInstall(context: Context, file: File): InstallResult {
        if (isInstalled(context)) return InstallResult.ALREADY_INSTALLED
        validateApk(context, file)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return InstallResult.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return InstallResult.INSTALLER_OPENED
    }

    fun openPocket(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(AndroidNarrator.POCKET_PACKAGE)
            ?: error("Instala Pocket TTS antes de importar el paquete español.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads into a location visible from Pocket's standard document picker. No import API exists upstream. */
    suspend fun downloadSpanishPack(context: Context, progress: (Float) -> Unit): Uri = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("pocket-setup", Context.MODE_PRIVATE)
        val previous = prefs.getString("spanish-uri", null)?.let(Uri::parse)
        if (previous != null) {
            val valid = try {
                context.contentResolver.openInputStream(previous)?.use {
                    PocketArtifactCheck.matches(it, PACK_BYTES, PACK_SHA) { currentCoroutineContext().ensureActive() }
                } == true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
            if (valid) { progress(1f); return@withContext previous }
        }
        val file = download(context, SPANISH_FILE, PACK_BYTES, PACK_SHA) { progress(it * .9f) }
        if (Build.VERSION.SDK_INT < 29) {
            // Android 8/9 allow the document picker to browse this app-owned external folder.
            val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Pocket").apply { mkdirs() }
            val target = File(folder, SPANISH_FILE)
            file.copyTo(target, overwrite = true)
            file.delete()
            progress(1f)
            return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, SPANISH_FILE)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/GlideLive/Pocket")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "No se pudo guardar el paquete en Descargas." }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        progress(.9f + .1f * copied / PACK_BYTES)
                    }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            prefs.edit().putString("spanish-uri", uri.toString()).apply()
            file.delete()
            progress(1f)
            uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private suspend fun download(context: Context, name: String, bytes: Long, sha: String, progress: (Float) -> Unit): File {
        val folder = File(context.cacheDir, "updates/pocket").apply { check(isDirectory || mkdirs()) }
        val target = File(folder, name)
        if (target.isFile && target.inputStream().use { PocketArtifactCheck.matches(it, bytes, sha) { currentCoroutineContext().ensureActive() } }) {
            progress(1f)
            return target
        }
        val temporary = File.createTempFile("pocket-", ".part", folder)
        val connection = URL(BASE + name).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            check(connection.responseCode == 200) { "No se pudo descargar Pocket (HTTP ${connection.responseCode})." }
            val hash = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var percent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    check(copied <= bytes) { "La descarga supera el tamaño esperado." }
                    hash.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    val next = (copied * 100 / bytes).toInt()
                    if (next != percent) { progress(copied.toFloat() / bytes); percent = next }
                }
            } }
            check(copied == bytes && PocketArtifactCheck.hex(hash.digest()) == sha) { "La descarga está incompleta o dañada. Inténtalo de nuevo." }
            if (target.exists()) check(target.delete())
            check(temporary.renameTo(target)) { "No se pudo guardar la descarga." }
            return target
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }
}

internal object PocketArtifactCheck {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    inline fun matches(input: InputStream, expectedBytes: Long, expectedSha: String, checkpoint: () -> Unit = {}): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n < 0) break
            size += n
            if (size > expectedBytes) return false
            hash.update(buffer, 0, n)
        }
        return size == expectedBytes && hex(hash.digest()) == expectedSha
    }
}
