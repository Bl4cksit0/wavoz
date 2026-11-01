package com.bck.wavoz

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Modelo de voz offline (Vosk, español). Ya no viaja dentro de la APK: se descarga una sola vez
 * (por Wi-Fi, o por datos si el usuario lo pide) y queda guardado en el teléfono.
 */
object VoskModel {
    private const val TAG = "WaVoz"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val ZIP_DIR = "vosk-model-small-es-0.42"

    sealed class State {
        object Missing : State()
        /** Descargado, se carga cuando arranca el asistente. */
        object Installed : State()
        object WaitingWifi : State()
        data class Downloading(val percent: Int) : State()
        object Loading : State()
        object Ready : State()
        data class Error(val msg: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var model: Model? = null; private set
    @Volatile private var working = false
    private val listeners = mutableListOf<(Model) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Misma carpeta que usaba StorageService.unpack: los teléfonos que ya lo tenían no lo vuelven a bajar. */
    private fun dir(c: Context) = File(File(c.getExternalFilesDir(null) ?: c.filesDir, "model"), "model-es")

    private fun installed(c: Context): Boolean {
        val d = dir(c)
        return File(d, "am/final.mdl").exists() && File(d, "graph/Gr.fst").exists() && File(d, "conf/model.conf").exists()
    }

    private fun unmetered(c: Context): Boolean {
        return try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork != null && !cm.isActiveNetworkMetered
        } catch (e: Exception) { Log.w(TAG, "network", e); false }
    }

    /** Llama a cb (en el hilo principal) cuando el modelo está cargado. Si falta, lo baja (solo por Wi-Fi salvo allowMetered). */
    fun ensure(c: Context, allowMetered: Boolean = false, cb: ((Model) -> Unit)? = null) {
        val app = c.applicationContext
        model?.let { m -> cb?.let { main.post { it(m) } }; return }
        if (cb != null) synchronized(listeners) { listeners += cb }
        if (working) return
        val have = installed(app)
        if (!have && !allowMetered && !unmetered(app)) { _state.value = State.WaitingWifi; return }
        working = true
        Thread {
            try {
                if (!have) download(app)
                _state.value = State.Loading
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(dir(app).absolutePath)
                model = m
                Log.d(TAG, "modelo de voz cargado")
                _state.value = State.Ready
                val ls = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
                main.post { ls.forEach { it(m) } }
            } catch (e: Exception) {
                Log.e(TAG, "vosk model", e)
                _state.value = State.Error(e.message ?: "error")
            } finally {
                working = false
            }
        }.start()
    }

    private fun download(c: Context) {
        _state.value = State.Downloading(0)
        val base = dir(c).parentFile!!
        val tmp = File(base, "tmp").apply { deleteRecursively(); mkdirs() }
        val con = URL(URL_ZIP).openConnection() as HttpURLConnection
        con.connectTimeout = 20000; con.readTimeout = 60000; con.instanceFollowRedirects = true
        if (con.responseCode != 200) throw Exception("Descarga del modelo: HTTP ${con.responseCode}")
        val total = con.contentLengthLong.coerceAtLeast(1)
        var read = 0L
        val counting = object : java.io.FilterInputStream(con.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    read += n
                    val p = (read * 100 / total).toInt().coerceIn(0, 99)
                    if (p != (_state.value as? State.Downloading)?.percent) _state.value = State.Downloading(p)
                }
                return n
            }
        }
        ZipInputStream(counting.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue   // zip slip
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
            }
        }
        val src = File(tmp, ZIP_DIR).takeIf { it.isDirectory } ?: tmp
        val dst = dir(c)
        dst.deleteRecursively()
        if (!src.renameTo(dst)) throw Exception("No se pudo instalar el modelo")
        tmp.deleteRecursively()
        if (!installed(c)) throw Exception("El modelo descargado está incompleto")
    }

    /** Estado para la UI sin cargar nada (el servicio es el que carga el modelo). */
    fun refreshState(c: Context) {
        if (model != null || working) return
        val app = c.applicationContext
        _state.value = when {
            installed(app) -> State.Installed
            unmetered(app) -> State.Missing
            else -> State.WaitingWifi
        }
    }
}
