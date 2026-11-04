package app.fonte.ui.home

import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fonte.Library
import app.fonte.app
import app.fonte.data.TrackedApp
import app.fonte.ui.Icons
import app.fonte.ui.ItemRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add an app by link. The link is resolved and its latest release read before the app joins the list, so a
 * wrong link shows its error here. A link to an F-Droid repo shows the apps of its signed index to choose from.
 * The clipboard is read only when the user taps Paste.
 */
@Composable
fun AddSheet(initial: String, onDismiss: () -> Unit, onAdded: (TrackedApp) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val focus = remember { FocusRequester() }
    // Keyed on the initial link: a link shared while the sheet is open replaces the old one (Review UIUX-6).
    var link by rememberSaveable(initial) { mutableStateOf(initial) }
    var prerelease by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    var choices by remember(initial) { mutableStateOf(emptyList<Library.Choice>()) }

    fun submit(target: String = link, name: String? = null) {
        if (busy || target.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                when (val added = app.library.add(target, prerelease, filter, name)) {
                    is Library.Added.Choose -> choices = added.apps
                    is Library.Added.App -> {
                        sheet.hide()
                        onAdded(added.app)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Library.userText(e, "Could not add this app")
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        // Scrolls, so that the Add button stays reachable with the keyboard, in landscape and at large font (Review UIUX-4).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Text("Add app", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "A link to a repository on GitHub, GitLab, Codeberg or Forgejo, to an app on F-Droid or IzzyOnDroid, " +
                    "or to an F-Droid repo with its fingerprint.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    error = null
                    choices = emptyList()
                },
                label = { Text("Link") },
                placeholder = { Text("github.com/owner/repo") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let {
                            link = it
                            error = null
                            choices = emptyList()
                        }
                    }) { Icon(Icons.Paste, contentDescription = "Paste") }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(
                Modifier.fillMaxWidth().toggleable(prerelease, role = Role.Switch) { prerelease = it }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Include pre-releases", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = prerelease, onCheckedChange = null)
            }
            if (showFilter) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("APK name filter") },
                    supportingText = { Text("A regular expression. ! in front: names that do not match.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { showFilter = true }) { Text("Choose APKs by name") }
            }
            if (choices.isNotEmpty()) {
                Text("Choose an app", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(choices, key = { it.packageName }) { c ->
                        Box(Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !busy) { submit(c.link, c.name) }) {
                            ItemRow(c.name, c.packageName, subtitleLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && link.isNotBlank() && choices.isEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary) else Text("Add")
            }
        }
    }
    LaunchedEffect(Unit) { if (initial.isEmpty()) focus.requestFocus() }
}
