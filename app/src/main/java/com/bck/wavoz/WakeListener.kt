package com.bck.wavoz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Escucha continua y offline (Vosk) de comandos cortos: "escribir", "responder", "repetir".
 * Se pausa mientras el asistente habla o usa el reconocimiento de Google.
 */
object WakeListener : RecognitionListener {
    private const val TAG = "WaVoz"
    private val h = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var service: SpeechService? = null
    private var rec: Recognizer? = null
    private var wanted = false
    var onCommand: ((String) -> Unit)? = null
    var ready = false; private set

    private val WRITE = setOf("escribir", "escribe", "mensaje")
    private val REPLY = setOf("responder", "contestar")
    private val REPEAT = setOf("repetir", "repite")
    private val GRAMMAR = (WRITE + REPLY + REPEAT).toList() + "[unk]"

    fun init(c: Context) {
        if (model != null || loading) return
        loading = true
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        StorageService.unpack(c.applicationContext, "model-es", "model",
            { m -> model = m; loading = false; ready = true; if (wanted) startInternal() },
            { e -> loading = false; Log.e(TAG, "model unpack", e) })
    }

    fun resume() {
        wanted = true
        if (model != null && service == null) startInternal()
    }

    fun pause() {
        wanted = false
        cancelAsk()
        stopInternal()
    }

    fun restart() { if (wanted) { stopInternal(); h.postDelayed({ if (wanted && service == null) startInternal() }, 500) } }

    private fun startInternal() {
        val m = model ?: return
        try {
            val r = Recognizer(m, 16000f, JSONArray(GRAMMAR).toString())
            r.setWords(true)
            rec = r
            val s = SpeechService(r, 16000f)
            service = s
            s.startListening(this)
        } catch (e: Exception) {
            Log.w(TAG, "vosk start", e); stopInternal()
            h.postDelayed({ if (wanted && service == null) startInternal() }, 3000)
        }
    }

    private fun stopInternal() {
        try { service?.stop(); service?.shutdown() } catch (_: Exception) {}
        service = null
        try { rec?.close() } catch (_: Exception) {}
        rec = null
    }

    private fun handle(hyp: String?) {
        if (hyp == null || !wanted) return
        val o = try { JSONObject(hyp) } catch (_: Exception) { return }
        val words = o.optJSONArray("result") ?: return
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val word = w.optString("word"); val conf = w.optDouble("conf", 0.0)
            if (conf < 0.75) continue
            val cmd = when (word) {
                in WRITE -> "write"; in REPLY -> "reply"; in REPEAT -> "repeat"; else -> null
            } ?: continue
            Log.d(TAG, "wake: $word ($conf)")
            onCommand?.invoke(cmd)
            return
        }
    }

    // ------------------------------------------------------------- pregunta corta (sí / no)
    private val YES_W = listOf("sí", "si", "dale", "responder", "contestar", "claro", "bueno", "okey", "listo", "respondo")
    private val NO_W = listOf("no", "nada", "después", "despues", "luego", "seguir", "sigue", "siguiente", "paso", "dejá", "deja")
    private var askSvc: SpeechService? = null
    private var askRec: Recognizer? = null
    private var askCb: ((Boolean?) -> Unit)? = null
    private var askGen = 0

    /**
     * Escucha offline una respuesta corta. cb(true)=sí, cb(false)=no, cb(null)=no se entendió / silencio.
     * Devuelve false si el modelo todavía no está listo (usar el reconocimiento de Google).
     */
    fun askYesNo(timeoutMs: Long, cb: (Boolean?) -> Unit): Boolean {
        val m = model ?: return false
        stopInternal(); cancelAsk()
        val g = ++askGen
        return try {
            val r = Recognizer(m, 16000f, JSONArray(YES_W + NO_W + "[unk]").toString())
            r.setWords(true)
            askRec = r
            askCb = cb
            val s = SpeechService(r, 16000f)
            askSvc = s
            s.startListening(object : RecognitionListener {
                fun check(hyp: String?, partial: Boolean) {
                    if (g != askGen) return
                    val o = try { JSONObject(hyp ?: return) } catch (_: Exception) { return }
                    val words: List<String> = if (partial) o.optString("partial").split(" ").filter { it.isNotBlank() }
                    else (o.optJSONArray("result") ?: return).let { a ->
                        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optDouble("conf", 0.0) >= 0.6 }.map { it.optString("word") }
                    }
                    for (w in words) {
                        val ans = when (w) { in YES_W -> true; in NO_W -> false; else -> null } ?: continue
                        // Con parciales solo aceptamos palabras de 2+ sílabas o el "no"/"sí" ya cerrado.
                        if (partial && w.length < 4) continue
                        Log.d(TAG, "yes/no: $w")
                        h.post { finishAsk(g, ans) }
                        return
                    }
                }
                override fun onPartialResult(hyp: String?) = check(hyp, true)
                override fun onResult(hyp: String?) = check(hyp, false)
                override fun onFinalResult(hyp: String?) = check(hyp, false)
                override fun onError(e: Exception?) { h.post { finishAsk(g, null) } }
                override fun onTimeout() { h.post { finishAsk(g, null) } }
            })
            h.postDelayed({ finishAsk(g, null) }, timeoutMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "vosk ask", e); cancelAsk(); false
        }
    }

    private fun finishAsk(g: Int, ans: Boolean?) {
        if (g != askGen) return
        val cb = askCb
        cancelAsk()
        cb?.invoke(ans)
    }

    fun cancelAsk() {
        askGen++
        askCb = null
        try { askSvc?.stop(); askSvc?.shutdown() } catch (_: Exception) {}
        askSvc = null
        try { askRec?.close() } catch (_: Exception) {}
        askRec = null
    }

    override fun onResult(hypothesis: String?) = handle(hypothesis)
    override fun onFinalResult(hypothesis: String?) = handle(hypothesis)
    override fun onPartialResult(hypothesis: String?) {}
    override fun onError(e: Exception?) {
        Log.w(TAG, "vosk error", e); stopInternal()
        h.postDelayed({ if (wanted && service == null) startInternal() }, 2000)
    }
    override fun onTimeout() { stopInternal(); if (wanted) startInternal() }
}
