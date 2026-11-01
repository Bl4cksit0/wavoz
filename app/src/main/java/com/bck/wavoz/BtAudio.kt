package com.bck.wavoz

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Enruta micrófono y voz al intercom del casco usando el canal "manos libres" (SCO/HFP).
 * Sin esto Android graba con el micrófono del teléfono (inútil en la moto).
 */
object BtAudio {
    var active = false; private set

    private fun am(c: Context) = c.getSystemService(AudioManager::class.java)

    private fun btDevice(c: Context): AudioDeviceInfo? {
        val am = am(c)
        return if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    }

    fun deviceName(c: Context): String? = btDevice(c)?.productName?.toString()

    /** Activa/mantiene el canal Bluetooth. Devuelve true si el estado cambió. */
    @Suppress("DEPRECATION")
    fun ensure(c: Context): Boolean {
        val was = active
        val dev = if (Prefs.helmetMic) btDevice(c) else null
        if (dev == null) { release(c); return was != active }
        val am = am(c)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.communicationDevice?.id != dev.id) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.setCommunicationDevice(dev)
                }
            } else if (!am.isBluetoothScoOn) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
            active = true
        } catch (e: Exception) { Log.w("WaVoz", "sco", e); active = false }
        return was != active
    }

    @Suppress("DEPRECATION")
    fun release(c: Context) {
        if (!active) return
        val am = am(c)
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        active = false
    }

    /** true cuando el canal del casco ya está realmente conectado. */
    @Suppress("DEPRECATION")
    private fun linkUp(c: Context): Boolean {
        val am = am(c)
        return if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.type.let {
            it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET
        } else am.isBluetoothScoOn
    }

    /** Llama a cb apenas el casco está listo (o enseguida si no se usa), con un máximo de 1,5 s. */
    fun whenReady(c: Context, cb: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        fun check() {
            if (!active || linkUp(c) || SystemClock.uptimeMillis() - start > 1500) cb()
            else h.postDelayed({ check() }, 100)
        }
        check()
    }
}
