package app.fonte.ui

import android.content.Context
import android.net.Uri
import app.fonte.FonteApp
import app.fonte.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The file types that the import picker shows: an Obtainium export can have any of them. */
internal val IMPORT_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

/**
 * Reads an Obtainium or Fonte export (at most [MAX_IMPORT] bytes), adds its apps and starts a check of all apps.
 * Returns the snackbar text. The home and settings screens share it.
 */
internal suspend fun importFile(context: Context, uri: Uri, app: FonteApp): String = try {
    val text = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_IMPORT).decodeToString() } ?: ""
    }
    val r = app.library.import(text)
    // Reads the releases of the new entries; runs on in the app scope when the screen goes.
    app.scope.launch { app.library.checkAll() }
    buildString {
        append(if (r.added == 1) "Imported 1 app" else "Imported ${r.added} apps")
        if (r.known > 0) append(" · ${r.known} in the list already")
        if (r.skipped.isNotEmpty()) append(" · ${r.skipped.size} from sources Fonte does not read")
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Library.userText(e, "Import failed")
}

private const val MAX_IMPORT = 16 shl 20
