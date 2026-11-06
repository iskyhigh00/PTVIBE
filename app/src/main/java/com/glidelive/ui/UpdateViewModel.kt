package com.glidelive.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Long, val url: String) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object NeedsPermission : UpdateState
    data class Error(val message: String) : UpdateState
}

class UpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentBuild: Long = PackageInfoCompat.getLongVersionCode(
        app.packageManager.getPackageInfo(app.packageName, 0)
    )

    init { check() }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { fetchLatest() }
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Sin conexión")
            }
        }
    }

    private fun fetchLatest(): UpdateState {
        val c = URL(RELEASE_API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode == 404) return UpdateState.UpToDate
        if (c.responseCode != 200) return UpdateState.Error("GitHub respondió ${c.responseCode}")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val build = json.optString("body").trim().toLongOrNull()
            ?: return UpdateState.Error("Release sin número de build")
        val assets = json.getJSONArray("assets")
        if (assets.length() == 0) return UpdateState.Error("Release sin APK")
        val url = assets.getJSONObject(0).getString("browser_download_url")
        return if (build > currentBuild) UpdateState.Available(build, url) else UpdateState.UpToDate
    }

    fun downloadAndInstall() {
        val available = _state.value as? UpdateState.Available ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        _state.value = UpdateState.Downloading(0)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(available.url) }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                _state.value = available
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Error al descargar")
            }
        }
    }

    private fun download(url: String): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "GlideLive.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (c.responseCode != 200) error("Descarga falló (${c.responseCode})")
        val total = c.contentLengthLong
        var done = 0L
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) _state.value = UpdateState.Downloading((done * 100 / total).toInt())
                }
            }
        }
        return out
    }

    private companion object {
        const val RELEASE_API = "https://api.github.com/repos/iskyhigh00/PTVIBE/releases/tags/latest"
    }
}
