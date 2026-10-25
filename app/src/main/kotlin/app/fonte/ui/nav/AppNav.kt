package app.fonte.ui.nav

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.fonte.ui.detail.DetailScreen
import app.fonte.ui.home.HomeScreen
import app.fonte.ui.settings.SettingsScreen

/** Shows the top route of [nav]. System back and the predictive back gesture pop the stack. */
@Composable
fun AppNav(nav: Navigator, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    if (nav.finished) LaunchedEffect(Unit) { activity?.finish() }

    val forward = sharedAxis<Scene<Route>>(forward = true)
    val backward = sharedAxis<Scene<Route>>(forward = false)
    NavDisplay(
        backStack = nav.backStack,
        modifier = modifier,
        onBack = nav::back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = forward,
        popTransitionSpec = backward,
        predictivePopTransitionSpec = { backward() },
        entryProvider = entryProvider {
            entry<Route.Home> { HomeScreen(nav) }
            entry<Route.Detail> { DetailScreen(it.id, nav) }
            entry<Route.Settings> { SettingsScreen(nav) }
        },
    )
}

private typealias Transition<S> = AnimatedContentTransitionScope<S>.() -> ContentTransform

/** Material shared-axis X with the theme's expressive motion: a short slide plus a cross-fade. */
@Composable
private fun <S> sharedAxis(forward: Boolean): Transition<S> {
    val motion = MaterialTheme.motionScheme
    val slide = motion.defaultSpatialSpec<IntOffset>()
    val fadeIn = motion.defaultEffectsSpec<Float>()
    val fadeOut = motion.fastEffectsSpec<Float>()
    val sign = if (forward == (LocalLayoutDirection.current == LayoutDirection.Ltr)) 1 else -1
    val offset = sign * with(LocalDensity.current) { 48.dp.roundToPx() }
    return {
        (slideInHorizontally(slide) { offset } + fadeIn(fadeIn)) togetherWith
            (slideOutHorizontally(slide) { -offset } + fadeOut(fadeOut))
    }
}
