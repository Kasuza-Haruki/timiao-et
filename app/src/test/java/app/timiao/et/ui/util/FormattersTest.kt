package app.timiao.et.ui.util

import app.timiao.et.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {

    @Test
    fun mapsEveryNatTypeToAResource() {
        val expected = mapOf(
            "Unknown" to R.string.nat_unknown,
            "OpenInternet" to R.string.nat_open,
            "NoPAT" to R.string.nat_nopat,
            "FullCone" to R.string.nat_fullcone,
            "Restricted" to R.string.nat_restricted,
            "PortRestricted" to R.string.nat_portrestricted,
            "Symmetric" to R.string.nat_symmetric,
            "SymUdpFirewall" to R.string.nat_symudpfirewall,
            "SymmetricEasyInc" to R.string.nat_syminc,
            "SymmetricEasyDec" to R.string.nat_symdec,
        )
        for ((raw, label) in expected) {
            assertEquals(raw, label, natLabelRes(raw))
            assertEquals("$raw (lowercase)", label, natLabelRes(raw.lowercase()))
        }
        assertNull(natLabelRes(null))
        assertNull(natLabelRes(""))
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
        assertEquals(R.string.event_peer_conn_added, eventTypeLabelRes("PeerConnAdded"))
        assertEquals(R.string.event_connect_error, eventTypeLabelRes("ConnectError"))
        assertNull(eventTypeLabelRes("custom event"))
    }
}
