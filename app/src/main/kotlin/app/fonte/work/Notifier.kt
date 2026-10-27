package app.fonte.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.fonte.MainActivity
import app.fonte.R

/** Three quiet channels: available updates, install prompts, installed updates. Nothing is shown on the lock screen. */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** Creates the channels. Once per process start, off the main thread. */
    fun setUp() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(UPDATES, "Available updates", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(PROMPTS, "Install requests", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(INSTALLED, "Installed updates", NotificationManager.IMPORTANCE_MIN),
            ).onEach { it.lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        )
    }

    fun updatesAvailable(names: List<String>) {
        if (names.isEmpty()) return nm.cancel(TAG_UPDATES, 0)
        val title = if (names.size == 1) "Update for ${names[0]}" else "${names.size} updates"
        post(TAG_UPDATES, 0, builder(UPDATES).setContentTitle(title).setContentText(names.joinToString(", ")).setContentIntent(openApp()))
    }

    /** After a change in the app: [updatesAvailable], but only while that notification shows. One that the user removed stays away. */
    fun refreshUpdates(names: List<String>) {
        if (nm.activeNotifications.any { it.tag == TAG_UPDATES }) updatesAvailable(names)
    }

    /** The app cannot install without the user: a tap opens the system prompt for this session. */
    fun confirm(sessionId: Int, name: String, prompt: Intent) {
        val tap = PendingIntent.getActivity(
            context,
            sessionId,
            prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(TAG_PROMPT, sessionId, builder(PROMPTS).setContentTitle("Tap to install $name").setContentIntent(tap).setAutoCancel(true))
    }

    fun cancelConfirm(sessionId: Int) = nm.cancel(TAG_PROMPT, sessionId)

    fun installed(name: String, version: String?) {
        val text = if (version.isNullOrEmpty()) name else "$name $version"
        post(TAG_INSTALLED, name.hashCode(), builder(INSTALLED).setContentTitle("Updated").setContentText(text).setContentIntent(openApp()).setAutoCancel(true))
    }

    private fun builder(channel: String) = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)

    private fun openApp() = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(tag: String, id: Int, builder: Notification.Builder) {
        if (nm.areNotificationsEnabled()) nm.notify(tag, id, builder.build())
    }

    private companion object {
        const val UPDATES = "updates"
        const val PROMPTS = "prompts"
        const val INSTALLED = "installed"
        const val TAG_UPDATES = "updates"
        const val TAG_PROMPT = "prompt"
        const val TAG_INSTALLED = "installed"
    }
}
