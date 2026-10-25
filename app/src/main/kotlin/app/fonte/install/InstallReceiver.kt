package app.fonte.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fonte.app
import kotlinx.coroutines.launch

/** Session results from PackageInstaller. Not exported: only the system sends here, through our PendingIntent. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.app
        app.scope.launch {
            try {
                app.installer.onStatus(intent)
            } finally {
                pending.finish()
            }
        }
    }
}
