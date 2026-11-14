import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.net.*
import com.rhshourav.tenchar.crypto.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

fun show(step: String, r: Reply<*>) = println("  %-34s %s".format(step, when (r) { is Reply.Ok -> "OK"; is Reply.Rejected -> "REJECTED status=${r.status} error='${r.error}'"; Reply.Unreachable -> "UNREACHABLE" }))

fun main(args: Array<String>) = runBlocking {
    val trust = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    })
    val ctx = SSLContext.getInstance("TLS").apply { init(null, trust, SecureRandom()) }
    HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
    HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

    val client = RelayClient(args[0])
    val rnd = SystemRandom
    val room = Hex.encode(ByteArray(16).also(rnd::nextBytes))
    val code = Hex.encode(ByteArray(24).also(rnd::nextBytes))
    val a = LocalMember.create(rnd); val b = LocalMember.create(rnd)
    println("relay: ${args[0]}")
    println("  %-34s %s".format("wake-up ping (health page)", if (client.ping()) "OK" else "NO ANSWER"))
    show("hello (A)", client.hello(room, code, a.memberId, a.tokenHash, a.commit))
    show("hello (B)", client.hello(room, code, b.memberId, b.tokenHash, b.commit))
    show("reveal (A)", client.reveal(room, a.memberToken, a.memberId, Hex.encode(a.keys.ik.pub), Hex.encode(a.keys.ek.pub)))
    show("reveal (B)", client.reveal(room, b.memberToken, b.memberId, Hex.encode(b.keys.ik.pub), Hex.encode(b.keys.ek.pub)))
    val text = client.post(room, a.memberToken, "A".repeat(180)); show("text message (A -> B)", text)

    // an attachment exactly as the app makes one: own key, encrypted file, id derived from the key
    var x = 5
    val plain = ByteArray(183_456) { x = (x * 1103515245 + 12345) and 0x7fffffff; (x ushr 16).toByte() }
    val key = BlobCrypto.newKey(rnd); val id = BlobCrypto.blobId(key)
    val sealed = File.createTempFile("blob", ".tcm")
    sealed.outputStream().use { BlobCrypto.encrypt(key, 1, plain, it) }
    show("upload photo (A)", client.putBlob(room, a.memberToken, id, sealed))
    val dest = File.createTempFile("down", ".part")
    val expected = BlobCrypto.cipherSize(plain.size).toLong()
    val got = client.getBlob(room, b.memberToken, id, expected, dest); show("download photo (B)", got)
    if (got is Reply.Ok) {
        val back = dest.inputStream().use { BlobCrypto.decrypt(key, 1, plain.size, it) }
        println("  %-34s %s".format("decrypts to the original", if (back != null && back.contentEquals(plain)) "YES" else "NO"))
    }
    show("delete photo (B)", client.deleteBlob(room, b.memberToken, id))
    val token = Hex.encode(ByteArray(32).also(rnd::nextBytes))
    show("register watch token (B)", client.registerWatch(room, b.memberToken, Hex.encode(Kdf.sha256(Hex.decode(token)))))
    val peek = client.peek(room, token); show("peek with watch token (B)", peek)
    if (peek is Reply.Ok) println("  %-34s waiting=%d latest=%d".format("  -> relay says", peek.value.waiting, peek.value.latest))
}
