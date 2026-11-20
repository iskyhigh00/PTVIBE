package com.glidelive.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Mantiene vivo el sonido del cuento cuando se apaga la pantalla o se cambia de app (por ejemplo para escucharlo en el aula).
 * El audio lo reproduce el motor de la app; este servicio solo avisa al sistema que hay algo sonando.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()   // Android exige avisar enseguida; si se pide parar de inmediato, igual no falla
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cuento sonando", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Tu cuento está sonando")
            .setContentText("Toca para volver a GlideLive")
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
    }

    companion object {
        private const val CHANNEL = "cuento_sonando"
        private const val ID = 4107

        /** true si se pudo iniciar (desde segundo plano, Android 12+ puede negarlo). */
        fun start(ctx: Context): Boolean =
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java)) }.isSuccess

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
