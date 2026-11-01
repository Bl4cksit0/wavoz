package com.bck.wavoz

import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** Mensaje entrante ya filtrado, listo para leer. */
data class Incoming(val chat: String, val isGroup: Boolean, val lines: List<Pair<String, String>>)

/** Acción "Responder" de una notificación de WhatsApp (igual que la usa Android Auto). */
class ReplyTarget(val chat: String, val pkg: String, val action: Notification.Action)

data class Target(val name: String, val reply: ReplyTarget?, val phone: String?, val isGroup: Boolean = false)

/**
 * Motor del asistente, 100% por voz. Todo corre en el hilo principal.
 *
 * Reposo: WakeListener escucha "escribir" / "responder" / "repetir".
 * Escribir: ¿A quién? -> abre el chat -> dictado (termina tras 5 s de silencio)
 *           -> "¿Está listo?" -> "enviar" | "seguir" | "borrar" | "cancelar".
 */
object Assistant : TextToSpeech.OnInitListener {
    private const val TAG = "WaVoz"
    private const val SILENCE_MS = 5000L
    val WA_PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")

    private lateinit var app: Context
    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeak = mutableListOf<() -> Unit>()
    private val callbacks = HashMap<String, () -> Unit>()
    private var uttSeq = 0
    private var ttsOnBt: Boolean? = null
    private var ttsRate = -1f
    private var gen = 0
    private var recognizer: SpeechRecognizer? = null
    private var focusReq: AudioFocusRequest? = null
    private val queue = ArrayDeque<Incoming>()
    private val recentlySent = ArrayDeque<String>()

    var running = false; private set
    var busy = false; private set
    var lastChat: String? = null; private set
    private var lastIncoming: Incoming? = null

    val replies = HashMap<String, ReplyTarget>()

    /** Mensaje en preparación. viaChat = se escribe en el chat abierto con accesibilidad. */
    private class Draft(val t: Target, val viaChat: Boolean) { var text = "" }

    // ------------------------------------------------------------------ ciclo de vida
    fun start(c: Context) {
        app = c.applicationContext
        Prefs.init(app)
        if (tts == null) tts = TextToSpeech(app, this)
        running = true
        WaContacts.load(app)
        WakeListener.onCommand = { cmd -> h.post { onWake(cmd) } }
        WakeListener.init(app)
        if (!busy) goIdle()
    }

    fun stop() {
        running = false
        interrupt()
        queue.clear()
        WakeListener.pause()
        tts?.shutdown(); tts = null; ttsReady = false
        busy = false
    }

    override fun onInit(status: Int) {
        h.post {
            val t = tts ?: return@post
            if (status != TextToSpeech.SUCCESS) { Log.e(TAG, "TTS init failed"); return@post }
            if (t.setLanguage(Locale.getDefault()) < 0) t.setLanguage(Locale("es", "ES"))
            try {
                val lang = t.voice?.locale?.language ?: "es"
                val cur = t.voice
                if (cur == null || cur.isNetworkConnectionRequired) {
                    t.voices?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired &&
                        !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                        ?.sortedWith(compareBy({ it.locale.country != (cur?.locale?.country ?: Locale.getDefault().country) }, { -it.quality }))
                        ?.firstOrNull()?.let { t.voice = it; Log.d(TAG, "voz offline: ${it.name}") }
                }
            } catch (e: Exception) { Log.w(TAG, "voices", e) }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { h.post { id?.let { callbacks.remove(it)?.invoke() } } }
                @Deprecated("") override fun onError(id: String?) { onDone(id) }
                override fun onError(id: String?, code: Int) { onDone(id) }
            })
            ttsOnBt = null; ttsRate = -1f
            ttsReady = true
            pendingSpeak.toList().forEach { it() }; pendingSpeak.clear()
        }
    }

    // ------------------------------------------------------------------ entradas
    private fun onWake(cmd: String) {
        if (!running || busy) return
        when (cmd) {
            "write" -> { beginBusy(); askTarget(0) }
            "reply" -> replyLast()
            "repeat" -> { val l = lastIncoming; beginBusy(); if (l == null) say("No hay mensajes para repetir.") else say(compose(l)) }
        }
    }

    fun onIncoming(inc: Incoming) {
        if (!running) return
        if (Prefs.onlyHeadset && !headsetConnected()) return
        queue.addLast(inc)
        if (!busy) { beginBusy(); pump() }
    }

    fun dictateNew() { if (!running) return; interrupt(); beginBusy(); askTarget(0) }

    fun replyLast() {
        if (!running) return
        interrupt(); beginBusy()
        val c = lastChat ?: run { say("Todavía no hay mensajes para responder."); return }
        replyTo(targetFor(c))
    }

    fun silence() { interrupt(); queue.clear(); goIdle() }

    fun test() = onIncoming(Incoming("Prueba", false, listOf("WaVoz" to "Hola, así se van a escuchar tus mensajes.")))

    fun isOwnEcho(text: String) = recentlySent.any { Matcher.norm(it) == Matcher.norm(text) }

    /** Lo llama el servicio cuando cambia la ruta de audio (casco conectado/desconectado). */
    fun audioRouteChanged() { if (running && !busy) WakeListener.restart() }

    // ------------------------------------------------------------------ estados
    private fun beginBusy() { busy = true; WakeListener.pause() }

    private fun goIdle() {
        busy = false
        abandonFocus()
        if (running && Prefs.wakeWord) WakeListener.resume()
    }

    private fun pump() {
        var inc = queue.removeFirstOrNull() ?: run { goIdle(); return }
        // Juntar los mensajes del mismo chat que llegaron mientras tanto: se leen de una y se pregunta una sola vez.
        queue.filter { it.chat == inc.chat }.forEach { more -> inc = Incoming(inc.chat, inc.isGroup, inc.lines + more.lines) }
        queue.removeAll { it.chat == inc.chat }
        lastChat = inc.chat; lastIncoming = inc
        speak(compose(inc)) {
            val t = targetFor(inc.chat)
            if (Prefs.askReply && (t.reply != null || t.phone != null)) {
                askYesNo("¿Querés responder?") { yes -> if (yes == true) replyTo(t) else pump() }
            } else pump()
        }
    }

    // ------------------------------------------------------------------ escribir
    private fun askTarget(attempt: Int) {
        listen(if (attempt == 0) "¿A quién le escribo?" else "No te entendí. ¿A quién?") { heard ->
            when {
                heard == null -> if (attempt < 1) askTarget(attempt + 1) else say("No te escuché. Cancelado.")
                Matcher.isCancel(heard) -> say("Cancelado.")
                else -> {
                    val (t, score) = resolve(heard) ?: (null to 0.0)
                    when {
                        t == null -> if (attempt < 1) askTarget(attempt + 1) else say("No encontré a $heard en WhatsApp.")
                        score >= 0.9 -> openAndDictate(t)
                        else -> listen("¿${t.name}?") { a ->
                            when (a?.let { Matcher.isYes(it) }) {
                                true -> openAndDictate(t)
                                else -> if (attempt < 1) askTarget(attempt + 1) else say("Cancelado.")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 1) Si hay respuesta por notificación: dictado directo, sin pantalla (funciona en el bolsillo).
     * 2) Si no: prende la pantalla (Smart Lock), abre el chat, y al terminar la vuelve a apagar.
     */
    private fun openAndDictate(t: Target) {
        if (t.reply != null) { startDictation(Draft(t, false)); return }
        val acc = WaAccessibility.instance
            ?: run { say("Para escribirle a ${t.name} tengo que abrir WhatsApp. Activá la accesibilidad de WaVoz."); return }
        if (!ScreenWaker.screenReady(app) && ScreenWaker.secureLocked(app)) {
            say("El celular está bloqueado. Desbloquealo, o configurá el casco como dispositivo de confianza en Smart Lock.")
            return
        }
        val g = gen
        var opened: Boolean? = null
        var spoken = false
        fun proceed() {
            if (g != gen || opened == null || !spoken) return
            if (opened == true) startDictation(Draft(t, true))
            else { ScreenWaker.restore(); say("No pude abrir el chat de ${t.name}.") }
        }
        speak("Abriendo ${t.name}") { spoken = true; proceed() }
        val cb: (Boolean) -> Unit = { ok -> h.post { opened = ok; proceed() } }
        ScreenWaker.wakeAndUnlock(app) { ready ->
            h.post {
                if (g != gen) return@post
                if (!ready) { opened = false; proceed(); return@post }
                if (t.phone != null) acc.openByPhone(t.phone, cb) else acc.openByName(t.name, cb)
            }
        }
    }

    /** Responder a quien te escribió: usa la acción de la notificación (funciona con pantalla bloqueada). */
    private fun replyTo(t: Target) {
        if (t.reply != null) startDictation(Draft(t, false)) else openAndDictate(t)
    }

    private fun startDictation(d: Draft, prompt: String = "Dictá el mensaje") {
        speak(prompt) { collect(d, SystemClock.elapsedRealtime(), first = true) }
    }

    /** Junta frases hasta que haya 5 s de silencio. */
    private fun collect(d: Draft, lastSpeech: Long, first: Boolean = false) {
        recognize(beep = first) { r ->
            val now = SystemClock.elapsedRealtime()
            if (r != null) {
                val n = Matcher.norm(r)
                when {
                    n == "enviar" || n == "enviar mensaje" || n == "envialo" ->
                        if (d.text.isNotBlank()) send(d) else collect(d, now)
                    Matcher.isCancel(r) -> cancelDraft(d)
                    else -> {
                        d.text = (d.text + " " + r).trim().replaceFirstChar { it.uppercase() }
                        if (d.viaChat) WaAccessibility.instance?.typeInChat(d.text)
                        collect(d, now)
                    }
                }
            } else {
                when {
                    d.text.isBlank() && now - lastSpeech > 15000 -> cancelDraft(d, "No escuché ningún mensaje. Cancelado.")
                    d.text.isNotBlank() && now - lastSpeech >= SILENCE_MS -> askReady(d, 0)
                    else -> h.postDelayed({ collect(d, lastSpeech) }, 200)
                }
            }
        }
    }

    private fun askReady(d: Draft, attempt: Int) {
        val prompt = if (attempt == 0) "¿Está listo? Dice: ${d.text}. Decí enviar, seguir, borrar o cancelar."
                     else "Decí enviar, seguir, borrar o cancelar."
        listen(prompt) { ans ->
            when (ans?.let { Matcher.readyCommand(it) }) {
                "send" -> send(d)
                "more" -> startDictation(d, "Seguí")
                "clear" -> { d.text = ""; if (d.viaChat) WaAccessibility.instance?.typeInChat(""); startDictation(d, "Dictá de nuevo") }
                "cancel" -> cancelDraft(d)
                else -> if (attempt < 2) askReady(d, attempt + 1) else cancelDraft(d)
            }
        }
    }

    private fun cancelDraft(d: Draft, msg: String = "Cancelado.") {
        if (d.viaChat) WaAccessibility.instance?.let { it.typeInChat(""); leaveChat(it) }
        say(msg)
    }

    private fun send(d: Draft) {
        rememberSent(d.text)
        val acc = WaAccessibility.instance
        if (d.viaChat && acc != null) {
            val g = gen
            acc.sendInChat(d.text) { ok ->
                h.post {
                    if (g != gen) return@post
                    if (ok) { leaveChat(acc); say("Enviado.") }
                    else { ScreenWaker.restore(); say("No pude enviarlo. Quedó escrito en el chat.") }
                }
            }
            return
        }
        val r = d.t.reply
        if (r != null && sendReply(r, d.text)) say("Enviado.") else say("No pude enviar el mensaje.")
    }

    /** Sale de WhatsApp: vuelve a la app anterior o, si prendimos la pantalla, la apaga. */
    private fun leaveChat(acc: WaAccessibility) {
        if (ScreenWaker.wokeByUs) { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME); h.postDelayed({ ScreenWaker.restore() }, 600) }
        else { acc.returnToPrevious(); ScreenWaker.restore() }
    }

    private fun sendReply(r: ReplyTarget, msg: String): Boolean {
        val inputs = r.action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val b = Bundle()
            inputs.forEach { b.putCharSequence(it.resultKey, msg) }
            RemoteInput.addResultsToIntent(inputs, intent, b)
            r.action.actionIntent.send(app, 0, intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "reply failed", e); replies.remove(r.chat); false
        }
    }

    // ------------------------------------------------------------------ destinatarios
    private fun phoneFor(name: String): String? {
        val n = Matcher.norm(name)
        return Prefs.contacts().firstOrNull { Matcher.norm(it.name) == n }?.phone
            ?: WaContacts.list.firstOrNull { Matcher.norm(it.name) == n }?.phone
    }

    fun targetFor(chat: String): Target {
        val seen = Prefs.seen()[chat]
        return Target(chat, replies[chat], phoneFor(chat), seen?.isGroup ?: false)
    }

    private fun resolve(q: String): Pair<Target, Double>? {
        val cands = LinkedHashMap<String, Target>()
        fun add(t: Target) {
            val k = Matcher.norm(t.name)
            val old = cands[k]
            cands[k] = if (old == null) t else Target(old.name, old.reply ?: t.reply, old.phone ?: t.phone, old.isGroup || t.isGroup)
        }
        replies.values.forEach { add(targetFor(it.chat)) }
        Prefs.contacts().forEach { add(Target(it.name, null, it.phone)) }
        WaContacts.list.forEach { add(Target(it.name, null, it.phone)) }
        Prefs.seen().forEach { (name, s) -> if (s.isGroup) add(Target(name, replies[name], null, true)) }
        Prefs.rules().forEach { (name, r) -> if (r.isGroup) add(Target(name, replies[name], null, true)) }
        val best = cands.values.map { it to Matcher.score(q, it.name) }.maxByOrNull { it.second } ?: return null
        return if (best.second >= 0.6) best else null
    }

    // ------------------------------------------------------------------ texto
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]")

    private fun clean(s: String) = s.replace(emoji, " ").replace(Regex("https?://\\S+"), "un enlace")
        .replace(Regex("\\s+"), " ").trim().ifEmpty { "un emoji" }

    private fun compose(inc: Incoming): String {
        val sb = StringBuilder()
        if (inc.isGroup) sb.append("En ${clean(inc.chat)}. ")
        var lastSender: String? = null
        for ((sender, text) in inc.lines) {
            val who = sender.ifBlank { inc.chat }
            if (who != lastSender) { sb.append(clean(who)).append(" dice: "); lastSender = who }
            sb.append(clean(text)).append(". ")
        }
        return sb.toString()
    }

    private fun rememberSent(msg: String) {
        recentlySent.addLast(msg); while (recentlySent.size > 10) recentlySent.removeFirst()
    }

    // ------------------------------------------------------------------ audio
    private fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (BtAudio.active) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun say(text: String) = speak(text) { pump() }

    private fun speak(text: String, then: () -> Unit) {
        val t = tts ?: return
        if (!ttsReady) { pendingSpeak.add { speak(text, then) }; return }
        requestFocus()
        val g = gen
        val id = "u${uttSeq++}"
        callbacks[id] = { if (g == gen) then() }
        val bt = BtAudio.active
        if (bt != ttsOnBt) { t.setAudioAttributes(attrs()); ttsOnBt = bt }
        if (Prefs.speechRate != ttsRate) { t.setSpeechRate(Prefs.speechRate); ttsRate = Prefs.speechRate }
        t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    /** Pregunta de sí/no con el reconocedor offline (rápido, entiende "sí"/"no"). Silencio = null. */
    private fun askYesNo(prompt: String, cb: (Boolean?) -> Unit) {
        speak(prompt) {
            val g = gen
            beep()
            h.postDelayed({
                if (g != gen) return@postDelayed
                val ok = WakeListener.askYesNo(6000) { ans -> if (g == gen) cb(ans) }
                if (!ok) recognize(beep = false) { heard -> cb(heard?.let { Matcher.isYes(it) }) }
            }, 200)
        }
    }

    private fun listen(prompt: String, cb: (String?) -> Unit) {
        speak(prompt) { recognize(beep = true, cb = cb) }
    }

    private fun recognize(beep: Boolean, cb: (String?) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            say("No hay reconocimiento de voz disponible. Instalá o actualizá la app de Google."); return
        }
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("No tengo permiso de micrófono."); return
        }
        val g = gen
        if (beep) beep()
        h.postDelayed({ if (g == gen) startRecognizer(g, cb) }, if (beep) 250 else 50)
    }

    private fun startRecognizer(g: Int, cb: (String?) -> Unit) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        var done = false
        fun finish(text: String?) {
            if (done) return
            done = true
            if (recognizer === r) { r.destroy(); recognizer = null }
            if (g == gen) cb(text)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                finish(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() })
            }
            override fun onError(error: Int) { Log.d(TAG, "rec error $error"); finish(null) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        try { r.startListening(i) } catch (e: Exception) { finish(null) }
        h.postDelayed({ finish(null) }, 20000)
    }

    private fun beep() {
        try {
            val stream = if (BtAudio.active) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            val tg = ToneGenerator(stream, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            h.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {}
    }

    private fun interrupt() {
        gen++
        callbacks.clear(); pendingSpeak.clear()
        tts?.stop()
        recognizer?.destroy(); recognizer = null
        WakeListener.cancelAsk()
        WaAccessibility.instance?.cancelJobs()
    }

    private fun requestFocus() {
        if (focusReq != null) return
        val am = app.getSystemService(AudioManager::class.java)
        val type = if (Prefs.pauseMusic) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(type).setAudioAttributes(attrs()).build()
        am.requestAudioFocus(req)
        focusReq = req
    }

    private fun abandonFocus() {
        val r = focusReq ?: return
        app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(r)
        focusReq = null
    }

    fun headsetConnected(): Boolean {
        val am = app.getSystemService(AudioManager::class.java)
        val ok = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, 26, 27)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in ok }
    }
}
