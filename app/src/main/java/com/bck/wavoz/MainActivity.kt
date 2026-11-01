package com.bck.wavoz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val PICK = 42

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Arranca solo al abrir la app: desde ahí todo es por voz.
        if (Prefs.autoStart && !autoTried && !DrivingService.running && micOn() && notifOn()) {
            autoTried = true
            startForegroundService(DrivingService.intent(this))
            root.postDelayed({ build() }, 600)
        }
        Updater.check(this, force = false)
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- estado de permisos
    private fun listenerOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun perms(): Array<String> {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }
    private var autoTried = false
    private fun notifOn() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun accOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(ComponentName(this, WaAccessibility::class.java).flattenToString())
    }
    private fun batteryOn() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---------------------------------------------------------------- UI
    private fun build() {
        root.removeAllViews()

        section("1 · Permisos")
        perm("Acceso a notificaciones (para leer WhatsApp)", listenerOn()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        perm("Micrófono, contactos y notificaciones", micOn() && notifOn()) { requestPermissions(perms(), 1) }
        perm("Accesibilidad (para abrir el chat y tocar Enviar por vos)", accOn()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        perm("Instalar actualizaciones automáticas", packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        perm("Sin restricción de batería (que Android no lo cierre)", batteryOn()) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        note("Si al activar notificaciones o accesibilidad aparece \"Configuración restringida\": abrí Info de la app → menú ⋮ (arriba a la derecha) → \"Permitir configuración restringida\" y volvé a intentar.")
        button("Abrir info de la app") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        section("2 · Asistente")
        val on = DrivingService.running
        status(if (on) "● Activo: leyendo mensajes en segundo plano" else "○ Detenido", on)
        button(if (on) "Detener asistente" else "Iniciar asistente", big = true) {
            if (on) startService(DrivingService.intent(this, DrivingService.A_STOP))
            else { Prefs.wanted = true; startAssistant() }
            root.postDelayed({ build() }, 400)
        }
        row(
            "🎤 Dictar" to { cmd(DrivingService.A_DICTATE) },
            "↩ Responder último" to { cmd(DrivingService.A_REPLY) },
        )
        row(
            "🔊 Probar voz" to { cmd(DrivingService.A_TEST) },
            "🤫 Silenciar" to { cmd(DrivingService.A_SILENCE) },
        )
        val bt = BtAudio.deviceName(this)
        note(if (bt != null) "🎧 Casco/auricular detectado: $bt" else "🎧 No hay casco Bluetooth conectado (se usa el micrófono del teléfono)")
        note("""Comandos de voz (con el asistente activo, sin tocar nada):
• "Escribir" → te pregunta a quién, abre el chat y dictás. Tras 5 s de silencio pregunta si está listo:
     "enviar" · "seguir" (agregar más) · "borrar" (dictar de nuevo) · "cancelar"
• "Responder" → responde al último mensaje leído
• "Repetir" → vuelve a leer el último mensaje
• Después de leer un mensaje pregunta "¿Querés responder?" → "sí" / "no".""")

        section("Pantalla apagada / celular en el bolsillo")
        note("Responder a quien te escribió funciona siempre con la pantalla apagada. Para escribirle a alguien nuevo, WaVoz prende la pantalla, abre el chat, envía y la vuelve a apagar. Si usás PIN/patrón/huella, registrá el intercom como dispositivo de confianza: Ajustes → Pantalla de bloqueo → Smart Lock → Dispositivos de confianza. Así mientras el casco esté conectado el celular no pide PIN.")
        button("Abrir ajustes de seguridad / Smart Lock") {
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
        }

        section("3 · Opciones")
        check("Iniciar el asistente automáticamente al abrir la app", Prefs.autoStart) { Prefs.autoStart = it }
        check("Escuchar comandos de voz todo el tiempo (\"escribir\", \"responder\"…)", Prefs.wakeWord) { Prefs.wakeWord = it; restartIfRunning() }
        check("Usar el micrófono del casco Bluetooth", Prefs.helmetMic) { Prefs.helmetMic = it; restartIfRunning() }
        note("Con el micrófono del casco activo, el intercom queda en modo \"llamada\" mientras el asistente escucha. En la mayoría de los intercoms eso corta la música por A2DP.")
        check("Preguntar si querés responder después de leer", Prefs.askReply) { Prefs.askReply = it }
        check("Pausar la música mientras habla", Prefs.pauseMusic) { Prefs.pauseMusic = it }
        check("Leer solo con Bluetooth o auriculares conectados", Prefs.onlyHeadset) { Prefs.onlyHeadset = it }
        val rates = listOf(0.8f to "lenta", 1.0f to "normal", 1.2f to "rápida", 1.4f to "muy rápida")
        val cur = rates.minByOrNull { kotlin.math.abs(it.first - Prefs.speechRate) }!!
        button("Velocidad de voz: ${cur.second}") {
            val i = (rates.indexOf(cur) + 1) % rates.size
            Prefs.speechRate = rates[i].first; build()
        }

        section("Actualizaciones")
        note("Versión instalada: ${Updater.currentVersion(this)}" + if (Updater.status.isNotEmpty()) "\n${Updater.status}" else "")
        check("Actualizar automáticamente desde GitHub", Prefs.autoUpdate) { Prefs.autoUpdate = it }
        button("Buscar actualización ahora") {
            toast("Buscando…")
            Updater.check(this, force = true) { m -> toast(m); build() }
        }

        section("4 · Chats que se leen")
        check("Leer TODOS los chats (ignora la selección de abajo)", Prefs.readAll) { Prefs.readAll = it; build() }
        if (!Prefs.readAll) chatsList()

        section("5 · Contactos para dictar")
        note("Podés responder por voz a cualquier chat del que te haya llegado un mensaje desde que el asistente está activo. Para escribirle a alguien que no te escribió, agregalo acá con su número (con código de país, ej. 5491122334455).")
        val contacts = Prefs.contacts()
        if (contacts.isEmpty()) note("— Ninguno —")
        contacts.forEachIndexed { idx, c ->
            item("${c.name}  ·  +${c.phone}") {
                AlertDialog.Builder(this).setTitle(c.name).setItems(arrayOf("Editar número", "Eliminar")) { _, w ->
                    if (w == 0) editContact(c.name, c.phone, idx)
                    else { val l = Prefs.contacts(); l.removeAt(idx); Prefs.saveContacts(l); build() }
                }.show()
            }
        }
        button("+ Agregar desde la agenda") {
            try { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PICK) }
            catch (_: Exception) { toast("No se pudo abrir la agenda") }
        }
        button("+ Agregar manualmente") { editContact("", "", -1) }
    }

    private fun chatsList() {
        note("Marcá los contactos y grupos que querés que se lean. Aparecen acá cuando te llega un mensaje suyo (con el permiso de notificaciones activo). En los grupos podés elegir qué integrantes leer.")
        val rules = Prefs.rules()
        val seen = Prefs.seen()
        val names = (seen.entries.sortedByDescending { it.value.lastSeen }.map { it.key } + rules.keys).distinct()
        if (names.isEmpty()) note("— Todavía no llegó ningún mensaje. Pedile a alguien que te escriba, o agregá el nombre a mano. —")
        for (name in names) {
            val isGroup = seen[name]?.isGroup ?: rules[name]?.isGroup ?: false
            val rule = rules[name]
            check((if (isGroup) "👥 " else "👤 ") + name, rule != null) { checked ->
                val r = Prefs.rules()
                if (checked) r[name] = Rule(isGroup, mutableSetOf()) else r.remove(name)
                Prefs.saveRules(r); build()
            }
            if (isGroup && rule != null) {
                val label = if (rule.members.isEmpty()) "todos" else rule.members.joinToString(", ")
                item("      Integrantes: $label  ✎", accent = true) { membersDialog(name) }
            }
        }
        button("+ Agregar chat por nombre") { addChatDialog() }
    }

    private fun membersDialog(chat: String) {
        val rules = Prefs.rules()
        val rule = rules[chat] ?: return
        val opts = ((Prefs.seen()[chat]?.senders ?: emptySet()) + rule.members).distinct().sorted()
        val checked = BooleanArray(opts.size) { opts[it] in rule.members }
        AlertDialog.Builder(this)
            .setTitle("¿A quién leer en $chat?")
            .setMessage(if (opts.isEmpty()) "Aún no se vieron integrantes. Agregá nombres tal como aparecen en WhatsApp." else null)
            .apply { if (opts.isNotEmpty()) setMultiChoiceItems(opts.toTypedArray(), checked) { _, i, v -> checked[i] = v } }
            .setPositiveButton("Guardar") { _, _ ->
                rule.members.clear(); opts.forEachIndexed { i, s -> if (checked[i]) rule.members.add(s) }
                Prefs.saveRules(rules); build()
            }
            .setNeutralButton("Agregar nombre") { _, _ ->
                input("Nombre del integrante (como aparece en WhatsApp)", "") { n ->
                    if (n.isNotBlank()) { rule.members.add(n.trim()); Prefs.saveRules(rules); build(); membersDialog(chat) }
                }
            }
            .setNegativeButton("Leer a todos") { _, _ -> rule.members.clear(); Prefs.saveRules(rules); build() }
            .show()
    }

    private fun addChatDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val et = EditText(this).apply { hint = "Nombre exacto del contacto o grupo" }
        val cb = CheckBox(this).apply { text = "Es un grupo" }
        ll.addView(et); ll.addView(cb)
        AlertDialog.Builder(this).setTitle("Agregar chat").setView(ll)
            .setPositiveButton("Agregar") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { val r = Prefs.rules(); r[n] = Rule(cb.isChecked, mutableSetOf()); Prefs.saveRules(r); build() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun editContact(name: String, phone: String, idx: Int) {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val en = EditText(this).apply { hint = "Nombre (como lo vas a decir)"; setText(name) }
        val ep = EditText(this).apply {
            hint = "Número con código de país"; inputType = InputType.TYPE_CLASS_PHONE; setText(phone)
        }
        ll.addView(en); ll.addView(ep)
        ll.addView(TextView(this).apply { text = "Ej. Argentina: 549 + área sin 0 + número sin 15 → 5491122334455"; textSize = 12f })
        AlertDialog.Builder(this).setTitle("Contacto").setView(ll)
            .setPositiveButton("Guardar") { _, _ ->
                val n = en.text.toString().trim()
                val p = ep.text.toString().filter { it.isDigit() }
                if (n.isEmpty() || p.length < 8) { toast("Nombre o número inválido"); return@setPositiveButton }
                val l = Prefs.contacts()
                if (idx in l.indices) l[idx] = Contact(n, p) else l.add(Contact(n, p))
                Prefs.saveContacts(l); build()
            }.setNegativeButton("Cancelar", null).show()
    }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != PICK || res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.query(uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0) ?: ""
                    var p = (c.getString(1) ?: "").filter { it.isDigit() }
                    editContact(n, p, -1)
                }
            }
        } catch (e: Exception) { toast("No se pudo leer el contacto") }
    }

    private fun startAssistant() {
        if (!micOn() || !notifOn()) {
            toast("Primero dale los permisos de micrófono, contactos y notificaciones")
            requestPermissions(perms(), 1); return
        }
        if (!listenerOn()) toast("Ojo: falta el acceso a notificaciones, no se van a leer mensajes")
        startForegroundService(DrivingService.intent(this))
    }

    private fun restartIfRunning() {
        if (!DrivingService.running) return
        startService(DrivingService.intent(this, DrivingService.A_STOP))
        root.postDelayed({ startForegroundService(DrivingService.intent(this)); root.postDelayed({ build() }, 600) }, 800)
    }

    private fun cmd(a: String) {
        if (!DrivingService.running) { toast("Iniciá el asistente primero"); return }
        startService(DrivingService.intent(this, a))
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (Prefs.autoStart && !DrivingService.running && micOn() && notifOn()) startForegroundService(DrivingService.intent(this))
        root.postDelayed({ build() }, 600)
    }

    // ---------------------------------------------------------------- helpers de vista
    private fun section(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#075E54"))
        setPadding(0, dp(22), 0, dp(6))
    })

    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(4), 0, dp(6))
    })

    private fun status(t: String, ok: Boolean) = root.addView(TextView(this).apply {
        text = t; textSize = 16f; setTextColor(if (ok) Color.parseColor("#128C7E") else Color.GRAY); setPadding(0, dp(4), 0, dp(4))
    })

    private fun perm(label: String, ok: Boolean, onClick: () -> Unit) = root.addView(TextView(this).apply {
        text = (if (ok) "✅  " else "⚠️  ") + label + if (ok) "" else "  →  tocá para activar"
        textSize = 15f; setPadding(0, dp(10), 0, dp(10))
        setTextColor(if (ok) Color.BLACK else Color.parseColor("#B00020"))
        setOnClickListener { onClick() }
    })

    private fun button(t: String, big: Boolean = false, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t; isAllCaps = false; textSize = if (big) 18f else 15f
        if (big) minHeight = dp(64)
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, -2))

    private fun row(vararg items: Pair<String, () -> Unit>) {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (t, a) ->
            ll.addView(Button(this).apply { text = t; isAllCaps = false; minHeight = dp(56); setOnClickListener { a() } },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(ll)
    }

    private fun check(t: String, v: Boolean, onChange: (Boolean) -> Unit) = root.addView(CheckBox(this).apply {
        text = t; isChecked = v; textSize = 15f; setOnCheckedChangeListener { _, c -> onChange(c) }
    })

    private fun item(t: String, accent: Boolean = false, onClick: () -> Unit) = root.addView(TextView(this).apply {
        text = t; textSize = 15f; setPadding(dp(4), dp(8), 0, dp(8)); gravity = Gravity.CENTER_VERTICAL
        if (accent) setTextColor(Color.parseColor("#128C7E"))
        setOnClickListener { onClick() }
    })

    private fun input(title: String, value: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(value) }
        val ll = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle(title).setView(ll)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }.setNegativeButton("Cancelar", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @Suppress("unused") private fun View.gone() { visibility = View.GONE }
}
