package com.glidelive.data

import android.content.Context
import com.glidelive.drums.DrumPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Bases de fábrica (assets/patterns.json) y bases del usuario (filesDir/user_patterns.json). */
object PatternRepository {
    private val _all = MutableStateFlow<List<DrumPattern>>(emptyList())
    val all: StateFlow<List<DrumPattern>> = _all
    private var user: List<DrumPattern> = emptyList()
    private var factory: List<DrumPattern> = emptyList()

    @Synchronized
    fun load(ctx: Context) {
        factory = runCatching {
            DrumPattern.parseAll(ctx.assets.open("patterns.json").bufferedReader().use { it.readText() }, true)
        }.getOrDefault(emptyList())
        val f = File(ctx.filesDir, "user_patterns.json")
        user = runCatching { DrumPattern.parseAll(f.readText(), false) }.getOrDefault(emptyList())
        publish()
    }

    private fun publish() { _all.value = factory + user }

    @Synchronized
    fun saveUser(ctx: Context, p: DrumPattern) {
        val mine = p.copy(factory = false)
        user = if (user.any { it.id == mine.id }) user.map { if (it.id == mine.id) mine else it } else user + mine
        File(ctx.filesDir, "user_patterns.json").writeText(DrumPattern.toJsonAll(user))
        publish()
    }

    @Synchronized
    fun deleteUser(ctx: Context, id: String) {
        user = user.filterNot { it.id == id }
        File(ctx.filesDir, "user_patterns.json").writeText(DrumPattern.toJsonAll(user))
        publish()
    }

    fun duplicate(p: DrumPattern): DrumPattern =
        p.copy(id = "user_${System.currentTimeMillis()}", name = p.name + " (copia)", factory = false)
}
