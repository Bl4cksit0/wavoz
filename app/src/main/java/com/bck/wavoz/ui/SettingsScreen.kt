package com.bck.wavoz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bck.wavoz.DrivingService
import com.bck.wavoz.MainActivity
import com.bck.wavoz.PermState
import com.bck.wavoz.Prefs
import com.bck.wavoz.Updater
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(a: MainActivity, perms: PermState, tick: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PermissionsCard(a, perms)
        VoiceCard(a)
        ListeningCard(a)
        PrivacyCard(a)
        UpdatesCard(a, tick)
    }
}

// ------------------------------------------------------------------ Voz
@Composable
private fun VoiceCard(a: MainActivity) {
    var rate by remember { mutableFloatStateOf(Prefs.speechRate) }
    var pauseMusic by remember { mutableStateOf(Prefs.pauseMusic) }
    SettingsCard("Voz", Icons.Rounded.RecordVoiceOver) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Velocidad de lectura: ${rateLabel(rate)}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = rate,
                onValueChange = { rate = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = { Prefs.speechRate = rate },
                valueRange = 0.6f..1.6f,
                steps = 9,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        ActionRow(Icons.AutoMirrored.Rounded.VolumeUp, "Probar voz", "Lee un mensaje de prueba con esta velocidad") {
            a.cmd(DrivingService.A_TEST)
        }
        SwitchRow("Pausar la música mientras habla", pauseMusic, "Si está apagado, la música solo baja de volumen") {
            pauseMusic = it; Prefs.pauseMusic = it
        }
    }
}

private fun rateLabel(r: Float): String {
    val name = when {
        r < 0.85f -> "lenta"
        r < 1.15f -> "normal"
        r < 1.35f -> "rápida"
        else -> "muy rápida"
    }
    return "%.1f× (%s)".format(r, name)
}

// ------------------------------------------------------------------ Escucha
@Composable
private fun ListeningCard(a: MainActivity) {
    var autoStart by remember { mutableStateOf(Prefs.autoStart) }
    var wake by remember { mutableStateOf(Prefs.wakeWord) }
    var helmet by remember { mutableStateOf(Prefs.helmetMic) }
    var askReply by remember { mutableStateOf(Prefs.askReply) }
    SettingsCard("Escucha", Icons.Rounded.Hearing) {
        SwitchRow("Iniciar al abrir la app", autoStart) { autoStart = it; Prefs.autoStart = it }
        SwitchRow("Escuchar comandos todo el tiempo", wake, "\"Escribir\", \"responder\", \"repetir\"") {
            wake = it; Prefs.wakeWord = it; a.restartIfRunning()
        }
        SwitchRow(
            "Usar el micrófono del casco", helmet,
            "El intercom queda en modo llamada mientras escucha. En la mayoría corta la música.",
        ) { helmet = it; Prefs.helmetMic = it; a.restartIfRunning() }
        SwitchRow("Preguntar si querés responder", askReply, "Después de leer cada mensaje") {
            askReply = it; Prefs.askReply = it
        }
    }
}

// ------------------------------------------------------------------ Privacidad
@Composable
private fun PrivacyCard(a: MainActivity) {
    var onlyHeadset by remember { mutableStateOf(Prefs.onlyHeadset) }
    SettingsCard("Privacidad", Icons.Rounded.Shield) {
        SwitchRow(
            "Leer solo con casco o auriculares", onlyHeadset,
            "Así los mensajes no se escuchan por el altavoz del teléfono",
        ) { onlyHeadset = it; Prefs.onlyHeadset = it }
        ActionRow(
            Icons.Rounded.Lock, "Pantalla bloqueada (Smart Lock)",
            "Para escribirle a alguien nuevo WaVoz prende la pantalla. Si usás PIN, agregá el intercom como dispositivo de confianza.",
        ) { a.openSecurity() }
    }
}

// ------------------------------------------------------------------ Permisos
@Composable
private fun PermissionsCard(a: MainActivity, p: PermState) {
    SettingsCard("Permisos", Icons.Rounded.CheckCircle) {
        PermRow(Icons.Rounded.Notifications, "Acceso a notificaciones", "Para leer los mensajes de WhatsApp", p.listener) { a.openNotifAccess() }
        PermRow(Icons.Rounded.Mic, "Micrófono, contactos y notificaciones", "Para escucharte y encontrar contactos", p.mic && p.notif) { a.requestRuntimePerms() }
        PermRow(Icons.Rounded.Accessibility, "Accesibilidad", "Para abrir el chat y tocar Enviar por vos", p.accessibility) { a.openAccessibility() }
        PermRow(Icons.Rounded.BatteryChargingFull, "Sin restricción de batería", "Para que Android no cierre el asistente", p.battery) { a.openBattery() }
        PermRow(Icons.Rounded.InstallMobile, "Instalar actualizaciones", "Para actualizarse solo desde GitHub", p.install) { a.openInstallSources() }
        Note("Si aparece \"Configuración restringida\": abrí Info de la app → menú ⋮ → \"Permitir configuración restringida\" y volvé a intentar.")
        ActionRow(Icons.Rounded.Info, "Abrir info de la app") { a.openAppInfo() }
    }
}

@Composable
private fun PermRow(icon: ImageVector, title: String, desc: String, ok: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ActionRow(
        icon = if (ok) icon else Icons.Rounded.Warning,
        title = title,
        subtitle = if (ok) "Activado" else "$desc · Tocá para activar",
        iconTint = if (ok) cs.primary else cs.error,
        onClick = onClick,
    )
}

// ------------------------------------------------------------------ Actualizaciones
@Composable
private fun UpdatesCard(a: MainActivity, tick: Int) {
    var auto by remember { mutableStateOf(Prefs.autoUpdate) }
    val status = remember(tick) { Updater.status }
    SettingsCard("Actualizaciones", Icons.Rounded.SystemUpdate) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Versión ${remember { Updater.currentVersion(a) }}", style = MaterialTheme.typography.bodyLarge)
                if (status.isNotEmpty()) Text(
                    status, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwitchRow("Actualizar automáticamente", auto, "Desde GitHub, al abrir la app y cada 6 horas") {
            auto = it; Prefs.autoUpdate = it
        }
        Button(
            onClick = { a.checkUpdate() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Buscar actualización ahora")
        }
    }
}
