package com.bck.wavoz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Escucha continua y offline (Vosk) de comandos cortos: "escribir", "responder", "repetir",
 * y preguntas de sí/no. Se pausa mientras el asistente habla o usa el reconocimiento de Google.
 *
 * Los Recognizer se crean una sola vez y se reutilizan con reset(); el micrófono (SpeechService)
 * sí se abre y cierra en cada pausa para seguir la ruta de audio (casco / teléfono).
 */
object WakeListener : RecognitionListener {
    private const val TAG = "WaVoz"
    private val h = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    private var wakeRec: Recognizer? = null
    private var wanted = false
    var onCommand: ((String) -> Unit)? = null
    val ready: Boolean get() = model != null

    private val WRITE = setOf("escribir", "escribe", "mensaje")
    private val REPLY = setOf("responder", "contestar")
    private val REPEAT = setOf("repetir", "repite")
    private val GRAMMAR = (WRITE + REPLY + REPEAT).toList() + "[unk]"

    // Comando visto en el parcial anterior: si se repite en el siguiente, se acepta sin esperar el final.
    private var lastPartial: String? = null
    private var fired = false

    fun init(c: Context) {
        VoskModel.ensure(c) { m ->
            model = m
            if (wanted && service == null) startInternal()
        }
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

    fun restart() { if (wanted) { stopInternal(); h.postDelayed({ if (wanted && service == null) startInternal() }, 300) } }

    private fun wakeRecognizer(m: Model): Recognizer =
        wakeRec ?: Recognizer(m, 16000f, JSONArray(GRAMMAR).toString()).also { it.setWords(true); wakeRec = it }

    private fun startInternal() {
        val m = model ?: return
        if (askSvc != null) return
        try {
            val r = wakeRecognizer(m)
            r.reset()
            lastPartial = null; fired = false
            val s = SpeechService(r, 16000f)
            service = s
            s.startListening(this)
            Log.d(TAG, "escuchando comandos")
        } catch (e: Exception) {
            Log.w(TAG, "vosk start", e); stopInternal()
            h.postDelayed({ if (wanted && service == null) startInternal() }, 3000)
        }
    }

    private fun stopInternal() {
        try { service?.stop(); service?.shutdown() } catch (_: Exception) {}
        service = null
    }

    private fun cmdFor(word: String) = when (word) {
        in WRITE -> "write"; in REPLY -> "reply"; in REPEAT -> "repeat"; else -> null
    }

    private fun fire(cmd: String, why: String) {
        if (fired || !wanted) return
        fired = true
        Log.d(TAG, "wake: $cmd ($why)")
        onCommand?.invoke(cmd)
    }

    private fun handleFinal(hyp: String?) {
        lastPartial = null
        if (hyp == null || !wanted) return
        if (fired) { fired = false; return }   // ya se disparó con los parciales de esta frase
        val o = try { JSONObject(hyp) } catch (_: Exception) { return }
        val words = o.optJSONArray("result") ?: return
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optDouble("conf", 0.0) < 0.75) continue
            val cmd = cmdFor(w.optString("word")) ?: continue
            fire(cmd, "final"); fired = false
            return
        }
    }

    private fun handlePartial(hyp: String?) {
        if (hyp == null || !wanted || fired) return
        val text = try { JSONObject(hyp).optString("partial") } catch (_: Exception) { return }
        val cmd = text.split(" ").firstNotNullOfOrNull { cmdFor(it) }
        if (cmd != null && cmd == lastPartial) fire(cmd, "parcial") else lastPartial = cmd
    }

    override fun onResult(hypothesis: String?) = handleFinal(hypothesis)
    override fun onFinalResult(hypothesis: String?) = handleFinal(hypothesis)
    override fun onPartialResult(hypothesis: String?) = handlePartial(hypothesis)
    override fun onError(e: Exception?) {
        Log.w(TAG, "vosk error", e); stopInternal()
        h.postDelayed({ if (wanted && service == null) startInternal() }, 2000)
    }
    override fun onTimeout() { stopInternal(); if (wanted) startInternal() }

    // ------------------------------------------------------------- pregunta corta (sí / no)
    private val YES_W = listOf("sí", "si", "dale", "responder", "contestar", "claro", "bueno", "okey", "listo", "respondo")
    private val NO_W = listOf("no", "nada", "después", "despues", "luego", "seguir", "sigue", "siguiente", "paso", "dejá", "deja")
    private var askSvc: SpeechService? = null
    private var askRec: Recognizer? = null
    private var askCb: ((Boolean?) -> Unit)? = null
    private var askGen = 0

    private fun askRecognizer(m: Model): Recognizer =
        askRec ?: Recognizer(m, 16000f, JSONArray(YES_W + NO_W + "[unk]").toString()).also { it.setWords(true); askRec = it }

    /**
     * Escucha offline una respuesta corta. cb(true)=sí, cb(false)=no, cb(null)=no se entendió / silencio.
     * Devuelve false si el modelo todavía no está listo (usar el reconocimiento de Google).
     */
    fun askYesNo(timeoutMs: Long, cb: (Boolean?) -> Unit): Boolean {
        val m = model ?: return false
        stopInternal(); cancelAsk()
        val g = ++askGen
        return try {
            val r = askRecognizer(m)
            r.reset()
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
                        // Con parciales solo aceptamos palabras de 4+ letras; "sí"/"no" esperan el resultado.
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
    }
}
