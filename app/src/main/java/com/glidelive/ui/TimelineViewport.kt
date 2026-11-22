package com.glidelive.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

internal class TimelineViewport(zoom: Float = 30f, left: Double = 0.0) {
    var zoom by mutableFloatStateOf(zoom)
        private set
    var left by mutableDoubleStateOf(left)
        private set

    fun scale(duration: Double) = minOf(zoom, (30000.0 / duration).toFloat())
    fun minimumZoom(duration: Double, viewportDp: Float) =
        minOf(8f, (viewportDp / duration).toFloat()).coerceAtLeast(0.01f)

    fun maxLeft(duration: Double, viewportDp: Float) =
        (duration - viewportDp / scale(duration)).coerceAtLeast(0.0)

    fun scrollTo(seconds: Double, duration: Double, viewportDp: Float) {
        left = seconds.coerceIn(0.0, maxLeft(duration, viewportDp))
    }

    fun transform(factor: Float, oldX: Float, newX: Float, duration: Double, viewportDp: Float) {
        val anchor = left + oldX / scale(duration)
        zoom = (scale(duration) * factor).coerceIn(minimumZoom(duration, viewportDp), minOf(120f, (30000.0 / duration).toFloat()))
        scrollTo(anchor - newX / scale(duration), duration, viewportDp)
    }

    fun fit(end: Double, duration: Double, viewportDp: Float) {
        zoom = (viewportDp / (end + 0.5).coerceAtLeast(1.0)).toFloat()
            .coerceIn(minimumZoom(duration, viewportDp), minOf(120f, (30000.0 / duration).toFloat()))
        left = 0.0
    }

    companion object {
        val Saver = listSaver<TimelineViewport, Number>(
            save = { listOf(it.zoom, it.left) },
            restore = { TimelineViewport(it[0].toFloat(), it[1].toDouble()) },
        )
    }
}
