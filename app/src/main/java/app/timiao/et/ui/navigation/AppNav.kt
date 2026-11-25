package app.timiao.et.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.IntOffset
import app.timiao.et.ui.theme.LocalReducedMotion
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.GlassBackdrop
import app.timiao.et.ui.components.GlassDock
import app.timiao.et.ui.screens.EventLogScreen
import app.timiao.et.ui.screens.HomeScreen
import app.timiao.et.ui.screens.NetworkEditScreen
import app.timiao.et.ui.screens.NetworksScreen
import app.timiao.et.ui.screens.NodeInfoScreen
import app.timiao.et.ui.screens.PeersScreen
import app.timiao.et.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PEERS = "peers"
    const val NETWORKS = "networks"
    const val SETTINGS = "settings"
    const val NODE_INFO = "node_info"
    const val EVENT_LOG = "event_log"
    const val EDIT_PATTERN = "network/{id}?open={open}"
    fun edit(id: String, open: String = "none") = "network/$id?open=$open"
}

/** Screens pushed on top of a tab; the dock keeps the owning tab highlighted. */
private fun dockOwner(route: String?): String? = when (route) {
    Routes.NODE_INFO -> Routes.HOME
    Routes.EVENT_LOG -> Routes.PEERS
    else -> route
}

@Composable
fun TimiaoRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    val dockRoute = when {
        route == null -> Routes.HOME
        route.startsWith("network/") -> Routes.NETWORKS
        else -> dockOwner(route) ?: Routes.HOME
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        val reduced = LocalReducedMotion.current
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            enterTransition = { pageEnter(initialState.destination.route, targetState.destination.route, reduced) },
            exitTransition = { pageExit(initialState.destination.route, targetState.destination.route, reduced) },
            popEnterTransition = { pageEnter(initialState.destination.route, targetState.destination.route, reduced) },
            popExitTransition = { pageExit(initialState.destination.route, targetState.destination.route, reduced) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(vm) { target ->
                    if (target == Routes.NODE_INFO) nav.navigate(Routes.NODE_INFO)
                    else nav.navigateTopLevel(target)
                }
            }
            composable(Routes.PEERS) {
                PeersScreen(
                    vm = vm,
                    onNavigate = { target ->
                        if (target == Routes.EVENT_LOG) nav.navigate(Routes.EVENT_LOG)
                        else nav.navigateTopLevel(target)
                    },
                )
            }
            composable(Routes.NODE_INFO) {
                NodeInfoScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(Routes.EVENT_LOG) {
                EventLogScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(Routes.NETWORKS) {
                NetworksScreen(vm) { nav.navigate(it) }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm)
            }
            composable(
                route = Routes.EDIT_PATTERN,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("open") {
                        type = NavType.StringType
                        defaultValue = "none"
                    },
                ),
            ) { backStack ->
                val id = backStack.arguments?.getString("id") ?: "new"
                val open = backStack.arguments?.getString("open") ?: "none"
                NetworkEditScreen(
                    vm = vm,
                    networkId = id,
                    openAction = open,
                    onBack = { nav.popBackStack() },
                )
            }
        }
        GlassDock(
            currentRoute = dockRoute,
            onNavigate = { target -> nav.navigateTopLevel(target) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

private val TabOrder = listOf(Routes.HOME, Routes.PEERS, Routes.NETWORKS, Routes.SETTINGS)

/** Tab index; the edit page counts as one step to the right of its tab. */
private fun order(route: String?): Float = when {
    route == null -> 0f
    route.startsWith("network/") -> TabOrder.indexOf(Routes.NETWORKS) + 0.5f
    else -> TabOrder.indexOf(route).coerceAtLeast(0).toFloat()
}

private val PageEase = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)

private fun pageEnter(from: String?, to: String?, reduced: Boolean): EnterTransition {
    val fade = fadeIn(tween(200, delayMillis = 40))
    if (reduced) return fade
    val dir = if (order(to) >= order(from)) 1 else -1
    val push = to?.startsWith("network/") == true || from?.startsWith("network/") == true
    return fade + slideInHorizontally(PageEase) { w -> dir * (if (push) w else w / 10) }
}

private fun pageExit(from: String?, to: String?, reduced: Boolean): ExitTransition {
    val fade = fadeOut(tween(140))
    if (reduced) return fade
    val dir = if (order(to) >= order(from)) -1 else 1
    val push = to?.startsWith("network/") == true || from?.startsWith("network/") == true
    return fade + slideOutHorizontally(PageEase) { w -> dir * (if (push) w / 4 else w / 10) }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
