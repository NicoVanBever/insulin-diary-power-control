package com.insulindiary.app

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sync with the Insulin Diary PC program from a scanned QR code:
 *   http://<pc-ip>:47622/p/<code id>#k=<256-bit key>&d=<diary id>&i=<other ips>
 * The key in the code never goes over the network; everything sent is AES-256-GCM encrypted with it.
 * Returns the PC's answer as JSON ({"ok":true,"host","diaryId","stats","state","update"?} or {"ok":false,"error"}),
 * plus "base" (the address that answered). Blocking: call it off the main thread.
 */
object SyncClient {

    /** prefixes: network size of each PC address (same order as ips); reverse: the PC can connect back to the phone. */
    data class Code(val ips: List<String>, val port: Int, val id: String, val key: ByteArray, val diaryId: String,
                    val prefixes: List<Int> = emptyList(), val reverse: Boolean = false)

    const val REVERSE_PORT = 47623

    fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    fun normId(s: String) = s.replace("-", "").uppercase()

    fun parse(raw: String): Code? = try {
        val u = URI(raw.trim())
        val parts = u.rawPath.trim('/').split("/")
        val frag = (u.rawFragment ?: "").split("&").mapNotNull {
            val kv = it.split("=", limit = 2); if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val key = Base64.getUrlDecoder().decode(frag["k"] ?: "")
        if (u.scheme != "http" || parts.size != 2 || parts[0] != "p" || key.size != 32 || u.host.isNullOrEmpty()) null
        else Code(
            ips = listOf(u.host) + (frag["i"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            port = if (u.port > 0) u.port else 47622, id = parts[1], key = key, diaryId = normId(frag["d"] ?: ""),
            prefixes = (frag["m"] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() },
            reverse = frag["r"] == "1",
        )
    } catch (e: Exception) { null }

    private val rnd = SecureRandom()

    fun seal(key: ByteArray, aad: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, aad: String, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.doFinal(data, 12, data.size - 12)
    }

    /**
     * @param pairedId diary ID this phone synced with before (null = accept any); a different one returns needConfirm.
     */
    fun sync(raw: String, state: String, device: String, appVersion: Int, pairedId: String?, progress: (String) -> Unit = {}): String {
        val code = parse(raw)
            ?: return err("That isn't an Insulin Diary sync code. Open \"Sync with phone\" in the PC program and scan the code shown there.")
        if (pairedId != null && pairedId.isNotEmpty() && code.diaryId != normId(pairedId)) {
            return JSONObject().put("ok", false).put("needConfirm", true)
                .put("diaryId", code.diaryId).put("pairedId", normId(pairedId)).toString()
        }
        progress("Connecting to your PC…")
        val body = seal(code.key, "req|" + code.id, state.toByteArray(Charsets.UTF_8))
        for (ip in code.ips.take(connectTries)) {
            var conn: HttpURLConnection? = null
            try {
                val base = "http://$ip:${code.port}"
                conn = URL("$base/s/${code.id}").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 75000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Device", device.filter { it.code in 32..126 }.take(60))
                conn.setRequestProperty("X-App-Version", appVersion.toString())
                conn.connect() // fails fast if this address isn't reachable; then the next one is tried
                progress("Syncing with your PC…")
                conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val text = if (status == 200 && conn.contentType?.startsWith("application/octet-stream") == true) {
                    try { String(open(code.key, "res|" + code.id, bytes), Charsets.UTF_8) }
                    catch (e: Exception) { return err("The PC's answer couldn't be verified. Make sure you scanned the code on your own PC.") }
                } else String(bytes, Charsets.UTF_8)
                if (text.isBlank()) return err("The PC answered with error $status.")
                return try { JSONObject(text).put("base", base).toString() } catch (e: Exception) { err("The PC sent an unreadable answer.") }
            } catch (_: Exception) {
                // try the next address
            } finally {
                conn?.disconnect()
            }
        }
        // No address answered. A newer PC can connect to the phone instead (incoming connections are what
        // the Windows firewall blocks; its outgoing ones are allowed).
        if (code.reverse) return reverseWait(code, body, device, appVersion, progress = progress)
        return unreachable()
    }

    /** Test hook: how many PC addresses to try (all of them normally). */
    var connectTries = Int.MAX_VALUE

    fun unreachable(): String = JSONObject().put("ok", false).put("unreachable", true)
        .put("error", "Couldn't reach the PC.").toString()

    // ---------------------------------------------------------------- reverse connection

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readRequest(input: InputStream): Req? {
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar()); if (sb.length > 8192) return null
            }
        }
        val first = line() ?: return null
        val p = first.split(" "); if (p.size < 2) return null
        val h = mutableMapOf<String, String>()
        while (true) {
            val l = line() ?: return null
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            if (h.size > 50) return null
        }
        val len = h["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > 256 * 1024 * 1024) return null
        val body = ByteArray(len); var got = 0
        while (got < len) { val n = input.read(body, got, len - got); if (n < 0) return null; got += n }
        return Req(p[0], p[1], h, body)
    }

    private fun respond(out: OutputStream, status: Int, body: ByteArray, type: String, extra: Map<String, String> = emptyMap()) {
        val head = StringBuilder("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            .append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush()
    }

    /**
     * Waits (on port 47623) for the PC to connect to this phone. Hands over the encrypted diary only for
     * the code that was scanned, then waits for the PC's encrypted answer from that same address.
     */
    fun reverseWait(code: Code, body: ByteArray, device: String, appVersion: Int, waitMs: Long = 60_000, progress: (String) -> Unit = {}): String {
        val server = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(REVERSE_PORT)); soTimeout = 1000 }
        } catch (e: Exception) { return unreachable() }
        progress("The PC can't be reached directly. Waiting for the PC to connect to this phone… keep the sync screen open on the PC.")
        var deadline = System.currentTimeMillis() + waitMs
        var pcAddr: InetAddress? = null
        try {
            while (System.currentTimeMillis() < deadline) {
                val sock = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                sock.use { s ->
                    s.soTimeout = 75_000
                    val req = try { readRequest(s.getInputStream()) } catch (e: Exception) { null } ?: return@use
                    val out = s.getOutputStream()
                    if (req.path != "/r/${code.id}" || (pcAddr != null && s.inetAddress != pcAddr)) {
                        respond(out, 404, ByteArray(0), "text/plain"); return@use
                    }
                    if (req.method == "GET") {
                        respond(out, 200, body, "application/octet-stream", mapOf(
                            "X-Insulin-Diary" to "1",
                            "X-Device" to device.filter { it.code in 32..126 }.take(60),
                            "X-App-Version" to appVersion.toString()))
                        pcAddr = s.inetAddress
                        deadline = System.currentTimeMillis() + 80_000
                        progress("Your PC found this phone. Syncing…")
                    } else if (req.method == "POST" && pcAddr != null) {
                        respond(out, 200, "ok".toByteArray(), "text/plain")
                        val type = req.headers["content-type"] ?: ""
                        val text = if (type.startsWith("application/octet-stream")) {
                            try { String(open(code.key, "res|" + code.id, req.body), Charsets.UTF_8) }
                            catch (e: Exception) { return err("The PC's answer couldn't be verified. Make sure you scanned the code on your own PC.") }
                        } else String(req.body, Charsets.UTF_8)
                        if (text.isBlank()) return err("The PC answered with error ${req.headers["x-status"] ?: "?"}.")
                        return try {
                            // App updates are downloaded from the PC, which can't be reached directly here: skip the offer.
                            JSONObject(text).put("base", "http://${pcAddr!!.hostAddress}:${code.port}").put("reverse", true)
                                .also { it.remove("update") }.toString()
                        } catch (e: Exception) { err("The PC sent an unreadable answer.") }
                    } else respond(out, 404, ByteArray(0), "text/plain")
                }
            }
        } finally { try { server.close() } catch (_: Exception) { } }
        return if (pcAddr != null) err("The PC stopped answering. Try again.") else unreachable()
    }

    /** Downloads an update offered by the PC and checks it against the SHA-256 from the encrypted answer. */
    fun download(base: String, path: String, sha256: String, dest: File, progress: (Int) -> Unit = {}): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(base + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            if (conn.responseCode != 200) return false
            val total = conn.contentLength
            val md = MessageDigest.getInstance("SHA-256")
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256, ignoreCase = true)) { dest.delete(); return false }
            return true
        } catch (e: Exception) {
            dest.delete(); return false
        } finally {
            conn?.disconnect()
        }
    }
}
