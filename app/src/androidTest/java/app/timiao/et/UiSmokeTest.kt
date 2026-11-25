package app.timiao.et

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.timiao.et.core.FakeConfigRepository
import app.timiao.et.core.FakeEngine
import app.timiao.et.core.LinkState
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.CatMascot
import app.timiao.et.ui.components.GlassDock
import app.timiao.et.ui.data.AppSettings
import app.timiao.et.ui.data.SettingsStore
import app.timiao.et.ui.screens.HomeScreen
import app.timiao.et.ui.theme.LocalReducedMotion
import app.timiao.et.ui.theme.TimiaoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dockShowsAllTabsAndNavigates() {
        var selected: String? = null
        compose.setContent {
            TimiaoTheme(AppSettings()) {
                GlassDock(currentRoute = "home", onNavigate = { selected = it })
            }
        }
        compose.onNodeWithText("首页").assertIsDisplayed()
        compose.onNodeWithText("节点").assertIsDisplayed().performClick()
        assertEquals("peers", selected)
        compose.onNodeWithText("网络").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun catExposesAllStateDescriptions() {
        compose.setContent {
            TimiaoTheme(AppSettings()) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        CatMascot(LinkState.Idle, Modifier.size(80.dp))
                        CatMascot(LinkState.Connecting, Modifier.size(80.dp))
                        CatMascot(LinkState.Connected, Modifier.size(80.dp))
                        CatMascot(LinkState.Error, Modifier.size(80.dp))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("小猫在睡觉（未连接）").assertExists()
        compose.onNodeWithContentDescription("小猫在找节点（连接中）").assertExists()
        compose.onNodeWithContentDescription("小猫已连接").assertExists()
        compose.onNodeWithContentDescription("小猫出错").assertExists()
    }

    @Test
    fun homeShowsConnectedNetworkAndStats() {
        val vm = AppViewModel(
            engine = FakeEngine(),
            repository = FakeConfigRepository(),
            settingsStore = SettingsStore(compose.activity),
        )
        compose.setContent {
            TimiaoTheme(AppSettings()) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    HomeScreen(vm = vm, onNavigate = {})
                }
            }
        }
        compose.waitUntil(5_000L) { vm.networks.value.isNotEmpty() }
        compose.onNodeWithText("已连接 home-mesh").assertExists()
    }
}
