package app.timiao.et.ui.navigation

import androidx.compose.foundation.layout.Box
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
import app.timiao.et.ui.screens.HomeScreen
import app.timiao.et.ui.screens.NetworkEditScreen
import app.timiao.et.ui.screens.NetworksScreen
import app.timiao.et.ui.screens.PeersScreen
import app.timiao.et.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PEERS = "peers"
    const val NETWORKS = "networks"
    const val SETTINGS = "settings"
    const val EDIT_PATTERN = "network/{id}?open={open}"
    fun edit(id: String, open: String = "none") = "network/$id?open=$open"
}

@Composable
fun TimiaoRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    val dockRoute = when {
        route == null -> Routes.HOME
        route.startsWith("network/") -> Routes.NETWORKS
        else -> route
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            composable(Routes.HOME) {
                HomeScreen(vm) { target -> nav.navigateTopLevel(target) }
            }
            composable(Routes.PEERS) {
                PeersScreen(vm)
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

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
