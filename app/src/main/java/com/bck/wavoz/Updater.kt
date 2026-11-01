package com.bck.wavoz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca la última versión publicada en GitHub Releases y se instala sola.
 * Primera vez: Android pide confirmar. Desde ahí (Android 12+), las siguientes son silenciosas.
 */
object Updater {
    const val REPO = "Bl4cksit0/wavoz"
    private const val TAG = "WaVoz"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private val h = Handler(Looper.getMainLooper())

    @Volatile var status: String = ""; private set
    @Volatile private var working = false

    fun currentVersion(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    private fun newer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** force = ignorar el intervalo (botón "Buscar actualización"). onDone corre en el hilo principal. */
    fun check(c: Context, force: Boolean, every: Long = CHECK_EVERY_MS, onDone: ((String) -> Unit)? = null) {
        val app = c.applicationContext
        Prefs.init(app)
        if (working) return
        if (!force && (!Prefs.autoUpdate || System.currentTimeMillis() - Prefs.lastUpdateCheck < every)) return
        working = true
        Thread {
            val msg = try {
                Prefs.lastUpdateCheck = System.currentTimeMillis()
                val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                con.setRequestProperty("Accept", "application/vnd.github+json")
                con.connectTimeout = 15000; con.readTimeout = 15000
                if (con.responseCode != 200) throw Exception("GitHub respondió ${con.responseCode}")
                val rel = JSONObject(con.inputStream.bufferedReader().readText())
                val tag = rel.getString("tag_name")
                val local = currentVersion(app)
                if (!newer(tag, local)) "Tenés la última versión ($local)"
                else {
                    val assets = rel.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.getString("name").endsWith(".apk") } ?: throw Exception("La versión $tag no tiene APK")
                    waitIdle()
                    status = "Descargando $tag…"
                    install(app, URL(apk.getString("browser_download_url")))
                    "Instalando $tag…"
                }
            } catch (e: Exception) {
                Log.w(TAG, "update", e); "No se pudo buscar actualización: ${e.message}"
            }
            status = msg
            working = false
            h.post { onDone?.invoke(msg) }
        }.start()
    }

    /** No actualizar en medio de un dictado. */
    private fun waitIdle() { var n = 0; while (Assistant.busy && n++ < 600) Thread.sleep(1000) }

    private fun install(c: Context, url: URL) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            val con = url.openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true
            con.connectTimeout = 20000; con.readTimeout = 60000
            con.inputStream.use { input -> s.openWrite("wavoz.apk", 0, -1).use { out -> input.copyTo(out); s.fsync(out) } }
            val intent = Intent(c, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(c, id, intent, flags).intentSender)
        }
    }

    fun notify(c: Context, title: String, text: String, intent: PendingIntent?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "Actualizaciones", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(c, "updates").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
        if (intent != null) b.setContentIntent(intent)
        nm.notify(9, b.build())
    }
}

/** Resultado de la instalación. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = WaAccessibility.instance ?: c
                try { starter.startActivity(confirm) } catch (_: Exception) {}
                val pi = PendingIntent.getActivity(c, 3, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Updater.notify(c, "Actualización de WaVoz lista", "Tocá para instalarla", pi)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error"
                Log.w("WaVoz", "install failed: $m")
                Updater.notify(c, "No se pudo actualizar WaVoz", m, null)
            }
        }
    }
}

/** Después de actualizarse (o reiniciar el celular) vuelve a prender el asistente si estaba activo. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        if (Prefs.wanted) DrivingService.startFromBackground(c)
    }
}
