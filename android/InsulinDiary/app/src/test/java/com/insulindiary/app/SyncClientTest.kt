package com.insulindiary.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SyncClientTest {

    // ---------- offline checks ----------

    @Test fun parsesCode() {
        val key = ByteArray(32) { it.toByte() }
        val k = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val c = SyncClient.parse("http://192.168.1.5:47622/p/abc123#k=$k&d=ABCDEF0123456789&i=10.0.0.2,172.20.1.1")!!
        assertEquals(listOf("192.168.1.5", "10.0.0.2", "172.20.1.1"), c.ips)
        assertEquals("abc123", c.id); assertEquals(47622, c.port); assertArrayEquals(key, c.key)
        assertNull(SyncClient.parse("https://example.com/p/x#k=$k"))
        assertNull(SyncClient.parse("http://1.2.3.4:47622/p/x#k=short"))
        assertFalse(JSONObject(SyncClient.sync("hello", "{}", "Phone", 1, null)).getBoolean("ok"))
    }

    @Test fun encryptionRoundTripAndTamper() {
        val key = ByteArray(32) { 7 }
        val box = SyncClient.seal(key, "req|x", "secret diary".toByteArray())
        assertEquals("secret diary", String(SyncClient.open(key, "req|x", box)))
        assertFalse(String(box).contains("secret"))
        val bad = box.copyOf(); bad[20] = (bad[20].toInt() xor 1).toByte()
        assertTrue(runCatching { SyncClient.open(key, "req|x", bad) }.isFailure)
        assertTrue(runCatching { SyncClient.open(key, "res|x", box) }.isFailure) // other direction/code
        assertNotEquals(SyncClient.seal(key, "a", "x".toByteArray()).toList(), SyncClient.seal(key, "a", "x".toByteArray()).toList())
    }

    // ---------- against a real Insulin Diary PC program (test build on 127.0.0.1) ----------

    private fun local(path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:47621$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /** Plays the PC window: waits for the phone's data and answers with a merged diary. */
    private fun pcWindow(seen: MutableList<JSONObject>) = thread {
        repeat(300) {
            val (code, text) = local("/api/sync/poll")
            if (code == 200) {
                val p = JSONObject(text); seen.add(p)
                val phoneEntries = p.getJSONObject("state").getJSONArray("entries")
                val merged = JSONObject().put("entries", JSONArray().put(phoneEntries.get(0))
                    .put(JSONObject().put("id", "pc1").put("type", "glucose").put("ts", 2).put("value", 120)))
                local("/api/sync/reply", "POST", JSONObject().put("id", p.getString("id")).put("state", merged)
                    .put("stats", JSONObject().put("received", 1).put("sent", 1)).toString())
                return@thread
            }
            Thread.sleep(100)
        }
    }

    private val phone = JSONObject().put("entries", JSONArray().put(JSONObject().put("id", "ph1").put("type", "glucose").put("ts", 1).put("value", 99))).toString()

    @Test fun fullEncryptedSyncWithUpdateOffer() {
        assumeTrue(System.getenv("SYNC_SERVER") == "1")
        val open = JSONObject(local("/api/sync/open", "POST").second)
        val url = open.getString("url")
        val diaryId = open.getString("diaryId")
        assertTrue(url, url.startsWith("http://127.0.0.1:47622/p/") && url.contains("#k="))

        // the code's key is not needed to fetch the install page, but nothing in the URL path reveals it
        val key = SyncClient.parse(url)!!.key
        assertFalse(url.substringBefore("#").contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)))

        val seen = mutableListOf<JSONObject>()
        val pc = pcWindow(seen)
        val progress = mutableListOf<String>()
        val r = JSONObject(SyncClient.sync(url.replace("http://127.0.0.1:", "http://10.255.255.1:").replace("#k=", "#k=").let {
            // first address unreachable on purpose; the real one is listed as an alternative
            it + "&i=127.0.0.1"
        }, phone, "Test Phone", 1, null) { progress.add(it) })
        pc.join()
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("Test Phone", seen[0].getString("device"))
        assertEquals(1, seen[0].getInt("appVersion"))
        assertEquals(2, r.getJSONObject("state").getJSONArray("entries").length())
        assertEquals(diaryId, r.getString("diaryId"))
        assertTrue(progress.contains("Syncing with your PC…"))

        // older phone app -> the PC offers its newer one; the download must match the announced SHA-256
        val upd = r.getJSONObject("update")
        assertTrue(upd.getInt("versionCode") > 1)
        val f = File.createTempFile("upd", ".apk")
        assertTrue(SyncClient.download(r.getString("base"), upd.getString("path"), upd.getString("sha256"), f))
        assertEquals(upd.getInt("size").toLong(), f.length())
        assertFalse("wrong hash is rejected", SyncClient.download(r.getString("base"), upd.getString("path"), "00".repeat(32), f))

        // the same code can't be used twice
        val again = JSONObject(SyncClient.sync(url, phone, "Test Phone", 1, null))
        assertFalse(again.getBoolean("ok"))
        assertTrue(again.getString("error").contains("expired"))
    }

    @Test fun upToDatePhoneGetsNoUpdateAndOtherDiaryNeedsConfirm() {
        assumeTrue(System.getenv("SYNC_SERVER") == "1")
        val url = JSONObject(local("/api/sync/open", "POST").second).getString("url")
        val code = SyncClient.parse(url)!!

        // a phone linked to a different diary must ask first
        val warn = JSONObject(SyncClient.sync(url, phone, "Test Phone", 99, "0000-0000-0000-0000"))
        assertTrue(warn.getBoolean("needConfirm"))
        assertEquals(code.diaryId, warn.getString("diaryId"))

        // a forged code with the right id but a wrong key is refused (and doesn't burn the code)
        val forged = url.substringBefore("#k=") + "#k=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)) + "&d=" + code.diaryId
        val f = JSONObject(SyncClient.sync(forged, phone, "Evil", 99, null))
        assertFalse(f.getBoolean("ok"))
        assertTrue(f.getString("error").contains("unlocked"))

        val pc = pcWindow(mutableListOf())
        val r = JSONObject(SyncClient.sync(url, phone, "Test Phone", 99, code.diaryId))
        pc.join()
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertFalse(r.has("update"))
    }
}
