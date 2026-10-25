package app.fonte

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fonte.source.Links
import app.fonte.ui.nav.AppNav
import app.fonte.ui.nav.Navigator
import app.fonte.ui.nav.Route
import app.fonte.ui.theme.FonteTheme
import kotlinx.coroutines.launch

/** The only activity. Starts the system install prompts while it is visible. */
class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        keepSplashUntilReady()
        if (savedInstanceState == null) takeSharedLink(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.installer.confirmations.collect { runCatching { startActivity(it) } }
            }
        }
        setContent {
            FonteTheme {
                Surface(Modifier.fillMaxSize()) { AppNav(nav) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedLink(intent)
    }

    override fun onStart() {
        super.onStart()
        app.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.onBackground()
    }

    /** A link shared from another app (a browser) opens the add sheet. Only the text is read. */
    private fun takeSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(2048) ?: return
        nav.home()
        nav.go(Route.Home)
        nav.sharedLink = Links.firstUrl(text) ?: text.trim()
    }

    /** Holds the system splash screen until the list is read, so that no empty list flashes. */
    private fun keepSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!app.ready.value) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }
}
