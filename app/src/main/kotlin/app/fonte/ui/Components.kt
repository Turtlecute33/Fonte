package app.fonte.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fonte.app
import app.fonte.install.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A top bar with a back arrow (when [onBack] is set), a title and actions. */
@Composable
fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
        actions = actions,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action()
    }
}

/**
 * One item of a group of rows: a large radius at the outer corners of the group, a small one between items,
 * and a 2 dp gap (the Android 16 settings style). With [checked], the whole item is one switch.
 */
@Composable
fun GroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val big = 24.dp
    val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
    val outer = modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = if (index == count - 1) 0.dp else 2.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    when {
        checked != null -> Surface(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            shape = shape,
            color = color,
            modifier = outer.semantics { role = Role.Switch },
        ) { content() }
        onClick != null -> Surface(onClick = onClick, shape = shape, color = color, modifier = outer) { content() }
        else -> Surface(shape = shape, color = color, modifier = outer) { content() }
    }
}

/** Text rows inside a [GroupItem]. */
@Composable
fun ItemRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleLines: Int = 2,
    mono: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                val style = MaterialTheme.typography.bodyMedium
                Text(
                    subtitle,
                    style = if (mono) style.copy(fontFamily = FontFamily.Monospace) else style,
                    color = subtitleColor,
                    maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
fun SwitchItem(index: Int, count: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    // One focus stop: TalkBack reads the title, the subtitle and the state of the switch together.
    GroupItem(index, count, checked = checked, onCheckedChange = onChange, enabled = enabled) {
        ItemRow(title, subtitle) {
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** Installed apps show their icon; others a letter on a tonal circle. Icons are cached per package and version. */
@Composable
fun AppIcon(pkg: String?, version: Long?, name: String, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = if (pkg != null && version != null) "$pkg@$version@$px" else null
    val icon by produceState(key?.let(IconCache::get), key) {
        if (key != null && value == null) {
            value = withContext(Dispatchers.IO) { context.app.packages.icon(pkg!!, px) }?.asImageBitmap()?.also { IconCache.put(key, it) }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = if (size >= 56.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(96)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

/** A wavy ring for a download (with its share), or a spinning one for the steps without a known length. */
@Composable
fun TaskIndicator(task: Installer.Task, size: Dp = 36.dp) {
    val d = task as? Installer.Task.Downloading
    if (d != null && d.total > 0) {
        CircularWavyProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.size(size))
    } else {
        CircularWavyProgressIndicator(modifier = Modifier.size(size))
    }
}

fun taskText(context: Context, task: Installer.Task): String = when (task) {
    is Installer.Task.Downloading ->
        if (task.total > 0) "Downloading ${(task.done * 100 / task.total).coerceIn(0, 100)}% of ${size(context, task.total)}"
        else "Downloading ${size(context, task.done)}"
    Installer.Task.Installing -> "Installing…"
    Installer.Task.Waiting -> "Installs when the app is not in use"
}

fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens a web page. Only https: a link that a server sends cannot open a deep link into another app. */
fun openLink(context: Context, url: String) {
    val uri = Uri.parse(url).normalizeScheme()
    if (uri.scheme != "https" || uri.host.isNullOrEmpty()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

