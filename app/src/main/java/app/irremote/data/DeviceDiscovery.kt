package app.irremote.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors

/**
 * Looks for TVs and casting targets around the phone: mDNS (Google Cast, AirPlay), SSDP/UPnP (Smart View, DLNA,
 * DIAL, Roku) and Wi-Fi Direct (Miracast). Results arrive on the main thread; [close] stops everything.
 */
class DeviceDiscovery(private val context: Context, private val onFound: (FoundDevice) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var closed = false
    private val seen = HashSet<String>()
    private val cleanups = ArrayList<() -> Unit>()

    private fun emit(d: FoundDevice) {
        main.post { if (!closed && seen.add(d.key)) onFound(d) }
    }

    fun start(includeWifiDirect: Boolean) {
        runCatching { startMdns() }
        pool.execute { runCatching { ssdp() } }
        if (includeWifiDirect) runCatching { startWifiDirect() }
    }

    // ------------------------------------------------------------ mDNS

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun startMdns() {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        for (type in listOf("_googlecast._tcp.", "_airplay._tcp.")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onServiceFound(s: NsdServiceInfo) = enqueue(nsd, s)
            }
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            cleanups.add { runCatching { nsd.stopServiceDiscovery(l) } }
        }
    }

    @Synchronized private fun enqueue(nsd: NsdManager, s: NsdServiceInfo) {
        resolveQueue.addLast(s)
        pump(nsd)
    }

    // NsdManager can only resolve one service at a time on older Android versions.
    @Synchronized @Suppress("DEPRECATION") private fun pump(nsd: NsdManager) {
        if (resolving || closed) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val txt = s.attributes.mapValues { String(it.value ?: ByteArray(0)) }
                emit(if (s.serviceType.contains("googlecast")) DiscoveryParsers.googleCast(s.serviceName, txt) else DiscoveryParsers.airPlay(s.serviceName, txt))
                done()
            }
            private fun done() { synchronized(this@DeviceDiscovery) { resolving = false; pump(nsd) } }
        })
    }

    // ------------------------------------------------------------ SSDP / UPnP

    private fun ssdp() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("irremote-ssdp").apply { setReferenceCounted(false); acquire() }
        val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)); soTimeout = 1000 }
        cleanups.add { runCatching { socket.close() }; runCatching { if (lock.isHeld) lock.release() } }
        val group = InetAddress.getByName("239.255.255.250")
        val targets = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:dial-multiscreen-org:service:dial:1", "roku:ecp")
        repeat(2) {
            for (st in targets) {
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                runCatching { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
            }
            Thread.sleep(300)
        }
        val fetched = HashSet<String>()
        val deadline = System.currentTimeMillis() + 15_000
        val buf = ByteArray(2048)
        while (!closed && System.currentTimeMillis() < deadline) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (e: java.net.SocketTimeoutException) { continue } catch (e: Exception) { break }
            val h = DiscoveryParsers.ssdpHeaders(String(p.data, 0, p.length))
            val loc = h["location"] ?: continue
            if (!fetched.add(loc)) continue
            val dial = h["st"]?.contains("dial", ignoreCase = true) == true
            pool.execute { runCatching { fetchDescription(loc, dial) } }
        }
    }

    private fun fetchDescription(location: String, dial: Boolean) {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        val xml = try { c.inputStream.use { it.readNBytesCompat(64 * 1024) } } finally { c.disconnect() }
        DiscoveryParsers.upnp(xml, dial)?.let(::emit)
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(4096)
        while (out.size() < max) { val n = read(b); if (n < 0) break; out.write(b, 0, n) }
        return out.toString("UTF-8")
    }

    // ------------------------------------------------------------ Wi-Fi Direct (Miracast)

    @SuppressLint("MissingPermission")
    private fun startWifiDirect() {
        val p2p = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        val channel = p2p.initialize(context, Looper.getMainLooper(), null) ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    p2p.requestPeers(channel) { peers ->
                        peers.deviceList.mapNotNull { DiscoveryParsers.miracast(it.deviceName.orEmpty(), it.primaryDeviceType) }.forEach(::emit)
                    }
                } catch (_: SecurityException) { }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        cleanups.add { runCatching { context.unregisterReceiver(receiver) }; runCatching { p2p.stopPeerDiscovery(channel, null) } }
        try { p2p.discoverPeers(channel, null) } catch (_: SecurityException) { }
    }

    override fun close() {
        closed = true
        cleanups.forEach { runCatching { it() } }
        cleanups.clear()
        pool.shutdownNow()
    }
}
