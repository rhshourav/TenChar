package com.rhshourav.tenchar.net

import com.rhshourav.tenchar.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** What a relay call came back with. Anything the relay says is untrusted input. */
sealed interface Reply<out T> {
    class Ok<T>(val value: T) : Reply<T>

    /** The relay answered with an error status. */
    class Rejected(val status: Int, val error: String) : Reply<Nothing>

    /** No usable answer: network failure, timeout, or a response that did not make sense. */
    data object Unreachable : Reply<Nothing>
}

class RoomMember(val id: String, val commit: String, val ik: String?, val ek: String?)
class RoomState(val sealed: Boolean, val members: List<RoomMember>)
class RelayMessage(val id: Long, val env: String)
class RelayPage(val messages: List<RelayMessage>, val more: Boolean)

/**
 * Client for one relay (see relay/src/core.js for the protocol). HTTPS only,
 * no redirects, small responses only, no logging. Every call suspends on the IO dispatcher.
 */
class RelayClient(base: String) {
    private val origin: String = base.trimEnd('/').also {
        require(it.startsWith("https://")) { "relay must be https" }
    }

    suspend fun hello(room: String, codeToken: String, memberId: String, tokenHash: String, commit: String): Reply<Int> {
        val body = JSONObject().put("memberId", memberId).put("memberTokenHash", tokenHash).put("commit", commit)
        return call("POST", room, "/hello", codeToken, body.toString()) { it.optInt("members", -1).takeIf { n -> n in 1..2 } }
    }

    suspend fun state(room: String, token: String): Reply<RoomState> =
        call("GET", room, "/state", token, null) { json ->
            val arr = json.optJSONArray("members") ?: return@call null
            if (arr.length() > 2) return@call null
            val members = ArrayList<RoomMember>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: return@call null
                val id = m.optString("memberId")
                val commit = m.optString("commit")
                val ik = if (m.isNull("ik")) null else m.optString("ik")
                val ek = if (m.isNull("ek")) null else m.optString("ek")
                if (!isHex(id, 32) || !isHex(commit, 64)) return@call null
                if ((ik != null && !isHex(ik, 64)) || (ek != null && !isHex(ek, 64))) return@call null
                members.add(RoomMember(id, commit, ik, ek))
            }
            RoomState(json.optBoolean("sealed", false), members)
        }

    suspend fun reveal(room: String, token: String, memberId: String, ik: String, ek: String): Reply<Boolean> {
        val body = JSONObject().put("memberId", memberId).put("ik", ik).put("ek", ek)
        return call("POST", room, "/reveal", token, body.toString()) { it.optBoolean("sealed", false) }
    }

    suspend fun post(room: String, token: String, envelope: String): Reply<Unit> =
        call("POST", room, "/messages", token, JSONObject().put("env", envelope).toString()) { }

    suspend fun fetch(room: String, token: String, after: Long): Reply<RelayPage> =
        call("GET", room, "/messages?after=$after", token, null) { json ->
            val arr = json.optJSONArray("messages") ?: return@call null
            if (arr.length() > 128) return@call null
            val list = ArrayList<RelayMessage>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: return@call null
                val id = m.optLong("id", -1)
                val env = m.optString("env")
                if (id < 0 || env.length != Constants.ENVELOPE_B64_CHARS) return@call null
                list.add(RelayMessage(id, env))
            }
            RelayPage(list, json.optBoolean("more", false))
        }

    suspend fun ack(room: String, token: String, upTo: Long): Reply<Unit> =
        call("POST", room, "/ack", token, JSONObject().put("upTo", upTo).toString()) { }

    suspend fun delete(room: String, token: String): Reply<Unit> = call("DELETE", room, "", token, null) { }

    private suspend fun <T : Any> call(
        method: String,
        room: String,
        path: String,
        token: String,
        body: String?,
        parse: (JSONObject) -> T?,
    ): Reply<T> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$origin/v1/rooms/$room$path").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            // Free hosting plans can take most of a minute to wake up.
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", Constants.USER_AGENT)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { readCapped(it) } ?: ""
            when {
                status in 200..299 -> {
                    if (status == 204 || text.isEmpty()) {
                        parse(JSONObject())?.let { Reply.Ok(it) } ?: Reply.Unreachable
                    } else {
                        parse(JSONObject(text))?.let { Reply.Ok(it) } ?: Reply.Unreachable
                    }
                }
                else -> {
                    val error = try {
                        JSONObject(text).optString("error", "")
                    } catch (e: JSONException) {
                        ""
                    }
                    Reply.Rejected(status, error.take(40))
                }
            }
        } catch (e: IOException) {
            Reply.Unreachable
        } catch (e: JSONException) {
            Reply.Unreachable
        } catch (e: RuntimeException) {
            Reply.Unreachable
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_RESPONSE_BYTES) throw IOException("response too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun isHex(s: String, length: Int): Boolean = s.length == length && s.all { it in '0'..'9' || it in 'a'..'f' }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 45_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
