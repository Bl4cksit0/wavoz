package com.bck.wavoz

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.NotificationManagerCompat
import com.bck.wavoz.ui.ChatsScreen
import com.bck.wavoz.ui.HomeScreen
import com.bck.wavoz.ui.SettingsScreen
import com.bck.wavoz.ui.WaVozTheme

/** Estado de los permisos que necesita WaVoz. */
data class PermState(
    val listener: Boolean,
    val mic: Boolean,
    val notif: Boolean,
    val accessibility: Boolean,
    val install: Boolean,
    val battery: Boolean,
) {
    /** Permisos sin los cuales el asistente no funciona bien. */
    val missingEssential: Int get() = listOf(listener, mic && notif, accessibility, battery).count { !it }
}

/** Contacto que se está creando/editando (idx = -1 → nuevo). */
data class ContactDraft(val name: String, val phone: String, val idx: Int)

class MainActivity : ComponentActivity() {
    /** Se incrementa al volver a la app o cambiar algo, para que la UI relea permisos y estado. */
    var tick by mutableIntStateOf(0); private set
    /** Contacto elegido de la agenda (o en edición): la pantalla Chats muestra el diálogo. */
    var contactDraft by mutableStateOf<ContactDraft?>(null)

    private var autoTried = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Prefs.autoStart && !DrivingService.running && micOn() && notifOn()) startForegroundService(DrivingService.intent(this))
        refresh()
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@registerForActivityResult
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        try {
            contentResolver.query(uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    contactDraft = ContactDraft(c.getString(0) ?: "", (c.getString(1) ?: "").filter { it.isDigit() }, -1)
                }
            }
        } catch (e: Exception) { toast("No se pudo leer el contacto") }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        enableEdgeToEdge()
        setContent { WaVozTheme { App(this) } }
    }

    override fun onResume() {
        super.onResume()
        // Arranca solo al abrir la app: desde ahí todo es por voz.
        if (Prefs.autoStart && !autoTried && !DrivingService.running && micOn() && notifOn()) {
            autoTried = true
            startForegroundService(DrivingService.intent(this))
        }
        // Al abrir la app se busca actualización siempre (máximo una vez por minuto).
        Updater.check(this, force = false, every = 60_000L) { refresh() }
        VoskModel.refreshState(this)
        refresh()
    }

    fun refresh() { tick++ }

    // ---------------------------------------------------------------- permisos
    private fun listenerOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun notifOn() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun accOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(ComponentName(this, WaAccessibility::class.java).flattenToString())
    }
    private fun batteryOn() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    fun perms() = PermState(listenerOn(), micOn(), notifOn(), accOn(), packageManager.canRequestPackageInstalls(), batteryOn())

    private fun runtimePerms(): Array<String> {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    fun requestRuntimePerms() = permLauncher.launch(runtimePerms())
    fun openNotifAccess() = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    fun openAccessibility() = open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openInstallSources() = open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    fun openBattery() = open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    fun openAppInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    fun openSecurity() = open(Intent(Settings.ACTION_SECURITY_SETTINGS))

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { toast("No se pudo abrir esa pantalla de Ajustes") }
    }

    // ---------------------------------------------------------------- asistente
    fun toggleAssistant() {
        if (DrivingService.running) startService(DrivingService.intent(this, DrivingService.A_STOP))
        else {
            Prefs.wanted = true
            if (!micOn() || !notifOn()) {
                toast("Primero dale los permisos de micrófono, contactos y notificaciones")
                requestRuntimePerms(); return
            }
            if (!listenerOn()) toast("Ojo: falta el acceso a notificaciones, no se van a leer mensajes")
            startForegroundService(DrivingService.intent(this))
        }
    }

    fun cmd(a: String) {
        if (!DrivingService.running) { toast("Iniciá el asistente primero"); return }
        startService(DrivingService.intent(this, a))
    }

    fun restartIfRunning() {
        if (!DrivingService.running) return
        startService(DrivingService.intent(this, DrivingService.A_STOP))
        window.decorView.postDelayed({ startForegroundService(DrivingService.intent(this)) }, 800)
    }

    fun headsetName(): String? = BtAudio.deviceName(this)

    fun checkUpdate() {
        toast("Buscando…")
        Updater.check(this, force = true) { m -> toast(m); refresh() }
    }

    fun pickContact() {
        try { pickLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
        catch (_: Exception) { toast("No se pudo abrir la agenda") }
    }

    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}

private enum class Dest(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Rounded.Home),
    Chats("Chats", Icons.Rounded.Forum),
    Settings("Ajustes", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(a: MainActivity) {
    val running by DrivingService.state.collectAsState()
    var dest by rememberSaveable { mutableStateOf(Dest.Home) }
    val tick = a.tick
    val perms = androidx.compose.runtime.remember(tick, running) { a.perms() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (dest == Dest.Home) "WaVoz" else dest.label) }) },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = dest == d,
                        onClick = { dest = d },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (dest) {
            Dest.Home -> HomeScreen(a, running, perms, tick, onFixPerms = { dest = Dest.Settings }, modifier = m)
            Dest.Chats -> ChatsScreen(a, modifier = m)
            Dest.Settings -> SettingsScreen(a, perms, tick, modifier = m)
        }
    }
}
