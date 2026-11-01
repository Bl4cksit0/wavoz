package com.bck.wavoz

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
import android.util.Log

/** Servicio en primer plano ("modo conducción"): mantiene vivo el asistente y permite usar el micrófono. */
class DrivingService : Service() {
    companion object {
        const val CH = "asistente"
        const val ID = 1
        const val A_DICTATE = "dictate"
        const val A_REPLY = "reply"
        const val A_SILENCE = "silence"
        const val A_STOP = "stop"
        const val A_TEST = "test"
        var running = false; private set

        fun intent(c: Context, action: String? = null) =
            Intent(c, DrivingService::class.java).also { if (action != null) it.action = action }

        /** Intento de arranque sin la app en pantalla (tras actualizar/reiniciar). */
        fun startFromBackground(c: Context) {
            if (running) return
            try { c.startForegroundService(intent(c, A_BG)) } catch (e: Exception) {
                Log.w("WaVoz", "bg start", e); askToReopen(c)
            }
        }

        fun askToReopen(c: Context) {
            val pi = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            Updater.notify(c, "WaVoz se detuvo", "Tocá para volver a activar el asistente", pi)
        }
        const val A_BG = "bg"
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Asistente activo", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, build())
        } catch (e: Exception) {
            // Android 14 no deja usar el micrófono si se arrancó desde segundo plano sin excepción.
            Log.e("WaVoz", "startForeground", e)
            if (Build.VERSION.SDK_INT >= 34) try { startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) } catch (_: Exception) {}
            askToReopen(this)
            stopSelf(); return
        }
        running = true
        Prefs.wanted = true
        BtAudio.ensure(this)
        // dar tiempo a que el canal Bluetooth se conecte antes de abrir el micrófono
        h.postDelayed({ if (running) Assistant.start(this) }, 1200)
        h.postDelayed(watchdog, 10000)
    }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    /** Cada 10 s: si se conectó/desconectó el casco, reenruta el audio. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (!Assistant.busy && BtAudio.ensure(this@DrivingService)) Assistant.audioRouteChanged()
            Updater.check(this@DrivingService, force = false)
            h.postDelayed(this, 10000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_DICTATE -> Assistant.dictateNew()
            A_REPLY -> Assistant.replyLast()
            A_SILENCE -> Assistant.silence()
            A_TEST -> Assistant.test()
            A_STOP -> { Prefs.wanted = false; stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        Assistant.stop()
        BtAudio.release(this)
        super.onDestroy()
    }

    private fun pi(action: String, code: Int) =
        PendingIntent.getService(this, code, intent(this, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("WaVoz activo")
            .setContentText("Decí \"escribir\", \"responder\" o \"repetir\"")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "🎤 Dictar", pi(A_DICTATE, 1)).build())
            .addAction(Notification.Action.Builder(null, "↩ Responder", pi(A_REPLY, 2)).build())
            .addAction(Notification.Action.Builder(null, "⏹ Detener", pi(A_STOP, 3)).build())
            .build()
    }
}
