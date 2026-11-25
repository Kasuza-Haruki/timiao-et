package app.timiao.et.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {
    @Test
    fun `parses instance map, nat type and events`() {
        val raw = """
            {"map":{"net":{"my_node_info":{"stun_info":{"udp_nat_type":"Symmetric"}},
            "events":["{\"time\":\"2026-11-15T12:00:01.123+08:00\",\"event\":{\"PeerConnAdded\":{\"peer_id\":1}}}"]}}}
        """.trimIndent()
        val snapshot = EventParser.parse(raw, "net")
        assertEquals("Symmetric", snapshot!!.myNatType)
        assertEquals(1, snapshot.events.size)
        assertEquals("PeerConnAdded", snapshot.events.first().type)
        assertEquals("已建立节点连接", snapshot.events.first().text)
        assertEquals("12:00:01", snapshot.events.first().time)
    }

    @Test
    fun `parses numeric nat type`() {
        val raw = """{"map":{"net":{"my_node_info":{"stun_info":{"udp_nat_type":6}},"events":[]}}}"""
        assertEquals("Symmetric", EventParser.parse(raw, "net")!!.myNatType)
    }

    @Test
    fun `missing instance falls back to the first entry`() {
        val raw = """{"net":{"my_node_info":{"stun_info":{"udp_nat_type":"FullCone"}},"events":[]}}"""
        assertEquals("FullCone", EventParser.parse(raw, "other")!!.myNatType)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(EventParser.parse("not json", "net"))
        assertNull(EventParser.parse(null, "net"))
        assertNull(EventParser.parse("", "net"))
    }

    @Test
    fun `translates common tunnel errors`() {
        assertTrue(EventParser.translateError("Connection refused").contains("端口"))
        assertTrue(EventParser.translateError("i/o timeout").contains("超时"))
        assertTrue(EventParser.translateError("dns error: failed to resolve host").contains("域名"))
        assertTrue(EventParser.translateError("handshake failed").contains("网络名"))
        assertEquals("custom failure", EventParser.translateError("custom failure"))
    }

    @Test
    fun `parses string unit event`() {
        val raw = """{"map":{"net":{"events":["{\"event\":\"CredentialChanged\"}"]}}}"""
        val event = EventParser.parse(raw, "net")!!.events.first()
        assertEquals("CredentialChanged", event.type)
        assertEquals("网络凭据已更新", event.text)
    }
}
