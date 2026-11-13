package com.insulindiary.app

import org.json.JSONObject
import java.io.File
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

    data class Code(val ips: List<String>, val port: Int, val id: String, val key: ByteArray, val diaryId: String)

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
        for (ip in code.ips) {
            var conn: HttpURLConnection? = null
            try {
                val base = "http://$ip:${code.port}"
                conn = URL("$base/s/${code.id}").openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
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
        return err("Couldn't reach the PC. Check that the phone and the PC are on the same Wi-Fi, and that Windows allowed Insulin Diary on private networks.")
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
