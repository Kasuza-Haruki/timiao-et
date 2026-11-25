package app.timiao.et.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun mapsEveryNatTypeToChinese() {
        val expected = mapOf(
            "Unknown" to "未知",
            "OpenInternet" to "开放互联网",
            "NoPAT" to "无 PAT",
            "FullCone" to "全锥形",
            "Restricted" to "受限锥形",
            "PortRestricted" to "端口受限锥形",
            "Symmetric" to "对称 NAT",
            "SymUdpFirewall" to "对称 UDP 防火墙",
            "SymmetricEasyInc" to "对称 NAT · 易增",
            "SymmetricEasyDec" to "对称 NAT · 易减",
        )
        for ((raw, label) in expected) {
            assertEquals(raw, label, natLabel(raw))
            assertEquals("$raw (lowercase)", label, natLabel(raw.lowercase()))
        }
        assertEquals("—", natLabel(null))
        assertEquals("—", natLabel(""))
    }

    @Test
    fun formatsRatesAndBytesInBinaryUnits() {
        assertEquals("0 B/s", formatRate(0))
        assertEquals("1.0 KiB/s", formatRate(1024))
        assertEquals("1.5 MiB/s", formatRate(1024L * 1024L + 512L * 1024L))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("2.5 MiB", formatBytes((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun labelsKnownEventTypes() {
        assertEquals("对端连接添加", eventTypeLabel("PeerConnAdded"))
        assertEquals("连接错误", eventTypeLabel("ConnectError"))
        assertEquals("自定义事件", eventTypeLabel("自定义事件"))
    }
}
