package app.timiao.et.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.IntOffset
import app.timiao.et.ui.theme.LocalReducedMotion
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.timiao.et.ui.screens.AUTO_RULES_ROUTE
import app.timiao.et.ui.screens.AppSplitScreen
import app.timiao.et.ui.screens.AutoRulesScreen
import app.timiao.et.ui.screens.DiagnosticsScreen
import app.timiao.et.ui.screens.EventLogScreen
import app.timiao.et.ui.screens.LicensesScreen
import app.timiao.et.ui.screens.ShareScreen
import app.timiao.et.ui.screens.SplitRoutes
import app.timiao.et.ui.screens.TrafficScreen
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
    const val DIAGNOSTICS = "diagnostics"
    const val TRAFFIC = "traffic"
    const val SCAN = "scan"
    const val LICENSES = "licenses"
    const val SHARE_PATTERN = "share/{id}"
    fun share(id: String) = "share/$id"
    const val EDIT_PATTERN = "network/{id}?open={open}"
    fun edit(id: String, open: String = "none") = "network/$id?open=$open"
}

/** Tabs only; every pushed page uses its own back button and hides the dock. */
private val TabRoutes = setOf(Routes.HOME, Routes.PEERS, Routes.NETWORKS, Routes.SETTINGS)

@Composable
fun TimiaoRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    // Deep-link/file import finished: show the imported network so the user can check it.
    val pendingOpen by vm.pendingOpenNetwork.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen) {
        val target = pendingOpen ?: return@LaunchedEffect
        vm.consumePendingOpenNetwork()
        nav.navigate(Routes.edit(target.id, if (target.focusSecret) "secret" else "none")) {
            launchSingleTop = true
        }
    }

    val dockRoute = route?.takeIf { it in TabRoutes }

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
                    actions = { peer -> app.timiao.et.share.PeerActionsSheetContent(peer, onDone = {}) },
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
                SettingsScreen(vm, onOpen = { nav.navigate(it) })
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    configProvider = { net -> vm.rawConfig(net.id) },
                )
            }
            composable(Routes.SCAN) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val container = (ctx.applicationContext as app.timiao.et.TimiaoApplication).container
                app.timiao.et.ui.screens.ScanScreen(
                    importSink = { toml, hasSecret ->
                        val cfg = importSharedToml(container, toml)
                        // Same hand-off as a deep link: open the editor, focus the secret if absent.
                        vm.requestOpenNetwork(cfg.id, focusSecret = !hasSecret)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.TRAFFIC) { TrafficScreen(vm, recorder = vm.trafficRecorder) }
            composable(Routes.LICENSES) { LicensesScreen(onBack = { nav.popBackStack() }) }
            composable(AUTO_RULES_ROUTE) { AutoRulesScreen(vm) { nav.popBackStack() } }
            composable(SplitRoutes.PATTERN, listOf(navArgument("networkId") { type = NavType.StringType })) {
                AppSplitScreen(SplitStoreGlobalId) { nav.popBackStack() }
            }
            composable(Routes.SHARE_PATTERN, listOf(navArgument("id") { type = NavType.StringType })) { b ->
                ShareScreen(vm, b.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
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
                    perAppSlot = {
                        app.timiao.et.ui.screens.AppSplitEntryRow(SplitStoreGlobalId) {
                            nav.navigate(SplitRoutes.of(SplitStoreGlobalId))
                        }
                    },
                    onShare = { nav.navigate(Routes.share(it)) },
                    onScan = { nav.navigate(Routes.SCAN) },
                )
            }
        }
        AnimatedVisibility(
            visible = dockRoute != null,
            enter = if (reduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 2 }
            },
            exit = if (reduced) {
                ExitTransition.None
            } else {
                fadeOut(tween(120)) + slideOutVertically(tween(170)) { it / 3 }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GlassDock(
                currentRoute = dockRoute,
                onNavigate = { target -> nav.navigateTopLevel(target) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

private const val SplitStoreGlobalId = app.timiao.et.split.SplitStore.GLOBAL_ID

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

/** Saves an imported config; a repeated instance id gets a fresh one so two devices never collide. */
suspend fun importSharedToml(
    container: app.timiao.et.di.AppContainer,
    toml: String,
): app.timiao.et.core.NetworkConfig {
    var cfg = container.configRepository.importToml(toml)
    if (container.configRepository.get(cfg.id) != null) {
        cfg = cfg.copy(instanceId = java.util.UUID.randomUUID().toString())
    }
    container.configRepository.save(cfg)
    return cfg
}
