package com.bck.wavoz

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Regla de lectura para un chat. En grupos, members vacío = leer a todos. */
data class Rule(var isGroup: Boolean, val members: MutableSet<String>)
data class SeenChat(var isGroup: Boolean, val senders: MutableSet<String>, var lastSeen: Long)
data class Contact(val name: String, val phone: String)

object Prefs {
    private lateinit var sp: SharedPreferences

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
    fun rules(): MutableMap<String, Rule> {
        val out = LinkedHashMap<String, Rule>()
        val o = JSONObject(sp.getString("rules", "{}")!!)
        for (k in o.keys()) {
            val r = o.getJSONObject(k)
            out[k] = Rule(r.optBoolean("g"), r.optJSONArray("m").toSet())
        }
        return out
    }

    fun saveRules(m: Map<String, Rule>) {
        val o = JSONObject()
        m.forEach { (k, r) -> o.put(k, JSONObject().put("g", r.isGroup).put("m", JSONArray(r.members.toList()))) }
        sp.edit().putString("rules", o.toString()).apply()
    }

    // ---- chats vistos en notificaciones ----
    fun seen(): MutableMap<String, SeenChat> {
        val out = LinkedHashMap<String, SeenChat>()
        val o = JSONObject(sp.getString("seen", "{}")!!)
        for (k in o.keys()) {
            val s = o.getJSONObject(k)
            out[k] = SeenChat(s.optBoolean("g"), s.optJSONArray("s").toSet(), s.optLong("t"))
        }
        return out
    }

    private fun saveSeen(m: Map<String, SeenChat>) {
        val o = JSONObject()
        m.forEach { (k, s) ->
            o.put(k, JSONObject().put("g", s.isGroup).put("s", JSONArray(s.senders.toList())).put("t", s.lastSeen))
        }
        sp.edit().putString("seen", o.toString()).apply()
    }

    fun recordSeen(chat: String, isGroup: Boolean, senders: Collection<String>) {
        val m = seen()
        val s = m.getOrPut(chat) { SeenChat(isGroup, mutableSetOf(), 0) }
        if (isGroup) {
            s.isGroup = true
            s.senders.addAll(senders.filter { it.isNotBlank() && it != chat })
        }
        s.lastSeen = System.currentTimeMillis()
        saveSeen(m)
        // si la regla existe, mantener el tipo sincronizado
        val r = rules()
        r[chat]?.let { if (it.isGroup != s.isGroup) { it.isGroup = s.isGroup; saveRules(r) } }
    }

    fun forgetSeen(chat: String) {
        val m = seen(); m.remove(chat); saveSeen(m)
    }

    // ---- contactos con número (para escribir a quien no te escribió) ----
    fun contacts(): MutableList<Contact> {
        val a = JSONArray(sp.getString("contacts", "[]")!!)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Contact(it.getString("n"), it.getString("p")) }.toMutableList()
    }

    fun saveContacts(l: List<Contact>) {
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
