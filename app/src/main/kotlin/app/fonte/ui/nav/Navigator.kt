package app.fonte.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** The back stack. A ViewModel, so it survives configuration changes. Every route is in it at most once. */
class Navigator : ViewModel() {
    private val stack = mutableStateListOf<Route>(Route.Home)

    val backStack: List<Route> get() = stack

    /** True after [back] on the last route: the activity should finish. */
    var finished by mutableStateOf(false)
        private set

    /** A link shared to the app; the home screen opens the add sheet with it. */
    var sharedLink by mutableStateOf<String?>(null)

    /** Opens [route]. When it is in the stack already, goes back to it instead. */
    fun go(route: Route) {
        val index = stack.indexOf(route)
        if (index < 0) stack += route else stack.removeRange(index + 1, stack.size)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finished = true
    }

    fun home() = stack.removeRange(1, stack.size)
}
