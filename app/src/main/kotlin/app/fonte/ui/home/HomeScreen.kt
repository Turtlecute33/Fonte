package app.fonte.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fonte.Library
import app.fonte.Row
import app.fonte.app
import app.fonte.ui.AppIcon
import app.fonte.ui.GroupItem
import app.fonte.ui.IMPORT_TYPES
import app.fonte.ui.Icons
import app.fonte.ui.ItemRow
import app.fonte.ui.SectionTitle
import app.fonte.ui.TaskIndicator
import app.fonte.ui.importFile
import app.fonte.ui.nav.Navigator
import app.fonte.ui.nav.Route
import app.fonte.ui.taskText
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val rows by app.rows.collectAsStateWithLifecycle()
    val checking by app.library.checking.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addLink by rememberSaveable { mutableStateOf("") }
    var canInstall by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose {}
    }
    LaunchedEffect(nav.sharedLink) {
        nav.sharedLink?.let {
            addLink = it
            adding = true
            nav.sharedLink = null
        }
    }
    LaunchedEffect(Unit) { app.installer.messages.collect { snackbar.showSnackbar(it) } }

    /** Apps that the user added: Fonte's own entry does not count. */
    fun added() = app.store.apps.count { !Library.isSelf(it) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (app.store.settings.background &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val first = added() == 0
            val text = importFile(context, uri, app)
            snackbar.showSnackbar(text)
            if (first && added() > 0) askNotifications()
        }
    }

    val (updates, installed, missing) = remember(rows) {
        Triple(rows.filter { it.update }, rows.filter { !it.update && it.installed != null }, rows.filter { it.installed == null })
    }
    // Only Fonte itself, with nothing to do: the start screen shows, with its import button.
    val empty = rows.all { Library.isSelf(it.app) && !it.update && it.task == null }
    val list = rememberLazyListState()
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val refresh = rememberPullToRefreshState()

    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Fonte") },
                subtitle = {
                    Text(
                        when {
                            checking -> "Checking…"
                            empty -> "No apps"
                            updates.isNotEmpty() -> if (updates.size == 1) "1 update" else "${updates.size} updates"
                            installed.isNotEmpty() -> "Up to date"
                            else -> if (rows.size == 1) "1 app" else "${rows.size} apps"
                        },
                        // Pull to refresh is only a gesture: TalkBack and Switch Access get the same check as an action.
                        modifier = Modifier.semantics {
                            if (!checking) customActions = listOf(
                                CustomAccessibilityAction("Check for updates") { scope.launch { app.library.checkAll() }; true },
                            )
                        },
                    )
                },
                actions = {
                    IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = bar,
            )
        },
        floatingActionButton = {
            if (!empty) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    expanded = atTop,
                    icon = { Icon(Icons.Add, contentDescription = null) },
                    text = { Text("Add app") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = checking,
            onRefresh = { scope.launch { app.library.checkAll() } },
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = checking, modifier = Modifier.align(Alignment.TopCenter))
            },
        ) {
            if (empty) {
                Empty(onAdd = { adding = true }, onImport = { importer.launch(IMPORT_TYPES) })
            } else {
                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 104.dp), modifier = Modifier.fillMaxSize()) {
                    // The list keeps its first visible item in view when items go in above it. At the top, stay at
                    // the top: the installed state arrives after the first layout, and an update moves its app up.
                    // This block runs again when the items change, before the list measures them.
                    Snapshot.withoutReadObservation {
                        if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 && !list.isScrollInProgress) {
                            list.requestScrollToItem(0)
                        }
                    }
                    if (!canInstall) item(key = "permission") { InstallPermission(context) }
                    section("Updates", updates, nav) {
                        if (updates.any { it.task == null }) {
                            TextButton(onClick = { updates.forEach { app.installer.start(it.app.id) } }) { Text("Update all") }
                        }
                    }
                    section("Installed", installed, nav)
                    section("Not installed", missing, nav)
                }
            }
        }
    }

    if (adding) {
        AddSheet(
            initial = addLink,
            onDismiss = {
                adding = false
                addLink = ""
            },
            onAdded = { added ->
                adding = false
                addLink = ""
                scope.launch {
                    if (added() == 1) askNotifications()
                    if (added.packageName?.let(app.packages::info) == null) {
                        val r = snackbar.showSnackbar("Added ${added.name}", actionLabel = "Install", withDismissAction = true, duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) app.installer.start(added.id)
                    } else {
                        snackbar.showSnackbar("Added ${added.name}")
                    }
                }
            },
        )
    }
}

private fun LazyListScope.section(
    title: String,
    rows: List<Row>,
    nav: Navigator,
    action: @Composable RowScope.() -> Unit = {},
) {
    if (rows.isEmpty()) return
    item(key = "title:$title", contentType = "title") { SectionTitle(title, Modifier.animateItem(), action) }
    itemsIndexed(rows, key = { _, r -> r.app.id }, contentType = { _, _ -> "app" }) { i, row ->
        AppItem(row, i, rows.size, onOpen = { nav.go(Route.Detail(row.app.id)) }, modifier = Modifier.animateItem())
    }
}

@Composable
private fun AppItem(row: Row, index: Int, count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = row.app
    val task = row.task
    val installed = row.installed
    val latest = app.latest
    val subtitle = when {
        task != null -> taskText(context, task)
        row.update && installed != null && latest != null -> "${installed.versionName} → ${latest.version}"
        app.error != null -> app.error
        installed != null -> installed.versionName
        latest != null -> latest.version
        else -> "Not checked yet"
    }
    val failed = task == null && !row.update && app.error != null
    GroupItem(index, count, modifier, onClick = onOpen) {
        ItemRow(
            title = app.name,
            subtitle = subtitle,
            leading = { AppIcon(app.packageName, installed?.versionCode, app.name, 44.dp) },
            subtitleColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            subtitleLines = 1,
        ) {
            Spacer(Modifier.width(12.dp))
            when {
                task != null -> TaskIndicator(task)
                row.update -> FilledTonalButton(onClick = { context.app.installer.start(app.id) }, shapes = ButtonDefaults.shapes()) { Text("Update") }
                installed == null && latest != null ->
                    OutlinedButton(onClick = { context.app.installer.start(app.id) }, shapes = ButtonDefaults.shapes()) { Text("Install") }
            }
        }
    }
}

@Composable
private fun InstallPermission(context: Context) {
    GroupItem(0, 1, Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Allow Fonte to install apps", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android needs your consent once. Without it, Fonte can check for updates but not install them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text("Allow") }
        }
    }
}

@Composable
private fun Empty(onAdd: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("No apps yet", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add apps from GitHub, GitLab, Codeberg, Forgejo, F-Droid or IzzyOnDroid. Fonte keeps them up to date.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAdd, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add app")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onImport, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Import from Obtainium")
        }
    }
}
