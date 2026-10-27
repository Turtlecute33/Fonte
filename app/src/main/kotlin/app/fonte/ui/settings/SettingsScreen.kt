package app.fonte.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fonte.BuildConfig
import app.fonte.app
import app.fonte.data.Settings
import app.fonte.ui.Bar
import app.fonte.ui.GroupItem
import app.fonte.ui.ItemRow
import app.fonte.ui.SectionTitle
import app.fonte.ui.SwitchItem
import app.fonte.ui.nav.Navigator
import app.fonte.ui.openLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun SettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val data by app.store.state.collectAsStateWithLifecycle()
    val tokenSet by app.token.isSet.collectAsStateWithLifecycle()
    val s = data.settings
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editingToken by rememberSaveable { mutableStateOf(false) }
    fun set(change: (Settings) -> Settings) = app.store.updateSettings(change)

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    // A provider can return no stream; that is a failure, not an empty success.
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the file")
                    out.use { it.write(app.library.export().toByteArray()) }
                }
                "Exported ${app.store.apps.size} apps"
            } catch (e: Exception) {
                e.message ?: "Export failed"
            }
            snackbar.showSnackbar(text)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readNBytes(16 shl 20).decodeToString() } ?: ""
                }
                val r = app.library.import(json)
                app.scope.launch { app.library.checkAll() }
                buildString {
                    append(if (r.added == 1) "Imported 1 app" else "Imported ${r.added} apps")
                    if (r.known > 0) append(" · ${r.known} in the list already")
                    if (r.skipped.isNotEmpty()) append(" · ${r.skipped.size} from sources Fonte does not read")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Import failed"
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(topBar = { Bar("Settings", onBack = nav::back) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Updates")
            val count = if (s.background) 4 else 1
            SwitchItem(0, count, "Check in the background", s.background, { v ->
                set { it.copy(background = v) }
                if (v && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            })
            if (s.background) {
                GroupItem(1, count) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                        Text("Check every", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        val options = listOf(3, 6, 12, 24)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, hours ->
                                SegmentedButton(
                                    selected = s.intervalHours == hours,
                                    onClick = { set { it.copy(intervalHours = hours) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text("$hours h") }
                            }
                        }
                    }
                }
                SwitchItem(
                    2, count, "Install automatically", s.autoInstall, { v -> set { it.copy(autoInstall = v) } },
                    subtitle = "When the app is not in use. Apps that another store installed ask once.",
                )
                SwitchItem(
                    3, count, "Download only on unmetered networks", s.unmeteredOnly, { v -> set { it.copy(unmeteredOnly = v) } },
                    subtitle = "For automatic installs. Checks use any network.",
                )
            }

            SectionTitle("GitHub")
            GroupItem(0, 1, onClick = { editingToken = true }) {
                ItemRow(
                    "Access token",
                    if (tokenSet) "Set · 5,000 requests an hour" else "Not set · 60 requests an hour. Tap for a guide.",
                )
            }

            SectionTitle("Apps")
            GroupItem(0, 2, onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                ItemRow("Import", "From an Obtainium or Fonte export")
            }
            GroupItem(1, 2, onClick = { exporter.launch("fonte-apps.json") }) {
                ItemRow("Export", "The list, its options and the pinned certificates. No token.")
            }

            SectionTitle("About")
            GroupItem(0, 2) { ItemRow("Fonte ${BuildConfig.VERSION_NAME}", "No tracking, no analytics, no account. GPL-3.0.") }
            GroupItem(1, 2, onClick = { openLink(context, "https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens") }) {
                ItemRow("About GitHub tokens", "docs.github.com")
            }
        }
    }

    if (editingToken) TokenSheet(
        isSet = tokenSet,
        onDismiss = { editingToken = false },
        onSave = { value ->
            editingToken = false
            scope.launch {
                val text = try {
                    withContext(Dispatchers.IO) { app.token.set(value) }
                    if (value.isNullOrBlank()) "Token removed" else "Token saved"
                } catch (e: Exception) {
                    e.message ?: "Could not save the token"
                }
                snackbar.showSnackbar(text)
            }
        },
    )
}
