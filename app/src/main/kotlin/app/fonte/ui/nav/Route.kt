package app.fonte.ui.nav

/** Screens. The back stack lives in [Navigator] (a ViewModel). */
sealed interface Route {
    data object Home : Route
    data class Detail(val id: String) : Route
    data object Settings : Route
}
