package com.insulindiary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetCheckTest {
    private fun a(ip: String, p: Int, iface: String) = NetCheck.Addr(ip, p, iface, NetCheck.kindOf(iface))

    @Test fun kinds() {
        assertEquals("wifi", NetCheck.kindOf("wlan0"))
        assertEquals("hotspot", NetCheck.kindOf("ap0"))
        assertEquals("hotspot", NetCheck.kindOf("swlan0"))
        assertEquals("usb", NetCheck.kindOf("rndis0"))
        assertEquals("mobile", NetCheck.kindOf("rmnet_data0"))
        assertEquals("vpn", NetCheck.kindOf("tun0"))
    }

    @Test fun sameNetwork() {
        assertTrue(NetCheck.sameNet("192.168.1.23", 24, "192.168.1.10", 24))
        assertFalse(NetCheck.sameNet("192.168.1.23", 24, "192.168.0.10", 24))
        assertTrue(NetCheck.sameNet("10.0.5.2", 16, "10.0.9.9", 16))
        assertTrue(NetCheck.sameNet("10.0.5.2", 24, "10.0.9.9", 16)) // the larger network counts
        assertFalse(NetCheck.sameNet("10.1.5.2", 24, "10.0.9.9", 16))
        assertTrue(NetCheck.isPrivate("172.20.10.2")); assertFalse(NetCheck.isPrivate("100.70.1.2"))
    }

    @Test fun verdicts() {
        val pc = listOf("192.168.1.10", "192.168.137.1"); val m = listOf(24, 24)
        val ok = NetCheck.verdict(listOf(a("192.168.1.23", 24, "wlan0")), "wifi", false, pc, m)
        assertEquals("ok", ok.getString("verdict")); assertEquals("192.168.1.10", ok.getString("pcIp"))
        // phone hotspot / PC hotspot / USB tethering
        assertEquals("ok", NetCheck.verdict(listOf(a("192.168.137.55", 24, "wlan0")), "wifi", false, pc, m).getString("verdict"))
        assertEquals("ok", NetCheck.verdict(listOf(a("192.168.42.129", 24, "rndis0")), "mobile", false, listOf("192.168.42.17"), listOf(24)).getString("verdict"))
        assertEquals("different", NetCheck.verdict(listOf(a("192.168.0.4", 24, "wlan0")), "wifi", false, pc, m).getString("verdict"))
        assertEquals("mobile", NetCheck.verdict(listOf(a("10.140.2.3", 30, "rmnet_data0")), "mobile", false, pc, m).getString("verdict"))
        assertEquals("none", NetCheck.verdict(emptyList(), "none", false, pc, m).getString("verdict"))
        // a mobile-data address that happens to look local is never a match
        assertEquals("mobile", NetCheck.verdict(listOf(a("192.168.1.99", 24, "rmnet0")), "mobile", false, pc, m).getString("verdict"))
    }
}
