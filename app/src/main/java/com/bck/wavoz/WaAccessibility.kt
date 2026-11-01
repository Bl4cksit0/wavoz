package com.bck.wavoz

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Maneja WhatsApp por el usuario: abre el chat, escribe lo dictado en la caja de texto
 * y toca "Enviar". Solo actúa cuando el asistente se lo pide.
 */
class WaAccessibility : AccessibilityService() {
    companion object {
        var instance: WaAccessibility? = null; private set
        private const val TAG = "WaVoz"
    }

    private val h = Handler(Looper.getMainLooper())
    private var prevPkg: String? = null
    private var job = 0   // invalida esperas viejas

    override fun onServiceConnected() {
        instance = this
        // Android vuelve a conectar la accesibilidad tras actualizar o reiniciar: reactivar el asistente.
        Prefs.init(this)
        if (Prefs.wanted && !DrivingService.running) h.postDelayed({ DrivingService.startFromBackground(this) }, 3000)
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}
    // ---------------------------------------------------------------- espera guiada por eventos
    /** Paso a revisar con cada cambio de WhatsApp; devuelve true cuando terminó. */
    private var waiter: (() -> Boolean)? = null
    private var checkPosted = false
    private var lastAction = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (waiter == null || checkPosted) return
        if (e?.packageName?.toString() !in Assistant.WA_PKGS) return
        // Agrupar ráfagas de eventos: como mucho una revisión cada 50 ms.
        checkPosted = true
        h.postDelayed({ checkPosted = false; runWaiter() }, 50)
    }

    private fun runWaiter() {
        val w = waiter ?: return
        if (w()) waiter = null
    }

    /**
     * Ejecuta step cada vez que WhatsApp cambia (y cada 700 ms por las dudas) hasta que devuelva true.
     * Si vence el tiempo, llama a onTimeout.
     */
    private fun waitFor(timeoutMs: Long, onTimeout: () -> Unit, step: () -> Boolean) {
        val j = ++job
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        lastAction = 0L
        waiter = { j != job || step() }
        fun tick() {
            if (j != job || waiter == null) return
            if (SystemClock.uptimeMillis() > deadline) { waiter = null; onTimeout(); return }
            runWaiter()
            if (waiter != null) h.postDelayed({ tick() }, 700)
        }
        h.post { tick() }
    }

    /** Evita repetir una acción (tocar buscar, etc.) antes de que WhatsApp reaccione a la anterior. */
    private fun canAct(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < 400) return false
        lastAction = now
        return true
    }

    private fun waRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        return if (r.packageName?.toString() in Assistant.WA_PKGS) r else null
    }

    private fun launcherPkgs(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    /** Recuerda la app que estaba en pantalla (ej. GPS) para volver después. */
    private fun rememberPrev() {
        val p = rootInActiveWindow?.packageName?.toString()
        prevPkg = if (p == null || p in Assistant.WA_PKGS || p == packageName || p == "com.android.systemui" || p in launcherPkgs()) null else p
    }

    fun returnToPrevious() {
        val p = prevPkg; prevPkg = null
        val i = p?.let { packageManager.getLaunchIntentForPackage(it) }
        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(i); return } catch (_: Exception) {} }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun findEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (pkg in Assistant.WA_PKGS) root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()?.let { return it }
        return null
    }

    private fun findAll(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, pred: (AccessibilityNodeInfo) -> Boolean) {
        if (n == null) return
        if (pred(n)) out.add(n)
        for (i in 0 until n.childCount) findAll(n.getChild(i), out, pred)
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c ?: n
    }

    /** Abre un chat por número (wa.me) y espera a que aparezca la caja de texto. */
    fun openByPhone(phone: String, cb: (Boolean) -> Unit) {
        rememberPrev()
        val pkg = Assistant.WA_PKGS.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://api.whatsapp.com/send?phone=$phone"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "open", e); cb(false); return }
        waitFor(10_000, onTimeout = { cb(false) }) {
            val r = waRoot()
            if (r != null && findEntry(r) != null) { cb(true); true } else false
        }
    }

    /** Abre WhatsApp, usa la búsqueda y entra al chat (sirve para grupos). Mejor esfuerzo. */
    fun openByName(name: String, cb: (Boolean) -> Unit) {
        rememberPrev()
        val pkg = Assistant.WA_PKGS.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null } ?: run { cb(false); return }
        val launch = packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try { startActivity(launch) } catch (_: Exception) { cb(false); return }
        var step = 0
        val target = Matcher.norm(name)
        waitFor(15_000, onTimeout = { cb(false) }) wait@{
            val r = waRoot() ?: return@wait false
            when (step) {
                0 -> {
                    if (findEntry(r) != null) { if (canAct()) performGlobalAction(GLOBAL_ACTION_BACK) }
                    else {
                        val edits = mutableListOf<AccessibilityNodeInfo>(); findAll(r, edits) { it.isEditable }
                        if (edits.isNotEmpty()) { setText(edits[0], name); step = 1 }
                        else if (canAct()) {
                            val ids = listOf("menuitem_search", "my_search_bar", "search_bar", "search_icon")
                            val byId = ids.firstNotNullOfOrNull { id -> r.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull() }
                            val btn = byId ?: mutableListOf<AccessibilityNodeInfo>().also { l ->
                                findAll(r, l) { n ->
                                    val s = ((n.contentDescription ?: "").toString() + " " + (n.text ?: "")).lowercase()
                                    s.contains("buscar") || s.contains("search")
                                }
                            }.firstOrNull()
                            btn?.let { clickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        }
                    }
                    false
                }
                1 -> {
                    val hits = mutableListOf<AccessibilityNodeInfo>()
                    findAll(r, hits) { n -> !n.isEditable && n.text != null && Matcher.norm(n.text.toString()) == target }
                    hits.firstOrNull()?.let { if (canAct()) { clickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK); step = 2 } }
                    false
                }
                else -> if (findEntry(r) != null) { cb(true); true } else false
            }
        }
    }

    private fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** Escribe el texto dictado en la caja del chat abierto (para que se vea mientras dictás). */
    fun typeInChat(text: String): Boolean {
        val r = waRoot() ?: return false
        val e = findEntry(r) ?: return false
        return setText(e, text)
    }

    /** Escribe el texto final y toca Enviar. */
    fun sendInChat(text: String, cb: (Boolean) -> Unit) {
        if (!typeInChat(text)) { cb(false); return }
        var tries = 0
        fun tryClick() {
            val r = waRoot()
            val btn = r?.let { root ->
                Assistant.WA_PKGS.firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId("$it:id/send").firstOrNull() }
                    ?: listOf("Enviar", "Send").firstNotNullOfOrNull { l ->
                        root.findAccessibilityNodeInfosByText(l).firstOrNull { it.contentDescription?.toString().equals(l, true) }
                    }
            }
            if (btn != null && clickable(btn).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { cb(true); return }
            if (++tries > 6) { cb(false); return }
            h.postDelayed({ tryClick() }, 300)
        }
        tryClick()   // el botón Enviar suele estar listo enseguida; si no, reintenta cada 300 ms
    }

    fun cancelJobs() { job++; waiter = null }
}
