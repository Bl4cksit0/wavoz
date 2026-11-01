package com.bck.wavoz

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/** Lee las notificaciones de WhatsApp en segundo plano. */
class WaListener : NotificationListenerService() {

    data class Msg(val sender: String, val text: String, val ts: Long)
    data class Parsed(val chat: String, val isGroup: Boolean, val msgs: List<Msg>)

    companion object { var connected = false }

    private val lastTs = HashMap<String, Long>()
    private val countSuffix = Regex("\\s*\\(\\d+[^)]*\\)\\s*$")

    override fun onListenerConnected() {
        connected = true
        Prefs.init(this)
        // Registrar lo que ya está en la barra sin leerlo en voz alta.
        try { activeNotifications?.forEach { handle(it, announce = false) } } catch (e: Exception) { Log.w("WaVoz", e) }
    }

    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn, announce = true) } catch (e: Exception) { Log.w("WaVoz", "handle", e) }
    }

    private fun handle(sbn: StatusBarNotification, announce: Boolean) {
        if (sbn.packageName !in Assistant.WA_PKGS) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_CALL) return
        val p = parse(n, sbn.postTime) ?: return
        if (p.msgs.isEmpty()) return

        Prefs.recordSeen(p.chat, p.isGroup, p.msgs.map { it.sender })
        findReply(n)?.let { Assistant.replies[p.chat] = ReplyTarget(p.chat, sbn.packageName, it) }

        val last = lastTs[p.chat] ?: 0L
        val fresh = p.msgs.filter { it.ts > last && !Assistant.isOwnEcho(it.text) }
        lastTs[p.chat] = maxOf(last, p.msgs.maxOf { it.ts })
        if (!announce || fresh.isEmpty()) return

        val toRead = if (Prefs.readAll) fresh else {
            val rule = Prefs.rule(p.chat) ?: return
            if (p.isGroup && rule.members.isNotEmpty()) {
                val allowed = rule.members.map { Matcher.norm(it) }.toSet()
                fresh.filter { Matcher.norm(it.sender) in allowed }
            } else fresh
        }
        if (toRead.isEmpty()) return
        Assistant.onIncoming(Incoming(p.chat, p.isGroup, toRead.map { it.sender to it.text }))
    }

    private fun parse(n: Notification, postTime: Long): Parsed? {
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) } catch (_: Exception) { null }

        if (style != null && style.messages.isNotEmpty()) {
            val me = style.user.name?.toString()
            val isGroup = style.isGroupConversation || style.conversationTitle != null
            val chat = (style.conversationTitle?.toString() ?: title ?: return null).replace(countSuffix, "")
            val msgs = style.messages.mapNotNull { m ->
                val txt = m.text?.toString() ?: return@mapNotNull null
                val sender = m.person?.name?.toString()
                if (sender == null && isGroup) return@mapNotNull null   // mensaje propio
                if (sender != null && sender == me) return@mapNotNull null
                Msg(sender ?: chat, txt, if (m.timestamp > 0) m.timestamp else postTime)
            }
            return Parsed(chat, isGroup, msgs)
        }

        // Formato viejo sin MessagingStyle
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        if (title == null || title.equals("WhatsApp", true) || title.startsWith("WhatsApp Business")) return null
        val t = title.replace(countSuffix, "")
        // "Grupo: Remitente"
        val idx = t.indexOf(": ")
        return if (idx > 0) Parsed(t.substring(0, idx), true, listOf(Msg(t.substring(idx + 2), text, postTime)))
        else Parsed(t, false, listOf(Msg(t, text, postTime)))
    }

    private fun findReply(n: Notification): Notification.Action? {
        val acts = n.actions ?: return null
        val withInput = acts.filter { it.remoteInputs?.isNotEmpty() == true }
        if (Build.VERSION.SDK_INT >= 28) {
            withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        }
        return withInput.firstOrNull()
    }
}
