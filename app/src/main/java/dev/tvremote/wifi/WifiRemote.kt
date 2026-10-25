package dev.tvremote.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.tvremote.core.RemoteKey
import dev.tvremote.core.RemoteTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

data class TvDevice(val name: String, val host: String)

sealed class WifiState {
    object Idle : WifiState()
    class Discovering(val devices: List<TvDevice>) : WifiState()
    object Pairing : WifiState()
    object AwaitingPin : WifiState()
    class Connected(val name: String) : WifiState()
    class Error(val message: String) : WifiState()
}

// --- minimal protobuf: only varint (wire 0) and length-delimited (wire 2) ---
private fun varint(v: Int): ByteArray {
    val o = java.io.ByteArrayOutputStream()
    var n = v
    while (n and 0x7F.inv() != 0) { o.write((n and 0x7F) or 0x80); n = n ushr 7 }
    o.write(n)
    return o.toByteArray()
}
private fun vf(f: Int, v: Int) = varint(f shl 3) + varint(v)
private fun lf(f: Int, b: ByteArray) = varint((f shl 3) or 2) + varint(b.size) + b
private fun lf(f: Int, s: String) = lf(f, s.toByteArray())

/** field number -> Long (varint) or ByteArray (length-delimited); first occurrence wins. Throws IOException on malformed input. */
private fun parse(b: ByteArray): Map<Int, Any> {
    val m = HashMap<Int, Any>()
    var i = 0
    fun byte(): Int { if (i >= b.size) throw IOException("truncated message"); return b[i++].toInt() }
    fun rv(): Long {
        var r = 0L; var s = 0
        while (true) {
            val c = byte()
            r = r or ((c and 0x7F).toLong() shl s)
            if (c >= 0) return r
            s += 7
            if (s > 63) throw IOException("bad varint")
        }
    }
    while (i < b.size) {
        val tag = rv().toInt()
        val v: Any = when (tag and 7) {
            0 -> rv()
            2 -> {
                val n = rv()
                if (n < 0 || n > b.size - i) throw IOException("bad length")
                b.copyOfRange(i, i + n.toInt()).also { i += n.toInt() }
            }
            else -> throw IOException("unsupported wire type ${tag and 7}")
        }
        m.putIfAbsent(tag ushr 3, v)
    }
    return m
}

private const val MAX_FRAME = 64 * 1024

private fun InputStream.frame(): ByteArray {
    var n = 0; var s = 0
    while (true) {
        val c = read()
        if (c < 0) throw IOException("closed")
        if (s > 28) throw IOException("bad frame length")
        n = n or ((c and 0x7F) shl s)
        if (c < 0x80) break
        s += 7
    }
    if (n < 0 || n > MAX_FRAME) throw IOException("frame too large: $n")
    val b = ByteArray(n)
    var o = 0
    while (o < n) { val r = read(b, o, n - o); if (r < 0) throw IOException("closed"); o += r }
    return b
}

private fun OutputStream.frame(body: ByteArray) { write(varint(body.size) + body); flush() }

private fun BigInteger.raw() = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

// --- Android TV Remote v2 pairing messages ---
private val HDR = vf(1, 2) + vf(2, 200)                    // protocol_version=2, status=OK
private val ENC = lf(1, vf(1, 3) + vf(2, 6))                // encoding HEXADECIMAL, 6 symbols
private val PAIR_REQ = HDR + lf(10, lf(1, "dev.tvremote") + lf(2, "TV Remote"))
private val PAIR_OPT = HDR + lf(20, ENC + vf(3, 1))         // preferred_role INPUT
private val PAIR_CFG = HDR + lf(30, ENC + vf(2, 1))         // client_role INPUT

// --- remote messages ---
private const val CODE = 622
private val CONFIGURE = lf(1, vf(1, CODE) + lf(2,
    lf(1, "androidtv_remote") + lf(2, "mobilecare") + vf(3, 1) + lf(4, "1") + lf(5, "dev.tvremote") + lf(6, "1")))
private val SET_ACTIVE = lf(2, vf(1, CODE))

private const val ALIAS = "atv_client"
private const val TAG = "TvRemote"
private val TRUST_ALL = object : X509TrustManager {
    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
}
private val LINKS = mapOf(
    RemoteKey.NETFLIX to "https://www.netflix.com/title.*",
    RemoteKey.YOUTUBE to "https://www.youtube.com/title.*",
    RemoteKey.PRIME to "https://app.primevideo.com/",
)

class WifiRemote(context: Context) : RemoteTransport {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("wifi", Context.MODE_PRIVATE)
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val hasWifi = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow<WifiState>(WifiState.Idle)
    val state: StateFlow<WifiState> = _state

    @Volatile private var gen = 0
    @Volatile private var sock: SSLSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var serverKey: RSAPublicKey? = null
    @Volatile private var wantDiscover = false   // sheet is open and wants devices; resumed on return to foreground
    private var listener: NsdManager.DiscoveryListener? = null
    private val mcast = wm.createMulticastLock("tvremote-mdns")
    private fun go(s: WifiState) {
        Log.d(TAG, "state -> ${s.javaClass.simpleName}" + ((s as? WifiState.Error)?.let { ": ${it.message}" } ?: ""))
        _state.value = s
    }

    override val available get() = _state.value is WifiState.Connected && out != null

    init {
        val host = prefs.getString("host", null)
        if (host != null) start(TvDevice(prefs.getString("name", host)!!, host), canPair = false)
    }

    /** Activity.onStop. Rotation keeps everything. A real background stop ends discovery and drops the socket; onStart re-opens it. */
    fun onStop(changingConfig: Boolean) {
        if (changingConfig) return
        stopDiscovery()
        when (_state.value) {
            is WifiState.Discovering -> go(WifiState.Idle)
            is WifiState.Connected -> closeSocket() // state stays Connected; onStart re-opens quietly
            else -> {}
        }
    }

    /** Activity.onStart: quiet reconnect to the last TV if the socket was dropped, or resume a search the sheet still wants. */
    fun onStart() {
        val host = prefs.getString("host", null)
        val s = _state.value
        val dropped = s is WifiState.Connected && out == null
        if (host != null && (dropped || s is WifiState.Error)) {
            start(TvDevice(prefs.getString("name", host)!!, host), canPair = false, quiet = true)
        } else if (wantDiscover && s is WifiState.Idle) discover()
    }

    /** Sheet closed or a device was picked: end the search and release NSD and the multicast lock. */
    fun stopSearch() {
        wantDiscover = false
        stopDiscovery()
        if (_state.value is WifiState.Discovering) go(WifiState.Idle)
    }

    fun discover() {
        val s = _state.value
        if (s is WifiState.Connected || s is WifiState.Pairing || s is WifiState.AwaitingPin) return
        wantDiscover = true
        stopDiscovery()
        if (!hasWifi) { go(WifiState.Error("No Wi-Fi on this phone")); return }
        if (!wm.isWifiEnabled) { go(WifiState.Error("Turn on Wi-Fi to find your TV")); return }
        go(WifiState.Discovering(emptyList()))
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {
                listener = null
                if (mcast.isHeld) mcast.release()
                go(WifiState.Error("Discovery failed ($e)"))
            }
            override fun onStopDiscoveryFailed(t: String, e: Int) { Log.d(TAG, "stop discovery failed: $e") }
            override fun onServiceFound(i: NsdServiceInfo) = resolve(i)
            override fun onServiceLost(i: NsdServiceInfo) = update { d -> d.filter { it.name != i.serviceName } }
        }
        listener = l
        mcast.acquire()
        runCatching { nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, l) }.onFailure {
            Log.d(TAG, "discoverServices: ${it.message}")
            stopDiscovery()
            go(WifiState.Error("Discovery failed"))
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(i: NsdServiceInfo, tries: Int = 0) {
        runCatching {
            nsd.resolveService(i, object : NsdManager.ResolveListener {
                // NsdManager resolves one service at a time; retry while busy, capped so it can't spin forever
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                    Log.d(TAG, "resolve ${s.serviceName} failed: $e")
                    if (e == NsdManager.FAILURE_ALREADY_ACTIVE && tries < 10 && listener != null) {
                        ui.postDelayed({ resolve(i, tries + 1) }, 300)
                    }
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val h = s.host?.hostAddress ?: return
                    update { d -> d.filter { it.host != h } + TvDevice(s.serviceName, h) }
                }
            })
        }.onFailure { Log.d(TAG, "resolve ${i.serviceName}: ${it.message}") }
    }

    private fun update(f: (List<TvDevice>) -> List<TvDevice>) {
        synchronized(this) {
            (_state.value as? WifiState.Discovering)?.let { _state.value = WifiState.Discovering(f(it.devices)) }
        }
    }

    private fun stopDiscovery() {
        listener?.let { l -> runCatching { nsd.stopServiceDiscovery(l) }.onFailure { Log.d(TAG, "stopServiceDiscovery: ${it.message}") } }
        listener = null
        if (mcast.isHeld) mcast.release()
    }

    fun connect(device: TvDevice) { stopSearch(); start(device, canPair = true) }

    fun disconnect() {
        stopSearch()
        closeSocket()
        go(WifiState.Idle)
    }

    /** secret = SHA-256(clientMod, clientExp, serverMod, serverExp, nonce); PIN = 1 check byte + 2 nonce bytes in hex. */
    fun submitPin(pin: String) {
        io.execute {
            try {
                val p = pin.trim()
                val nonce = p.substring(2, 6).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val c = (deviceKeyStore().getCertificate(ALIAS) as X509Certificate).publicKey as RSAPublicKey
                val s = serverKey ?: throw IOException("no pairing in progress")
                val secret = MessageDigest.getInstance("SHA-256").run {
                    update(c.modulus.raw()); update(c.publicExponent.raw())
                    update(s.modulus.raw()); update(s.publicExponent.raw())
                    digest(nonce)
                }
                if (p.substring(0, 2).toInt(16).toByte() != secret[0]) throw IOException("Wrong PIN")
                out!!.frame(HDR + lf(40, lf(1, secret)))
            } catch (e: Throwable) {
                Log.d(TAG, "PIN rejected: ${e.message}")
                disconnect() // closes pairing socket and bumps gen so the pairing thread can't overwrite
                go(WifiState.Error(e.message ?: "PIN rejected"))
            }
        }
    }

    override fun send(key: RemoteKey) {
        if (!available) return
        val body = if (key.androidKeyCode >= 0) lf(10, vf(1, key.androidKeyCode) + vf(2, 3)) // direction SHORT
        else lf(90, lf(1, LINKS[key] ?: return))
        write(body)
    }

    private fun write(body: ByteArray, my: Int = gen) = io.execute {
        try { out?.frame(body) } catch (e: Throwable) {
            Log.d(TAG, "send failed: ${e.message}")
            if (my == gen) { disconnect(); go(WifiState.Error("Send failed: ${e.message}")) }
        }
    }

    /** Invalidates in-flight connection threads and closes the socket. */
    private fun closeSocket() { gen++; dropSocket() }
    private fun dropSocket() { runCatching { sock?.close() }; sock = null; out = null }

    private fun start(dev: TvDevice, canPair: Boolean, quiet: Boolean = false) {
        closeSocket()
        if (!quiet) { stopDiscovery(); go(WifiState.Pairing) }
        val my = gen
        Thread {
            try {
                var r = openRemote(dev.host, my)
                if (r == null) {
                    if (!canPair) { if (my == gen) go(WifiState.Idle); return@Thread }
                    pair(dev.host, my)
                    r = openRemote(dev.host, my) ?: throw IOException("Pairing did not complete")
                }
                if (my != gen) { runCatching { r.first.close() }; return@Thread }
                prefs.edit().putString("host", dev.host).putString("name", dev.name).apply()
                remoteLoop(r.first, r.second, my, dev.name)
            } catch (e: Throwable) {
                Log.d(TAG, "connect failed: ${e.message}")
                if (my == gen) { closeSocket(); go(WifiState.Error(e.message ?: "Connection failed")) }
            }
        }.start()
    }

    /** AndroidKeyStore with the client key ensured. Any failure becomes one user-facing message. */
    private fun deviceKeyStore(): KeyStore = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.also { ks ->
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                        .setRandomizedEncryptionRequired(false)
                        .setCertificateSubject(X500Principal("CN=atvremote/dev.tvremote"))
                        .setCertificateNotAfter(java.util.Date(4070908800000L)) // 2099-01-01
                        .build())
                }.generateKeyPair()
            }
        }
    } catch (e: Exception) {
        throw IOException("Couldn't create device key", e)
    }

    private fun tls(host: String, port: Int, my: Int): SSLSocket {
        val ks = deviceKeyStore()
        val kmf = KeyManagerFactory.getInstance("X509").apply { init(ks, null) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(TRUST_ALL), SecureRandom()) }
        val s = ssl.socketFactory.createSocket() as SSLSocket
        try {
            s.enabledProtocols = s.supportedProtocols.intersect(setOf("TLSv1.2", "TLSv1.3")).toTypedArray()
            s.useClientMode = true
            s.keepAlive = true
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 5000
            s.startHandshake()
            if (my != gen) throw IOException("cancelled")
        } catch (e: Exception) { runCatching { s.close() }; throw e }
        sock = s
        out = s.outputStream
        return s
    }

    /** Connects to 6466; non-null only when the TV talks to us (i.e. it already trusts our cert). */
    private fun openRemote(host: String, my: Int): Pair<SSLSocket, ByteArray>? = try {
        val s = tls(host, 6466, my)
        val first = s.inputStream.frame()
        s.soTimeout = 0
        s to first
    } catch (e: IOException) {
        Log.d(TAG, "6466 not usable: ${e.message}")
        if (my == gen) dropSocket()
        null
    }

    private fun pair(host: String, my: Int) {
        val s = tls(host, 6467, my)
        serverKey = (s.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
        val i = s.inputStream
        out!!.frame(PAIR_REQ)
        while (true) {
            val m = parse(i.frame())
            if (m[2] != 200L) throw IOException("Pairing refused (status ${m[2]})")
            when {
                11 in m -> out!!.frame(PAIR_OPT)
                20 in m -> out!!.frame(PAIR_CFG)
                31 in m -> { s.soTimeout = 0; if (my == gen) go(WifiState.AwaitingPin) } // read timeout only until the TV shows its PIN
                41 in m -> { Log.d(TAG, "pairing accepted"); s.close(); return }
            }
        }
    }

    private fun remoteLoop(s: SSLSocket, first: ByteArray, my: Int, name: String) {
        try {
            var f = first
            while (true) {
                handle(f, my, name)
                f = s.inputStream.frame()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "connection lost: ${e.message}")
            if (my == gen) { dropSocket(); go(WifiState.Error("Connection lost")) }
        }
    }

    /** A malformed message is skipped; only transport errors end the loop. */
    private fun handle(f: ByteArray, my: Int, name: String) {
        try {
            val m = parse(f)
            when {
                1 in m -> write(CONFIGURE, my)
                2 in m -> write(SET_ACTIVE, my)
                40 in m -> if (my == gen) go(WifiState.Connected(name))
                8 in m -> write(lf(9, vf(1, ((parse(m[8] as ByteArray)[1] as? Long) ?: 0L).toInt())), my)
            }
        } catch (e: Exception) {
            Log.d(TAG, "skipped bad message: ${e.message}")
        }
    }
}
