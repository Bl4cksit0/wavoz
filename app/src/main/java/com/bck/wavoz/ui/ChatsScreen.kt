package com.bck.wavoz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bck.wavoz.Contact
import com.bck.wavoz.ContactDraft
import com.bck.wavoz.MainActivity
import com.bck.wavoz.Matcher
import com.bck.wavoz.Prefs
import com.bck.wavoz.Rule

/** Nombres con los que WhatsApp se refiere a uno mismo: no se muestran como chats ni integrantes. */
private val SELF_NAMES = setOf("tu", "you", "yo", "vos")
private fun isSelf(name: String) = Matcher.norm(name) in SELF_NAMES

@Composable
fun ChatsScreen(a: MainActivity, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Se incrementa al guardar algo para releer Prefs.
    var ver by remember { mutableIntStateOf(0) }
    var addChat by remember { mutableStateOf(false) }
    var addContactMenu by remember { mutableStateOf(false) }
    var membersFor by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Se leen", "Para dictar").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i, onClick = { tab = i }, text = { Text(label) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
            if (tab == 0) ReadList(ver, onChanged = { ver++ }, onMembers = { membersFor = it })
            else ContactsList(ver, onChanged = { ver++ }, onEdit = { a.contactDraft = it })
        }
        FloatingActionButton(
            onClick = { if (tab == 0) addChat = true else addContactMenu = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(72.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Icon(Icons.Rounded.Add, contentDescription = if (tab == 0) "Agregar chat" else "Agregar contacto", Modifier.size(32.dp)) }
    }

    if (addChat) AddChatDialog(onDismiss = { addChat = false }) { name, group ->
        val r = Prefs.rules(); r[name] = Rule(group, mutableSetOf()); Prefs.saveRules(r)
        // Registrarlo como chat conocido: así sigue en la lista aunque después se desactive.
        Prefs.recordSeen(name, group, emptyList())
        addChat = false; ver++
    }
    if (addContactMenu) AlertDialog(
        onDismissRequest = { addContactMenu = false },
        title = { Text("Agregar contacto") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Desde la agenda") },
                    leadingContent = { Icon(Icons.Rounded.Contacts, null) },
                    modifier = Modifier.clickable { addContactMenu = false; a.pickContact() },
                )
                ListItem(
                    headlineContent = { Text("Escribir manualmente") },
                    leadingContent = { Icon(Icons.Rounded.Keyboard, null) },
                    modifier = Modifier.clickable { addContactMenu = false; a.contactDraft = ContactDraft("", "", -1) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { addContactMenu = false }) { Text("Cancelar") } },
    )
    a.contactDraft?.let { d ->
        ContactDialog(d, onDismiss = { a.contactDraft = null }) { n, p ->
            val l = Prefs.contacts()
            if (d.idx in l.indices) l[d.idx] = Contact(n, p) else l.add(Contact(n, p))
            Prefs.saveContacts(l)
            a.contactDraft = null; ver++
        }
    }
    membersFor?.let { chat -> MembersDialog(chat, onDismiss = { membersFor = null }) { membersFor = null; ver++ } }
}

// ------------------------------------------------------------------ Se leen
@Composable
private fun ReadList(ver: Int, onChanged: () -> Unit, onMembers: (String) -> Unit) {
    val readAll = remember(ver) { Prefs.readAll }
    val rules = remember(ver) { Prefs.rules() }
    val seen = remember(ver) { Prefs.seen() }
    val names = remember(ver) {
        (seen.entries.sortedByDescending { it.value.lastSeen }.map { it.key } + rules.keys)
            .distinct().filterNot { isSelf(it) }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                SwitchRow(
                    "Leer todos los chats", readAll,
                    subtitle = if (readAll) "Se leen todos los mensajes de WhatsApp" else "Solo se leen los chats activados abajo",
                ) { Prefs.readAll = it; onChanged() }
            }
        }
        if (readAll) return@LazyColumn
        item {
            Note("Los chats aparecen acá cuando te llega un mensaje suyo. En los grupos podés elegir qué integrantes leer.")
        }
        if (names.isEmpty()) item {
            Note("Todavía no llegó ningún mensaje. Pedile a alguien que te escriba o agregá el chat con el botón +.")
        }
        items(names, key = { it }) { name ->
            val isGroup = seen[name]?.isGroup ?: rules[name]?.isGroup ?: false
            val rule = rules[name]
            val on = rule != null
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = if (isGroup && rule != null) {
                    {
                        val members = rule.members.filterNot { isSelf(it) }
                        Text(
                            "Integrantes: " + if (members.isEmpty()) "todos" else members.joinToString(", "),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                } else null,
                leadingContent = {
                    Icon(if (isGroup) Icons.Rounded.Group else Icons.Rounded.Person, contentDescription = if (isGroup) "Grupo" else "Contacto")
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isGroup && on) IconButton(onClick = { onMembers(name) }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Elegir integrantes")
                        }
                        Switch(checked = on, onCheckedChange = null)
                    }
                },
                modifier = Modifier
                    .heightIn(min = 64.dp)
                    .toggleable(value = on, role = Role.Switch) { checked ->
                        val r = Prefs.rules()
                        if (checked) r[name] = Rule(isGroup, mutableSetOf()) else r.remove(name)
                        Prefs.saveRules(r)
                        // Chats agregados a mano: que no desaparezcan de la lista al desactivarlos.
                        if (name !in seen) Prefs.recordSeen(name, isGroup, emptyList())
                        onChanged()
                    },
            )
            HorizontalDivider()
        }
    }
}

// ------------------------------------------------------------------ Para dictar
@Composable
private fun ContactsList(ver: Int, onChanged: () -> Unit, onEdit: (ContactDraft) -> Unit) {
    val contacts = remember(ver) { Prefs.contacts() }
    var toDelete by remember { mutableStateOf<Int?>(null) }

    LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
        item {
            Note(
                "Podés responder por voz a cualquiera que te haya escrito. Para escribirle a alguien que no te escribió, " +
                    "agregalo acá con su número (con código de país).",
                Modifier.padding(top = 10.dp),
            )
        }
        if (contacts.isEmpty()) item { Note("Todavía no agregaste contactos. Tocá + para agregar uno.") }
        items(contacts.size) { idx ->
            val c = contacts[idx]
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = { Text("+${c.phone}") },
                leadingContent = { Icon(Icons.Rounded.Phone, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onEdit(ContactDraft(c.name, c.phone, idx)) }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Editar ${c.name}")
                        }
                        IconButton(onClick = { toDelete = idx }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Eliminar ${c.name}")
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 64.dp).clickable { onEdit(ContactDraft(c.name, c.phone, idx)) },
            )
            HorizontalDivider()
        }
    }

    toDelete?.let { idx ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Eliminar a ${contacts.getOrNull(idx)?.name ?: ""}?") },
            confirmButton = {
                TextButton(onClick = {
                    val l = Prefs.contacts(); if (idx in l.indices) l.removeAt(idx); Prefs.saveContacts(l)
                    toDelete = null; onChanged()
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

// ------------------------------------------------------------------ diálogos
@Composable
private fun AddChatDialog(onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre exacto del contacto o grupo") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(group, role = Role.Switch) { group = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Es un grupo", Modifier.weight(1f))
                    Switch(checked = group, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onAdd(name.trim(), group) }, enabled = name.isNotBlank()) { Text("Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ContactDialog(d: ContactDraft, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(d) { mutableStateOf(d.name) }
    var phone by remember(d) { mutableStateOf(d.phone) }
    val digits = phone.filter { it.isDigit() }
    val valid = name.isNotBlank() && digits.length >= 8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.idx >= 0) "Editar contacto" else "Nuevo contacto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre (como lo vas a decir)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Número con código de país") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ej. Argentina: 549 + área sin 0 + número sin 15 → 5491122334455",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), digits) }, enabled = valid) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MembersDialog(chat: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val rule = remember { Prefs.rules()[chat] } ?: run { onDismiss(); return }
    val options = remember {
        mutableStateListOf<String>().apply {
            addAll(((Prefs.seen()[chat]?.senders ?: emptySet()) + rule.members).distinct().filterNot { isSelf(it) }.sorted())
        }
    }
    val selected = remember { mutableStateListOf<String>().apply { addAll(rule.members) } }
    var newName by remember { mutableStateOf("") }

    fun save(members: Collection<String>) {
        val r = Prefs.rules()
        r[chat]?.let { it.members.clear(); it.members.addAll(members) }
        Prefs.saveRules(r); onSaved()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿A quién leer en $chat?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text(
                    "Aún no se vieron integrantes. Agregá nombres tal como aparecen en WhatsApp.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                options.forEach { m ->
                    val on = m in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(on, role = Role.Checkbox) { if (it) selected.add(m) else selected.remove(m) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(m)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("Agregar integrante") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val n = newName.trim()
                            if (n.isNotEmpty() && n !in options) { options.add(n); selected.add(n) }
                            newName = ""
                        },
                        enabled = newName.isNotBlank(),
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Agregar") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(selected) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { save(emptyList()) }) { Text("Leer a todos") } },
    )
}
