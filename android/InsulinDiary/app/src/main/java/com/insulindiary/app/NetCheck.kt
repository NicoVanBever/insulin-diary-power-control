package com.insulindiary.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Network detection on the phone: what the phone is connected to, its addresses, and whether one of
 * them is on the same network as one of the PC's addresses from the QR code. Texts are made by the page
 * (so they follow the app's language); this returns facts only.
 */
object NetCheck {

    data class Addr(val ip: String, val prefix: Int, val iface: String, val kind: String)

    /** Kind of connection from the Linux interface name. */
    fun kindOf(iface: String): String {
        val n = iface.lowercase()
        return when {
            n.startsWith("wlan") && n != "wlan1" -> "wifi"
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n == "wlan1" || n.startsWith("wigig") -> "hotspot"
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> "usb"
            n.startsWith("bt-pan") || n.startsWith("bt") -> "bluetooth"
            n.startsWith("eth") -> "ethernet"
            n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("ipsec") || n.startsWith("wg") -> "vpn"
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-rmnet") || n.startsWith("pdp") || n.startsWith("seth") || n.startsWith("clat") -> "mobile"
            else -> "other"
        }
    }

    private fun toInt(ip: String): Long? {
        val p = ip.split("."); if (p.size != 4) return null
        var v = 0L
        for (x in p) { val b = x.toIntOrNull() ?: return null; if (b !in 0..255) return null; v = (v shl 8) or b.toLong() }
        return v
    }

    /** Same network: equal under the smaller of the two network sizes (at least /16, at most /30). */
    fun sameNet(a: String, ap: Int, b: String, bp: Int): Boolean {
        val x = toInt(a) ?: return false; val y = toInt(b) ?: return false
        val p = minOf(ap.takeIf { it in 1..32 } ?: 24, bp.takeIf { it in 1..32 } ?: 24).coerceIn(16, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return (x and mask) == (y and mask)
    }

    fun isPrivate(ip: String): Boolean {
        val v = toInt(ip) ?: return false
        return (v shr 24) == 10L || (v shr 20) == 0xAC1L || (v shr 16) == 0xC0A8L
    }

    /** Facts + verdict: "ok" (a shared network), "mobile" (no Wi-Fi), "different" (Wi-Fi, but another network), "none". */
    fun verdict(phone: List<Addr>, transport: String, vpn: Boolean, pcIps: List<String>, pcPrefixes: List<Int>): JSONObject {
        val o = JSONObject().put("transport", transport).put("vpn", vpn)
        val pa = JSONArray(); phone.forEach { pa.put(JSONObject().put("ip", it.ip).put("prefix", it.prefix).put("iface", it.iface).put("kind", it.kind)) }
        o.put("phone", pa).put("pc", JSONArray(pcIps))
        if (pcIps.isEmpty()) return o
        var match: Pair<Addr, String>? = null
        loop@ for (a in phone) for ((i, ip) in pcIps.withIndex()) {
            if (a.kind != "mobile" && a.kind != "vpn" && sameNet(a.ip, a.prefix, ip, pcPrefixes.getOrNull(i) ?: 24)) { match = a to ip; break@loop }
        }
        val local = phone.filter { it.kind != "mobile" && it.kind != "vpn" && isPrivate(it.ip) }
        o.put("verdict", when {
            match != null -> "ok"
            local.isEmpty() && phone.any { it.kind == "mobile" } -> "mobile"
            local.isEmpty() -> "none"
            else -> "different"
        })
        match?.let { o.put("phoneIp", it.first.ip).put("pcIp", it.second).put("via", it.first.kind) }
        return o
    }

    fun phoneAddrs(): List<Addr> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni ->
                ni.interfaceAddresses.filter { it.address is Inet4Address }
                    .map { Addr(it.address.hostAddress ?: "", it.networkPrefixLength.toInt(), ni.name, kindOf(ni.name)) }
            }
    } catch (e: Exception) { emptyList() }

    fun report(ctx: Context, pcIps: List<String>, pcPrefixes: List<Int>): JSONObject {
        var transport = "none"; var vpn = false
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    vpn -> "vpn"
                    else -> "other"
                }
            }
        } catch (_: Exception) { }
        return verdict(phoneAddrs(), transport, vpn, pcIps, pcPrefixes)
    }
}
