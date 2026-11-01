package com.bck.wavoz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bck.wavoz.DrivingService
import com.bck.wavoz.MainActivity
import com.bck.wavoz.PermState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    a: MainActivity,
    running: Boolean,
    perms: PermState,
    tick: Int,
    onFixPerms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El casco se puede conectar/desconectar en cualquier momento: se revisa cada 3 s.
    var headset by remember { mutableStateOf(a.headsetName()) }
    LaunchedEffect(tick) {
        while (true) { headset = a.headsetName(); delay(3000) }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (perms.missingEssential > 0) PermissionWarning(perms.missingEssential, onFixPerms)

        StatusCard(running)

        Button(
            onClick = { a.toggleAssistant() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
            shape = RoundedCornerShape(24.dp),
            colors = if (running) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) else ButtonDefaults.buttonColors(),
        ) {
            Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                if (running) "Detener asistente" else "Iniciar asistente",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction(Icons.Rounded.Mic, "Dictar", Modifier.weight(1f)) { a.cmd(DrivingService.A_DICTATE) }
            QuickAction(Icons.AutoMirrored.Rounded.Reply, "Responder último", Modifier.weight(1f)) { a.cmd(DrivingService.A_REPLY) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction(Icons.AutoMirrored.Rounded.VolumeUp, "Probar voz", Modifier.weight(1f)) { a.cmd(DrivingService.A_TEST) }
            QuickAction(Icons.AutoMirrored.Rounded.VolumeOff, "Silenciar", Modifier.weight(1f)) { a.cmd(DrivingService.A_SILENCE) }
        }

        HeadsetCard(headset)
        VoiceHelpCard()
    }
}

@Composable
private fun PermissionWarning(missing: Int, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (missing == 1) "Falta 1 permiso" else "Faltan $missing permisos",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Sin ellos WaVoz no puede leer o enviar mensajes.", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = onFix,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) { Text("Revisar") }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) cs.primaryContainer else cs.surfaceContainerHigh,
            contentColor = if (running) cs.onPrimaryContainer else cs.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (running) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle, null,
                Modifier.size(64.dp),
                tint = if (running) cs.primary else cs.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                if (running) "Activo" else "Detenido",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                if (running) "Leyendo mensajes en segundo plano" else "No se leen mensajes",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 88.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(30.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HeadsetCard(name: String?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (name != null) Icons.Rounded.Headset else Icons.Rounded.HeadsetOff, null,
                tint = if (name != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(if (name != null) "Casco conectado" else "Sin casco Bluetooth", style = MaterialTheme.typography.titleMedium)
                Text(
                    name ?: "Se usa el micrófono del teléfono",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceHelpCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Comandos de voz", style = MaterialTheme.typography.titleMedium)
            }
            Help("\"Escribir\"", "Te pregunta a quién, abre el chat y dictás. Tras 5 s de silencio: \"enviar\", \"seguir\", \"borrar\" o \"cancelar\".")
            Help("\"Responder\"", "Responde al último mensaje leído.")
            Help("\"Repetir\"", "Vuelve a leer el último mensaje.")
            Help("\"¿Querés responder?\"", "Después de leer: \"sí\" para responder, \"no\" para seguir.")
        }
    }
}

@Composable
private fun Help(cmd: String, desc: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(cmd, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
