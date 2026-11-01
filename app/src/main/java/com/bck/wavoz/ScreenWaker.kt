package com.bck.wavoz

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Prende la pantalla y saca el bloqueo cuando hace falta abrir WhatsApp con el celular en el bolsillo.
 * Con PIN/patrón solo funciona si Smart Lock (dispositivo de confianza = casco) lo mantiene desbloqueado.
 */
object ScreenWaker {
    private var wl: PowerManager.WakeLock? = null
    var wokeByUs = false; private set

    fun screenReady(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java)
        val km = c.getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    /** true si el celular está bloqueado con PIN/patrón/huella y Smart Lock no lo libera. */
    fun secureLocked(c: Context) = c.getSystemService(KeyguardManager::class.java).isDeviceLocked

    @Suppress("DEPRECATION")
    fun wakeAndUnlock(c: Context, cb: (Boolean) -> Unit) {
        if (screenReady(c)) { cb(true); return }
        if (secureLocked(c)) { cb(false); return }
        val pm = c.getSystemService(PowerManager::class.java)
        wokeByUs = !pm.isInteractive
        wl?.let { if (it.isHeld) it.release() }
        wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "wavoz:wake")
            .also { it.acquire(90_000) }
        // El seguro de 6 s queda atado a ESTE pedido: se cancela al terminar y no puede cerrar uno nuevo.
        UnlockActivity.arm(cb) { screenReady(c) }
        val i = Intent(c, UnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val starter: Context = WaAccessibility.instance ?: c
        try { starter.startActivity(i) } catch (_: Exception) { UnlockActivity.finishPending(false) }
    }

    /** Si la pantalla la prendimos nosotros, la apaga de nuevo (bloquea). */
    fun restore() {
        wl?.let { if (it.isHeld) it.release() }; wl = null
        if (wokeByUs) {
            wokeByUs = false
            if (Build.VERSION.SDK_INT >= 28)
                WaAccessibility.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}

/** Actividad invisible que se muestra sobre la pantalla de bloqueo y pide quitarla. */
class UnlockActivity : Activity() {
    companion object {
        private const val TIMEOUT_MS = 6000L
        private val h = Handler(Looper.getMainLooper())
        private var pending: ((Boolean) -> Unit)? = null
        private var timeout: Runnable? = null

        /** Registra el callback del pedido actual con su propio seguro de tiempo. */
        fun arm(cb: (Boolean) -> Unit, readyNow: () -> Boolean) {
            timeout?.let { h.removeCallbacks(it) }
            pending?.invoke(false)          // un pedido anterior que quedó colgado
            pending = cb
            val t = Runnable { if (pending === cb) finishPending(readyNow()) }
            timeout = t
            h.postDelayed(t, TIMEOUT_MS)
        }

        fun finishPending(ok: Boolean) {
            timeout?.let { h.removeCallbacks(it) }; timeout = null
            val p = pending; pending = null; p?.invoke(ok)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { done(true); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { done(true) }
            override fun onDismissError() { done(false) }
            override fun onDismissCancelled() { done(false) }
        })
    }

    private fun done(ok: Boolean) {
        finish()
        overridePendingTransition(0, 0)
        h.post { finishPending(ok) }
    }
}
