package app.fonte.ui.detail

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fonte.Library
import app.fonte.Row
import app.fonte.app
import app.fonte.install.Installer
import app.fonte.ui.AppIcon
import app.fonte.ui.Bar
import app.fonte.ui.GroupItem
import app.fonte.ui.Icons
import app.fonte.ui.ItemRow
import app.fonte.ui.SectionTitle
import app.fonte.ui.SwitchItem
import app.fonte.ui.copy
import app.fonte.ui.nav.Navigator
import app.fonte.ui.openLink
import app.fonte.ui.taskText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(id: String, nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val rows by app.rows.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.app.id == id }
    if (row == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val t = row.app
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.installer.messages.collect { snackbar.showSnackbar(it) } }
    var removing by rememberSaveable { mutableStateOf(false) }
    var editingFilter by rememberSaveable { mutableStateOf(false) }
    var resettingPin by rememberSaveable { mutableStateOf(false) }
    var allNotes by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Bar("", onBack = nav::back) {
                IconButton(onClick = { openLink(context, t.latest?.page ?: t.source.webUrl) }) {
                    Icon(Icons.OpenInNew, contentDescription = "Open the source page")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp + padding.calculateBottomPadding())) {
            item { Header(row) }
            item { Actions(row) }
            t.error?.let { error ->
                item {
                    GroupItem(0, 1, Modifier.padding(top = 16.dp)) {
                        ItemRow("Last problem", error, subtitleColor = MaterialTheme.colorScheme.error, subtitleLines = 4)
                    }
                }
            }

            item { SectionTitle("Version") }
            item {
                val latest = t.latest
                val installed = row.installed
                val items = buildList {
                    add("Installed" to (installed?.let { "${it.versionName} (${it.versionCode})" } ?: "Not installed"))
                    add("Latest" to (latest?.let { r -> r.version + (r.date?.let { " · " + ago(it) } ?: "") } ?: if (t.checkedAt == 0L) "Not checked yet" else "Not found"))
                    add("Source" to "${t.source.label} · ${t.source.path}")
                    if (t.checkedAt > 0) add("Checked" to ago(t.checkedAt))
                }
                Column {
                    items.forEachIndexed { i, (k, v) -> GroupItem(i, items.size) { ItemRow(k, v) } }
                }
            }

            val notes = t.latest?.notes.orEmpty()
            if (notes.isNotEmpty()) {
                item { SectionTitle("What's new") }
                item {
                    // Only a cut text opens. The click label tells TalkBack what a tap does.
                    var cut by remember(notes) { mutableStateOf(false) }
                    val opens = cut || allNotes
                    GroupItem(
                        0, 1,
                        if (opens) Modifier.semantics { onClick(if (allNotes) "Collapse" else "Expand") { allNotes = !allNotes; true } } else Modifier,
                        onClick = if (opens) ({ allNotes = !allNotes }) else null,
                    ) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (allNotes) Int.MAX_VALUE else 8,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!allNotes) cut = it.hasVisualOverflow },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // The app scope: the check after an option change runs on when the screen goes.
            item { SectionTitle("Options") }
            item {
                Column {
                    SwitchItem(0, 3, "Include pre-releases", t.prerelease, { v -> app.scope.launch { app.library.setOptions(id, v, t.apkFilter, t.autoUpdate) } })
                    SwitchItem(
                        1, 3, "Update automatically", t.autoUpdate,
                        { v -> app.scope.launch { app.library.setOptions(id, t.prerelease, t.apkFilter, v) } },
                        subtitle = "In the background, when the app is not in use",
                    )
                    GroupItem(2, 3, onClick = { editingFilter = true }) {
                        ItemRow("APK name filter", t.apkFilter.ifEmpty { "Automatic: the build for this device" })
                    }
                }
            }

            item { SectionTitle("Security") }
            item { Security(row, onReset = { resettingPin = true }) }

            // Fonte's own entry stays: it is how Fonte updates itself.
            if (!Library.isSelf(t)) item {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { removing = true }) {
                        Text("Remove from Fonte", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (editingFilter) FilterDialog(t.apkFilter, onDismiss = { editingFilter = false }) { f ->
        editingFilter = false
        app.scope.launch { app.library.setOptions(id, t.prerelease, f, t.autoUpdate) }
    }
    if (resettingPin) {
        AlertDialog(
            onDismissRequest = { resettingPin = false },
            title = { Text("Forget the certificate?") },
            text = {
                Text(
                    "Fonte then accepts the signing key of the next APK it installs. Do this only when the developer " +
                        "announced a new key and the app is not installed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resettingPin = false
                    app.library.resetPin(id)
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { resettingPin = false }) { Text("Cancel") } },
        )
    }
    if (removing) {
        var uninstall by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${t.name}?") },
            text = {
                Column {
                    Text("Fonte stops checking this app for updates.")
                    if (row.installed != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                            Checkbox(checked = uninstall, onCheckedChange = { uninstall = it })
                            Text("Also uninstall the app")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    val pkg = row.installed?.pkg
                    if (uninstall && pkg != null) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) }
                    }
                    app.library.remove(id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

private fun ago(time: Long): String =
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "Just now" else DateUtils.getRelativeTimeSpanString(time).toString()

@Composable
private fun Header(row: Row) {
    val t = row.app
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(t.packageName, row.installed?.versionCode, t.name, 88.dp)
        Spacer(Modifier.height(16.dp))
        Text(t.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            t.packageName ?: t.source.path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Actions(row: Row) {
    val context = LocalContext.current
    val app = context.app
    val t = row.app
    val task = row.task
    val latest = t.latest
    val installed = row.installed
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (task != null) {
            val d = task as? Installer.Task.Progress
            if (d != null && d.total > 0) {
                LinearWavyProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            Text(
                taskText(context, task),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A binder call: off the main thread, so the Open button comes one frame later.
            val launch = produceState<Intent?>(null, installed) {
                value = installed?.let { withContext(Dispatchers.IO) { app.packages.launchIntent(it.pkg) } }
            }.value
            when {
                row.update && latest != null -> Button(
                    onClick = { app.installer.start(t.id) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Update to ${latest.version}", maxLines = 1) }
                installed == null && latest != null -> Button(
                    onClick = { app.installer.start(t.id) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Install ${latest.version}", maxLines = 1) }
                launch != null -> FilledTonalButton(
                    onClick = { runCatching { context.startActivity(launch) } },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Open") }
            }
            if (installed != null) {
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", installed.pkg, null))) } },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(56.dp),
                ) { Text("Uninstall") }
            }
        }
    }
}

@Composable
private fun Security(row: Row, onReset: () -> Unit) {
    val context = LocalContext.current
    val t = row.app
    val cert = t.cert
    val installer by produceState<String?>(null, row.installed?.installer) {
        value = row.installed?.installer?.let { pkg ->
            if (pkg == context.packageName) "Fonte" else withContext(Dispatchers.IO) { context.app.packages.label(pkg) ?: pkg }
        }
    }
    val count = 1 + (if (row.installed != null) 1 else 0) + (if (cert != null) 1 else 0)
    Column {
        GroupItem(0, count) {
            ItemRow(
                "Signing certificate (SHA-256)",
                cert?.split(',')?.joinToString("\n\n") { c -> c.uppercase().chunked(16).joinToString("\n") { it.chunked(2).joinToString(":") } }
                    ?: "Pinned at the first install",
                subtitleLines = 8,
                mono = cert != null,
            ) {
                if (cert != null) IconButton(onClick = { copy(context, "Certificate", cert) }) { Icon(Icons.Copy, contentDescription = "Copy") }
            }
        }
        if (row.installed != null) GroupItem(1, count) { ItemRow("Installed by", installer ?: "Unknown") }
        if (cert != null) {
            GroupItem(count - 1, count, onClick = onReset) {
                ItemRow("Forget the certificate", "Only after a key change that the developer announced")
            }
        }
    }
}

@Composable
private fun FilterDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("APK name filter") },
        text = {
            Column {
                Text("A regular expression that the APK's file name must match. ! in front: names that must not match. Empty: automatic.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
