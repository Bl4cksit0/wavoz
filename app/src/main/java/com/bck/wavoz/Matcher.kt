package com.bck.wavoz

import java.text.Normalizer

object Matcher {
    private val marks = Regex("\\p{Mn}+")
    private val nonAlnum = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(marks, "").replace(nonAlnum, " ").replace(spaces, " ").trim()

    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    fun score(query: String, name: String): Double {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        if (n.startsWith(q)) return 0.92
        if (Regex("\\b${Regex.escape(q)}\\b").containsMatchIn(n)) return 0.9
        if (q.contains(n)) return 0.85
        val qt = q.split(" "); val nt = n.split(" ")
        val tok = qt.count { t -> nt.any { it == t || (t.length > 3 && lev(t, it) <= 1) } }.toDouble() / qt.size
        val lv = 1.0 - lev(q, n).toDouble() / maxOf(q.length, n.length)
        return maxOf(tok * 0.85, lv)
    }

    fun <T> best(query: String, items: List<T>, name: (T) -> String): T? =
        items.map { it to score(query, name(it)) }.filter { it.second >= 0.6 }
            .maxByOrNull { it.second }?.first

    private val YES = setOf("si", "dale", "ok", "okay", "oka", "claro", "enviar", "envia", "envialo", "enviala",
        "mandalo", "mandala", "manda", "mandar", "correcto", "afirmativo", "yes", "obvio", "responder", "bueno", "listo")
    private val NO = setOf("no", "cancelar", "cancela", "cancelalo", "nada", "negativo", "despues", "nop", "nope")

    /** true = sí, false = no, null = no se entendió */
    fun isYes(s: String): Boolean? {
        val t = norm(s).split(" ")
        if (t.any { it in NO }) return false
        if (t.any { it in YES }) return true
        return null
    }

    fun isCancel(s: String): Boolean = norm(s) in setOf("cancelar", "cancela", "cancelado", "nada", "olvidalo", "salir")

    private val R_SEND = setOf("enviar", "envia", "envialo", "enviala", "mandar", "manda", "mandalo", "si", "listo", "dale", "ok")
    private val R_MORE = setOf("seguir", "sigo", "segui", "agregar", "agrega", "continuar", "sumar", "falta", "mas")
    private val R_CLEAR = setOf("borrar", "borra", "borralo", "corregir", "repetir", "nuevo", "otra")
    private val R_CANCEL = setOf("cancelar", "cancela", "no", "nada", "olvidalo")

    /** Respuesta a "¿Está listo?": send | more | clear | cancel | null */
    fun readyCommand(s: String): String? {
        val t = norm(s).split(" ")
        return when {
            t.any { it in R_CLEAR } -> "clear"
            t.any { it in R_MORE } -> "more"
            t.any { it in R_CANCEL } -> "cancel"
            t.any { it in R_SEND } -> "send"
            else -> null
        }
    }
}
