package com.sada.tv.adb

import android.content.Context
import android.util.Base64
import com.sada.tv.util.SLog
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Minimal client for the receiver's own adbd over TCP (127.0.0.1), so Sada can
 * run shell commands as the shell user without a computer or a helper app.
 *
 * Needs network ADB enabled on the box (Developer options ▸ network/ADB
 * debugging, or `adb tcpip 5555` once from a computer). The first connection
 * shows "Allow debugging?" on the TV: choose "Always allow".
 *
 * Wireless debugging with pairing (TLS) is not supported; adbd then answers STLS.
 */
class AdbClient(context: Context) {

    private val keyDir = File(context.filesDir, "adb").apply { mkdirs() }
    private val keys: KeyPair by lazy { loadOrCreateKeys() }

    /** Opens an authenticated connection. Blocks up to [authWaitMs] for the on-screen approval. */
    fun connect(host: String, port: Int, authWaitMs: Int = 60_000): Connection {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 3000)
        s.tcpNoDelay = true
        s.soTimeout = 8000
        val c = Connection(s)
        try {
            c.send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var sentSignature = false
            var sentKey = false
            while (true) {
                val m = c.read()
                when (m.command) {
                    CNXN -> { s.soTimeout = 0; return c }
                    STLS -> throw IOException("الريسيفر يطلب تصحيحًا لاسلكيًا مشفّرًا (TLS)؛ فعّل ADB الشبكي العادي (منفذ 5555)")
                    AUTH -> {
                        if (m.arg0 != AUTH_TOKEN) throw IOException("bad adb auth")
                        if (!sentSignature) {
                            c.send(AUTH, AUTH_SIGNATURE, 0, sign(m.data))
                            sentSignature = true
                        } else if (!sentKey) {
                            c.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes())
                            sentKey = true
                            SLog.i("adb: waiting for on-screen approval")
                            s.soTimeout = authWaitMs
                        } else {
                            throw IOException("رُفض مفتاح ADB")
                        }
                    }
                    else -> { /* ignore */ }
                }
            }
        } catch (e: SocketTimeoutException) {
            c.close()
            throw IOException("لم تتم الموافقة على الاتصال (اختر «السماح دائمًا» على الشاشة)")
        } catch (e: Exception) {
            c.close()
            throw e
        }
    }

    // ------------------------------------------------------------------ connection

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    class Connection internal constructor(private val socket: Socket) : Closeable {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = socket.getOutputStream()
        private var nextId = 1

        internal fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
            val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xff)
            h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
            synchronized(output) {
                output.write(h.array())
                if (data.isNotEmpty()) output.write(data)
                output.flush()
            }
        }

        internal fun read(): Message {
            val h = ByteArray(24)
            input.readFully(h)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
            if (len < 0 || len > 4 * 1024 * 1024) throw IOException("bad adb length $len")
            val data = ByteArray(len)
            input.readFully(data)
            return Message(cmd, a0, a1, data)
        }

        /** Runs [command] with `sh` as the shell user and returns its output. */
        @Synchronized
        fun shell(command: String, timeoutMs: Int = 15_000): String {
            val local = nextId++
            socket.soTimeout = timeoutMs
            send(OPEN, local, 0, "shell:$command\u0000".toByteArray())
            val out = ByteArrayOutputStream()
            var remote = 0
            try {
                while (true) {
                    val m = read()
                    if (m.arg1 != local && m.command != CNXN) continue
                    when (m.command) {
                        OKAY -> remote = m.arg0
                        WRTE -> { out.write(m.data); send(OKAY, local, m.arg0) }
                        CLSE -> { send(CLSE, local, m.arg0); break }
                    }
                }
            } finally {
                socket.soTimeout = 0
            }
            if (remote == 0 && out.size() == 0) throw IOException("adb shell refused")
            return out.toString(Charsets.UTF_8.name())
        }

        override fun close() { runCatching { socket.close() } }
    }

    // ------------------------------------------------------------------ keys

    private fun loadOrCreateKeys(): KeyPair {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub.der")
        if (priv.exists() && pub.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
                )
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    /** adb signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    private fun sign(token: ByteArray): ByteArray {
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(keys.private)
        s.update(SHA1_DIGEST_INFO)
        s.update(token)
        return s.sign()
    }

    /** Public key in adb's format: base64(RSAPublicKey struct) + " name" + NUL. */
    private fun publicKeyBytes(): ByteArray {
        val k = keys.public as RSAPublicKey
        val words = 2048 / 32
        val n = k.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        b.putInt(n0inv)
        b.put(littleEndian(n, 256))
        b.put(littleEndian(rr, 256))
        b.putInt(k.publicExponent.toInt())
        val enc = Base64.encodeToString(b.array(), Base64.NO_WRAP)
        return "$enc sada@tv\u0000".toByteArray()
    }

    private fun littleEndian(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray() // big-endian, may carry a leading sign byte
        val out = ByteArray(size)
        for (i in 0 until size) {
            val j = be.size - 1 - i
            out[i] = if (j >= 0) be[j] else 0
        }
        return out
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
