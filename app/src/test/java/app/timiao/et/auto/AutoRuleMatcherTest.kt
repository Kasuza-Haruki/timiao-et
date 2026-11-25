package app.timiao.et.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRuleMatcherTest {

    private val global = AutoRule(networkId = null)
    private val wifi = NetworkSnapshot(type = NetType.Wifi, ssid = "Home", locationGranted = true)
    private val otherWifi = NetworkSnapshot(type = NetType.Wifi, ssid = "Cafe", locationGranted = true)
    private val hiddenWifi = NetworkSnapshot(type = NetType.Wifi, ssid = null, locationGranted = false)
    private val cellular = NetworkSnapshot(type = NetType.Cellular)
    private val offline = NetworkSnapshot(type = NetType.None)

    @Test
    fun alwaysConnectsOnAnyNetwork() {
        assertEquals(RuleAction.Connect, global.actionFor(wifi))
        assertEquals(RuleAction.Connect, global.actionFor(cellular))
        assertEquals(RuleAction.Skip, global.actionFor(offline))
    }

    @Test
    fun cellularOnlyConnectsOnCellularAndDisconnectsOnWifi() {
        val rule = global.copy(condition = AutoCondition.CellularOnly)
        assertEquals(RuleAction.Connect, rule.actionFor(cellular))
        assertEquals(RuleAction.Disconnect, rule.actionFor(wifi))
        assertEquals(RuleAction.Disconnect, rule.actionFor(NetworkSnapshot(type = NetType.Other)))
        assertEquals(RuleAction.Skip, rule.actionFor(offline))
    }

    @Test
    fun wifiOnlyUsesNormalizedSsidList() {
        val rule = global.copy(
            condition = AutoCondition.WifiOnly,
            wifiNames = listOf("\"home\"", "Office"),
        )
        assertEquals(RuleAction.Connect, rule.actionFor(wifi))
        assertEquals(RuleAction.Connect, rule.actionFor(wifi.copy(ssid = "OFFICE")))
        assertEquals(RuleAction.Disconnect, rule.actionFor(otherWifi))
        assertEquals(RuleAction.Disconnect, rule.actionFor(cellular))
        assertEquals(RuleAction.Skip, rule.actionFor(hiddenWifi))
    }

    @Test
    fun exceptWifiDisconnectsOnlyOnListedNetworks() {
        val rule = global.copy(
            condition = AutoCondition.ExceptWifi,
            wifiNames = listOf("Home"),
        )
        assertEquals(RuleAction.Disconnect, rule.actionFor(wifi))
        assertEquals(RuleAction.Connect, rule.actionFor(otherWifi))
        assertEquals(RuleAction.Connect, rule.actionFor(cellular))
        assertEquals(RuleAction.Skip, rule.actionFor(hiddenWifi))
    }

    @Test
    fun networkRuleWinsOverGlobalRule() {
        val rules = listOf(
            AutoRule(networkId = "home-vpn", condition = AutoCondition.WifiOnly, wifiNames = listOf("Home")),
            global.copy(condition = AutoCondition.Always),
        )
        val decision = evaluate(wifi, rules)
        assertTrue(decision is AutoDecision.Connect)
        assertEquals("home-vpn", (decision as AutoDecision.Connect).networkId)
    }

    @Test
    fun globalConnectBeatsNetworkDisconnect() {
        val rules = listOf(
            AutoRule(networkId = "home-vpn", condition = AutoCondition.WifiOnly, wifiNames = listOf("Home")),
            global.copy(condition = AutoCondition.CellularOnly),
        )
        // "Only on these Wi-Fi" excludes home-vpn on cellular, but the global rule
        // still gets to connect its own target.
        val decision = evaluate(cellular, rules)
        assertTrue(decision is AutoDecision.Connect)
        assertEquals(null, (decision as AutoDecision.Connect).networkId)
    }

    @Test
    fun networkDisconnectAppliesWithoutAConnectRule() {
        val rules = listOf(
            AutoRule(networkId = "home-vpn", condition = AutoCondition.WifiOnly, wifiNames = listOf("Home")),
        )
        val decision = evaluate(otherWifi, rules)
        assertTrue(decision is AutoDecision.Disconnect)
        assertEquals("home-vpn", (decision as AutoDecision.Disconnect).networkId)
    }

    @Test
    fun unreadableSsidFallsThroughToGenericGlobalRule() {
        val rules = listOf(
            AutoRule(networkId = "home-vpn", condition = AutoCondition.WifiOnly, wifiNames = listOf("Home")),
            global.copy(condition = AutoCondition.Always),
        )
        val decision = evaluate(hiddenWifi, rules)
        assertTrue(decision is AutoDecision.Connect)
        assertEquals(null, (decision as AutoDecision.Connect).networkId)
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rules = listOf(
            AutoRule(
                networkId = "home-vpn",
                condition = AutoCondition.Always,
                enabled = false,
            ),
        )
        val decision = evaluate(cellular, rules)
        assertEquals(AutoDecision.Skip(SkipReason.NoRule), decision)
    }

    @Test
    fun disconnectDecisionCarriesKeepNotificationRule() {
        val rules = listOf(
            AutoRule(
                networkId = "home-vpn",
                condition = AutoCondition.CellularOnly,
                keepNotification = true,
            ),
        )
        val decision = evaluate(wifi, rules)
        assertTrue(decision is AutoDecision.Disconnect)
        assertTrue((decision as AutoDecision.Disconnect).rule.keepNotification)
    }

    @Test
    fun parseWifiNamesTrimsQuotesAndDuplicates() {
        assertEquals(
            listOf("Home", "Office"),
            parseWifiNames(" \"Home\" , Office,Home，Office "),
        )
        assertEquals("Home, Office", formatWifiNames(listOf("Home", "Office")))
    }
}
