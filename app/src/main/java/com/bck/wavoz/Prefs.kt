package com.bck.wavoz

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Regla de lectura para un chat. En grupos, members vacío = leer a todos. */
data class Rule(var isGroup: Boolean, val members: MutableSet<String>)
data class SeenChat(var isGroup: Boolean, val senders: MutableSet<String>, var lastSeen: Long)
data class Contact(val name: String, val phone: String)

/**
 * Preferencias. Reglas, chats vistos y contactos se leen una sola vez y quedan en memoria:
 * antes se parseaba JSON en cada notificación de WhatsApp.
 */
object Prefs {
    private const val MAX_SEEN = 80          // chats recordados
    private const val MAX_SENDERS = 60       // integrantes recordados por grupo
    private const val SEEN_SAVE_DELAY = 3000L

    private lateinit var sp: SharedPreferences
    private val h = Handler(Looper.getMainLooper())

    private var rulesCache: LinkedHashMap<String, Rule>? = null
    private var seenCache: LinkedHashMap<String, SeenChat>? = null
    private var contactsCache: MutableList<Contact>? = null

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var readAll: Boolean get() = b("readAll", false); set(v) = pb("readAll", v)
    var askReply: Boolean get() = b("askReply", true); set(v) = pb("askReply", v)
    var onlyHeadset: Boolean get() = b("onlyHeadset", false); set(v) = pb("onlyHeadset", v)
    var pauseMusic: Boolean get() = b("pauseMusic", true); set(v) = pb("pauseMusic", v)
    var helmetMic: Boolean get() = b("helmetMic", true); set(v) = pb("helmetMic", v)
    var wakeWord: Boolean get() = b("wakeWord", true); set(v) = pb("wakeWord", v)
    var autoStart: Boolean get() = b("autoStart", true); set(v) = pb("autoStart", v)
    var wanted: Boolean get() = b("wanted", false); set(v) = pb("wanted", v)
    var autoUpdate: Boolean get() = b("autoUpdate", true); set(v) = pb("autoUpdate", v)
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpd", 0)
        set(v) = sp.edit().putLong("lastUpd", v).apply()
    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    // ---- reglas ----
    @Synchronized private fun rulesMap(): LinkedHashMap<String, Rule> = rulesCache ?: run {
        val out = LinkedHashMap<String, Rule>()
        val o = JSONObject(sp.getString("rules", "{}")!!)
        for (k in o.keys()) {
            val r = o.getJSONObject(k)
            out[k] = Rule(r.optBoolean("g"), r.optJSONArray("m").toSet())
        }
        out.also { rulesCache = it }
    }

    /** Copia editable: modificala y guardala con saveRules(). */
    @Synchronized fun rules(): MutableMap<String, Rule> =
        LinkedHashMap(rulesMap().mapValues { Rule(it.value.isGroup, it.value.members.toMutableSet()) })

    /** Lectura rápida (sin copiar) para el camino de cada notificación. No modificar. */
    @Synchronized fun rule(chat: String): Rule? = rulesMap()[chat]

    @Synchronized fun saveRules(m: Map<String, Rule>) {
        rulesCache = LinkedHashMap(m.mapValues { Rule(it.value.isGroup, it.value.members.toMutableSet()) })
        val o = JSONObject()
        m.forEach { (k, r) -> o.put(k, JSONObject().put("g", r.isGroup).put("m", JSONArray(r.members.toList()))) }
        sp.edit().putString("rules", o.toString()).apply()
    }

    // ---- chats vistos en notificaciones ----
    @Synchronized private fun seenMap(): LinkedHashMap<String, SeenChat> = seenCache ?: run {
        val out = LinkedHashMap<String, SeenChat>()
        val o = JSONObject(sp.getString("seen", "{}")!!)
        for (k in o.keys()) {
            val s = o.getJSONObject(k)
            out[k] = SeenChat(s.optBoolean("g"), s.optJSONArray("s").toSet(), s.optLong("t"))
        }
        out.also { seenCache = it }
    }

    /** Copia de los chats vistos. */
    @Synchronized fun seen(): MutableMap<String, SeenChat> =
        LinkedHashMap(seenMap().mapValues { SeenChat(it.value.isGroup, it.value.senders.toMutableSet(), it.value.lastSeen) })

    @Synchronized fun isGroupChat(chat: String): Boolean? = seenMap()[chat]?.isGroup

    private val saveSeenNow = Runnable { persistSeen() }

    @Synchronized private fun persistSeen() {
        val m = seenCache ?: return
        val o = JSONObject()
        m.forEach { (k, s) ->
            o.put(k, JSONObject().put("g", s.isGroup).put("s", JSONArray(s.senders.toList())).put("t", s.lastSeen))
        }
        sp.edit().putString("seen", o.toString()).apply()
    }

    /** Se llama en cada notificación: actualiza en memoria y guarda unos segundos después (agrupando). */
    @Synchronized fun recordSeen(chat: String, isGroup: Boolean, senders: Collection<String>) {
        val m = seenMap()
        val s = m.getOrPut(chat) { SeenChat(isGroup, mutableSetOf(), 0) }
        if (isGroup) {
            s.isGroup = true
            s.senders.addAll(senders.filter { it.isNotBlank() && it != chat })
            while (s.senders.size > MAX_SENDERS) s.senders.remove(s.senders.first())
        }
        s.lastSeen = System.currentTimeMillis()
        if (m.size > MAX_SEEN) {
            // Olvidar los más viejos (los que tienen regla siguen en la lista por sus reglas).
            m.entries.sortedBy { it.value.lastSeen }.take(m.size - MAX_SEEN).forEach { m.remove(it.key) }
        }
        h.removeCallbacks(saveSeenNow)
        h.postDelayed(saveSeenNow, SEEN_SAVE_DELAY)
        // si la regla existe, mantener el tipo sincronizado
        rulesMap()[chat]?.let { if (it.isGroup != s.isGroup) { val r = rules(); r[chat]?.isGroup = s.isGroup; saveRules(r) } }
    }

    /** Guarda ya lo pendiente (al detener el servicio). */
    fun flush() { h.removeCallbacks(saveSeenNow); persistSeen() }

    @Synchronized fun forgetSeen(chat: String) {
        seenMap().remove(chat); persistSeen()
    }

    // ---- contactos con número (para escribir a quien no te escribió) ----
    @Synchronized private fun contactsList(): MutableList<Contact> = contactsCache ?: run {
        val a = JSONArray(sp.getString("contacts", "[]")!!)
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Contact(it.getString("n"), it.getString("p")) }.toMutableList().also { contactsCache = it }
    }

    /** Copia editable: modificala y guardala con saveContacts(). */
    @Synchronized fun contacts(): MutableList<Contact> = contactsList().toMutableList()

    @Synchronized fun saveContacts(l: List<Contact>) {
        contactsCache = l.toMutableList()
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("p", it.phone)) }
        sp.edit().putString("contacts", a.toString()).apply()
    }

    private fun JSONArray?.toSet(): MutableSet<String> {
        val s = LinkedHashSet<String>()
        if (this != null) for (i in 0 until length()) s.add(getString(i))
        return s
    }
}
