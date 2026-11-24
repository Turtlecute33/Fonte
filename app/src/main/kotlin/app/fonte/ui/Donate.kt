package app.fonte.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The BTCPay donation page. The browser opens it; the app itself does not connect to this host. */
const val DONATE_URL =
    "https://btcpay.priorato.org/api/v1/invoices?storeId=31ntjiLSTmSSuTC6FrR81z6AAku46bLF2FKAL3MFHtV5&orderId=Donation&checkoutDesc=Donation&currency=USD"

/**
 * A small donation card when the user opens the app from the launcher: first 7 days after the install, then
 * at most once each 90 days (365 days after a tap on Donate). No notification and no network. The only data
 * is the time of the next card, in a private prefs file.
 */
@Composable
fun DonatePrompt() {
    val activity = LocalActivity.current ?: return
    var show by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Not for a shared link: the user came to do a task.
        if (activity.intent?.action == Intent.ACTION_MAIN && withContext(Dispatchers.IO) { Donate.claim(activity) }) show = true
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        icon = { Icon(Icons.Beer, null) },
        title = { Text("Enjoying Fonte?") },
        text = { Text("Buy the dev a beer or send a few sats. Sats buy AI tokens for security reviews. Beer buys a happy dev.") },
        confirmButton = {
            Button(onClick = {
                show = false
                Donate.snooze(activity, Donate.AFTER_DONATE_DAYS)
                openLink(activity, DONATE_URL)
            }) { Text("Donate") }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Remind me in 3 months") } },
    )
}

private object Donate {
    const val AFTER_DONATE_DAYS = 365L
    private const val FIRST_DAYS = 7L
    private const val LATER_DAYS = 90L
    private const val DAY_MS = 86_400_000L
    private const val NEXT = "next"

    private fun prefs(context: Context) = context.getSharedPreferences("donate", Context.MODE_PRIVATE)

    /** True when the card is due. It then sets the next card at once, so that Back or a closed app also waits 90 days. */
    fun claim(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(now)
        if (now < prefs(context).getLong(NEXT, installed + FIRST_DAYS * DAY_MS)) return false
        snooze(context, LATER_DAYS)
        return true
    }

    @SuppressLint("UseKtx") // core-ktx is not a direct dependency
    fun snooze(context: Context, days: Long) =
        prefs(context).edit().putLong(NEXT, System.currentTimeMillis() + days * DAY_MS).apply()
}
