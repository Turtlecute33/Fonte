package app.fonte.ui.settings

import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.fonte.ui.Icons
import app.fonte.ui.openLink

/**
 * The GitHub token: a guide to make a fine-grained token with the least access, and the field to save it. The
 * guide opens GitHub's token form with the name, the expiry and (for private repositories) the permission set.
 */
@Composable
fun TokenSheet(isSet: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val context = LocalContext.current
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    var private by rememberSaveable { mutableStateOf(false) }
    // Not saveable: the token stays out of the saved instance state.
    var value by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Text("GitHub token", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Optional. With a token, GitHub allows 5,000 requests an hour instead of 60; each check of an app is " +
                    "one. Apps in private repositories need a token.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text("Your apps on GitHub are in", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !private, onClick = { private = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("Public repos only")
                }
                SegmentedButton(selected = private, onClick = { private = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Private repos too")
                }
            }
            Spacer(Modifier.height(20.dp))
            (if (private) PRIVATE_STEPS else PUBLIC_STEPS).forEachIndexed { i, step -> Step(i + 1, step) }
            Text(
                "When the token expires, checks show \"GitHub refused the token\". Then make a new one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = { openLink(context, tokenForm(private)) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create token on GitHub")
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.trim() },
                label = { Text(if (isSet) "New token" else "Token") },
                placeholder = { Text("github_pat_…") },
                supportingText = {
                    Text(
                        "Encrypted with a key in the phone's secure hardware. Sent only to api.github.com, with each " +
                            "check: GitHub links the checks to your account.",
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { value = it }
                    }) { Icon(Icons.Paste, contentDescription = "Paste") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(value) },
                enabled = value.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Save") }
            if (isSet) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onSave(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Remove token", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** One numbered step. Words between asterisks are the names on GitHub's page, in bold. */
@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(bottom = 14.dp)) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                text.split('*').forEachIndexed { i, part ->
                    if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(top = 2.dp),
        )
    }
}

private val PUBLIC_STEPS = listOf(
    "Tap *Create token on GitHub* and sign in. The name and the expiry (one year) are set.",
    "At *Repository access*, select *Public repositories*.",
    "Add no permissions. A token can always read public repositories.",
    "Tap *Generate token*. Copy the token and paste it below.",
)

private val PRIVATE_STEPS = listOf(
    "Tap *Create token on GitHub* and sign in. The name, the expiry (one year) and the permission are set.",
    "At *Resource owner*, select the account or the organization that owns the repositories.",
    "At *Repository access*, select *Only select repositories*. Then select the repositories of your apps.",
    "At *Permissions*, *Contents* must be *Read-only*. GitHub adds *Metadata* (read-only). Add no other permissions.",
    "Tap *Generate token*. Copy the token and paste it below.",
)

/** GitHub's form for a new fine-grained token, filled in through its template parameters. */
private fun tokenForm(private: Boolean): String = buildString {
    append("https://github.com/settings/personal-access-tokens/new?name=Fonte&expires_in=366&description=")
    append(Uri.encode("Fonte: reads releases to update apps"))
    if (private) append("&contents=read")
}
