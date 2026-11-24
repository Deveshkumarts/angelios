package org.sih.itantra.network.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType
import java.io.ByteArrayOutputStream
import java.net.*

/**
 * Wi-Fi Direct & Ad-hoc Local Mesh Transport.
 * Offers real peer-to-peer over-the-air communication using UDP broadcast and Wi-Fi Direct sockets.
 * Operates autonomously without cellular infrastructure or internet connectivity.
 */
class WifiDirectTransport(
    private val context: Context,
    var localNodeId: String = "NODE_UNKNOWN"
) : NetworkTransport {
    override val transportType: TransportType = TransportType.WIFI_DIRECT

    companion object {
        const val MESH_PORT = 8988
        private const val TAG = "WifiDirectTransport"
    }

    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = p2pManager?.initialize(context, context.mainLooper, null)

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var listeningJob: Job? = null
    private var tcpListeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val isAvailable: Boolean
        get() = true

    var onPacketReceived: ((Packet) -> Unit)? = null

    val knownPeerIps = java.util.concurrent.CopyOnWriteArraySet<String>()

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        try {
            // 1. Acquire Multicast Lock to allow receipt of broadcast/multicast UDP packets on Android
            try {
                if (multicastLock?.isHeld != true) {
                    multicastLock = wifiManager?.createMulticastLock("itantra_p2p_multicast")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not acquire multicast lock: ${t.message}")
            }

            // 2 & 3. Open UDP + TCP sockets independently: a failure of one must never leave the
            // device unable to receive on the other (previously any bind error aborted start()).
            ensureUdpSocket()
            ensureTcpSocket()

            // 4. Launch background UDP receiver coroutine
            listeningJob?.cancel()
            listeningJob = scope.launch {
                val buffer = ByteArray(4096)
                while (isActive) {
                    try {
                        val sock = serverSocket
                        if (sock == null || sock.isClosed) {
                            // Socket lost (e.g. Wi-Fi reconnect): retry instead of busy-spinning
                            delay(1000L)
                            ensureUdpSocket()
                            continue
                        }
                        val datagram = DatagramPacket(buffer, buffer.size)
                        sock.receive(datagram)

                        val peerIp = datagram.address?.hostAddress
                        if (!peerIp.isNullOrBlank() && peerIp != "127.0.0.1") {
                            knownPeerIps.add(peerIp)
                        }

                        val payloadBytes = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                        val incomingPacket = Packet.fromByteArray(payloadBytes)

                        if (incomingPacket != null) {
                            val cleanSender = incomingPacket.senderId.trim()
                            val cleanLocal = localNodeId.trim()
                            val isSelf = cleanSender.equals(cleanLocal, ignoreCase = true)

                            if (!isSelf) {
                                Log.d(TAG, "Received UDP packet from $cleanSender ($peerIp): ${incomingPacket.messageId}")
                                withContext(Dispatchers.Default) {
                                    onPacketReceived?.invoke(incomingPacket)
                                }
                            } else {
                                Log.d(TAG, "Ignored loopback of self-transmitted packet ${incomingPacket.messageId}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (isActive) {
                            Log.w(TAG, "Error in UDP receive loop: ${e.message}")
                            delay(200L)
                        }
                    }
                }
            }

            // 5. Launch background TCP receiver coroutine
            tcpListeningJob?.cancel()
            tcpListeningJob = scope.launch {
                while (isActive) {
                    try {
                        val server = tcpServerSocket
                        if (server == null || server.isClosed) {
                            delay(1000L)
                            ensureTcpSocket()
                            continue
                        }
                        val client = server.accept()
                        scope.launch(Dispatchers.IO) {
                            try {
                                val peerIp = client.inetAddress?.hostAddress
                                if (!peerIp.isNullOrBlank() && peerIp != "127.0.0.1") {
                                    knownPeerIps.add(peerIp)
                                }

                                val stream = client.getInputStream()
                                val buffer = ByteArray(4096)
                                val baos = ByteArrayOutputStream()
                                client.soTimeout = 2000

                                val readCount = stream.read(buffer)
                                if (readCount > 0) {
                                    baos.write(buffer, 0, readCount)
                                    val incomingPacket = Packet.fromByteArray(baos.toByteArray())
                                    if (incomingPacket != null) {
                                        val cleanSender = incomingPacket.senderId.trim()
                                        val cleanLocal = localNodeId.trim()
                                        val isSelf = cleanSender.equals(cleanLocal, ignoreCase = true)

                                        if (!isSelf) {
                                            Log.d(TAG, "Received TCP packet from $cleanSender ($peerIp): ${incomingPacket.messageId}")
                                            withContext(Dispatchers.Default) {
                                                onPacketReceived?.invoke(incomingPacket)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.v(TAG, "TCP connection handled: ${e.message}")
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (isActive) {
                            Log.w(TAG, "Error in TCP accept loop: ${e.message}")
                            delay(200L)
                        }
                    }
                }
            }

            startNsd()

            Log.i(TAG, "Wi-Fi Direct / Local Mesh Transport started on port $MESH_PORT (UDP & TCP)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Wi-Fi Direct sockets", e)
        }
    }

    private var nsdManager: android.net.nsd.NsdManager? = null
    private var nsdRegistration: android.net.nsd.NsdManager.RegistrationListener? = null
    private var nsdDiscovery: android.net.nsd.NsdManager.DiscoveryListener? = null

    /**
     * mDNS/NSD advertise + discovery. Works on routers that pass multicast but filter broadcast,
     * and gives us peer IPs even when the subnet sweep is blocked.
     */
    @Suppress("DEPRECATION")
    private fun startNsd() {
        try {
            if (nsdDiscovery != null) return
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
            nsdManager = nsd
            val serviceType = "_itantra._tcp."

            val info = android.net.nsd.NsdServiceInfo().apply {
                serviceName = "iTANTRA_${localNodeId}"
                setServiceType(serviceType)
                port = MESH_PORT
            }
            nsdRegistration = object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) {}
                override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) { Log.w(TAG, "NSD register failed $e") }
                override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdRegistration)

            nsdDiscovery = object : android.net.nsd.NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "NSD discovery failed $e"); nsdDiscovery = null }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onServiceLost(s: android.net.nsd.NsdServiceInfo) {}
                override fun onServiceFound(s: android.net.nsd.NsdServiceInfo) {
                    if (s.serviceName == info.serviceName) return
                    try {
                        nsd.resolveService(s, object : android.net.nsd.NsdManager.ResolveListener {
                            override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
                            override fun onServiceResolved(i: android.net.nsd.NsdServiceInfo) {
                                val ip = i.host?.hostAddress
                                if (i.host is Inet4Address && !ip.isNullOrBlank()) {
                                    Log.i(TAG, "NSD found peer ${i.serviceName} at $ip")
                                    knownPeerIps.add(ip)
                                }
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }
            nsd.discoverServices(serviceType, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdDiscovery)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD unavailable: ${t.message}")
        }
    }

    private fun stopNsd() {
        try { nsdDiscovery?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Throwable) {}
        try { nsdRegistration?.let { nsdManager?.unregisterService(it) } } catch (_: Throwable) {}
        nsdDiscovery = null
        nsdRegistration = null
    }

    private fun ensureUdpSocket() {
        try {
            if (serverSocket == null || serverSocket?.isClosed == true) {
                serverSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                Log.i(TAG, "UDP socket bound on port $MESH_PORT")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bind UDP socket on $MESH_PORT: ${t.message}")
        }
    }

    private fun ensureTcpSocket() {
        try {
            if (tcpServerSocket == null || tcpServerSocket?.isClosed == true) {
                tcpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                Log.i(TAG, "TCP socket bound on port $MESH_PORT")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bind TCP ServerSocket on $MESH_PORT: ${t.message}")
        }
    }

    /**
     * The Wi-Fi network the phone is joined to. When that Wi-Fi has no internet (typical for a
     * hotspot / field router) Android routes an unbound socket over mobile data instead, so LAN
     * broadcasts and peer connections never reach the other phone. Binding to the Wi-Fi network fixes that.
     */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): android.net.Network? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.allNetworks?.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Throwable) {
        null
    }

    private fun bindToWifi(socket: DatagramSocket) {
        try { wifiNetwork()?.bindSocket(socket) } catch (t: Throwable) {
            Log.v(TAG, "UDP bindSocket to Wi-Fi skipped: ${t.message}")
        }
    }

    private fun bindToWifi(socket: Socket) {
        try { wifiNetwork()?.bindSocket(socket) } catch (t: Throwable) {
            Log.v(TAG, "TCP bindSocket to Wi-Fi skipped: ${t.message}")
        }
    }

    override suspend fun stop() {
        stopNsd()
        listeningJob?.cancel()
        listeningJob = null
        tcpListeningJob?.cancel()
        tcpListeningJob = null
        try {
            serverSocket?.close()
            serverSocket = null
            tcpServerSocket?.close()
            tcpServerSocket = null
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping transport", e)
        }
    }

    /**
     * Resolves the default gateway IPv4 address (e.g. Hotspot host or Wi-Fi AP).
     */
    fun getGatewayIp(): String? {
        try {
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val gw = dhcp.gateway
                return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
            }
        } catch (_: Exception) {}

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val linkProps = cm?.getLinkProperties(cm.activeNetwork)
            linkProps?.routes?.forEach { route ->
                route.gateway?.let { gw ->
                    if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                        return gw.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Reads /proc/net/arp to detect all hardware-connected Wi-Fi peers
     * (Hotspot clients, Wi-Fi Direct peers, LAN devices).
     */
    fun getDiscoveredArpIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val arpDoc = java.io.File("/proc/net/arp")
            if (arpDoc.exists() && arpDoc.canRead()) {
                arpDoc.bufferedReader().useLines { lines ->
                    for (line in lines.drop(1)) {
                        val tokens = line.split(Regex("\\s+"))
                        if (tokens.size >= 4) {
                            val ip = tokens[0]
                            val mac = tokens[3]
                            if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && mac != "00:00:00:00:00:00") {
                                ips.add(ip)
                                knownPeerIps.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Could not read /proc/net/arp: ${e.message}")
        }
        return ips
    }

    /**
     * Dynamically gathers all candidate IP addresses to reach all peers on the network.
     */
    fun getDynamicTargetAddresses(destinationAddress: String? = null): Set<InetAddress> {
        val targetAddresses = mutableSetOf<InetAddress>()

        // 1. Explicit destination if supplied
        if (destinationAddress != null) {
            try { targetAddresses.add(InetAddress.getByName(destinationAddress)) } catch (_: Exception) {}
        }

        // 2. Global subnet broadcast
        try { targetAddresses.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}

        // 3. Dynamic Gateway (Hotspot Host)
        getGatewayIp()?.let { gw ->
            try {
                targetAddresses.add(InetAddress.getByName(gw))
                knownPeerIps.add(gw)
            } catch (_: Exception) {}
        }

        // 4. Standard gateways across all Android Hotspot & Wi-Fi Direct implementations
        val standardGateways = listOf(
            "192.168.49.1", "192.168.49.255",
            "192.168.43.1", "192.168.43.255",
            "192.168.1.1", "192.168.1.255",
            "192.168.0.1", "192.168.0.255",
            "192.168.225.1", "192.168.225.255",
            "192.168.137.1", "192.168.137.255",
            "172.20.10.1", "172.20.10.255"
        )
        for (gw in standardGateways) {
            try { targetAddresses.add(InetAddress.getByName(gw)) } catch (_: Exception) {}
        }

        // 5. Wi-Fi subnet: directed broadcast + a full unicast sweep of the local /24 (or the real,
        // smaller subnet). Many routers/APs drop broadcast frames or use client isolation for them,
        // and DHCP pools often start outside .2-.20/.100-.115, so unicast to every host is the
        // only reliable way to find peers on the same network.
        val localV4 = LinkedHashMap<Inet4Address, Int>() // address -> prefix length
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifi = wifiNetwork()
            cm?.getLinkProperties(wifi)?.linkAddresses?.forEach { la ->
                (la.address as? Inet4Address)?.let { if (!it.isLoopbackAddress) localV4[it] = la.prefixLength }
            }
        } catch (_: Throwable) {}
        if (localV4.isEmpty()) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val n = iface.name.lowercase()
                    // Skip cellular / VPN interfaces; keep wlan, ap, swlan, p2p, eth
                    if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("dummy")) continue
                    for (addr in iface.interfaceAddresses) {
                        val ip = addr.address
                        if (ip is Inet4Address && !ip.isLoopbackAddress) {
                            localV4[ip] = addr.networkPrefixLength.toInt()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        for ((ip, prefix) in localV4) {
            val own = ip.address
            val ownInt = ((own[0].toInt() and 0xFF) shl 24) or ((own[1].toInt() and 0xFF) shl 16) or
                ((own[2].toInt() and 0xFF) shl 8) or (own[3].toInt() and 0xFF)
            // Sweep at most a /24 (254 hosts) around our own address to keep beacon traffic small
            val effPrefix = prefix.coerceIn(24, 30)
            val mask = (-1 shl (32 - effPrefix))
            val network = ownInt and mask
            val broadcast = network or mask.inv()
            fun toAddr(v: Int): InetAddress = InetAddress.getByAddress(
                byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            )
            try { targetAddresses.add(toAddr(broadcast)) } catch (_: Exception) {}
            for (host in (network + 1) until broadcast) {
                if (host == ownInt) continue
                try { targetAddresses.add(toAddr(host)) } catch (_: Exception) {}
            }
        }

        // 6. All ARP peers and known received peer IPs
        for (ip in getDiscoveredArpIps()) {
            try { targetAddresses.add(InetAddress.getByName(ip)) } catch (_: Exception) {}
        }
        for (ip in knownPeerIps) {
            try { targetAddresses.add(InetAddress.getByName(ip)) } catch (_: Exception) {}
        }

        return targetAddresses
    }

    /**
     * Broadcasts a discovery beacon over all available Wi-Fi interfaces and peer IPs.
     */
    suspend fun broadcastDiscoveryBeacon(
        nodeId: String,
        deviceName: String,
        batteryPct: Int
    ): Boolean {
        val payload = "iTANTRA_PEER|$deviceName|$batteryPct".toByteArray(Charsets.UTF_8)
        val beacon = Packet(
            senderId = nodeId,
            receiverId = "BRDCAST_",
            packetType = org.sih.itantra.domain.model.PacketType.HELLO,
            priority = org.sih.itantra.domain.model.Priority.NORMAL,
            payload = payload
        )
        return sendPacket(beacon)
    }

    override suspend fun sendPacket(packet: Packet, destinationAddress: String?): Boolean = withContext(Dispatchers.IO) {
        val wireBytes = packet.toByteArray()

        try {
            val socket = DatagramSocket().apply {
                broadcast = true
            }
            bindToWifi(socket)

            val targetAddresses = getDynamicTargetAddresses(destinationAddress)

            // 1. Broadcast over-the-air UDP across all target endpoints
            for (addr in targetAddresses) {
                try {
                    val datagram = DatagramPacket(wireBytes, wireBytes.size, addr, MESH_PORT)
                    socket.send(datagram)
                } catch (e: Exception) {
                    Log.v(TAG, "Failed to UDP send to $addr", e)
                }
            }
            try { socket.close() } catch (_: Exception) {}

            // 2. Parallel TCP Direct Delivery to all known peer IPs and gateway for 100% reliability
            val tcpTargets = mutableSetOf<String>()
            if (destinationAddress != null) tcpTargets.add(destinationAddress)
            tcpTargets.addAll(knownPeerIps)
            getGatewayIp()?.let { tcpTargets.add(it) }

            for (targetIp in tcpTargets) {
                if (targetIp == "127.0.0.1") continue
                scope.launch(Dispatchers.IO) {
                    try {
                        Socket().use { tcp ->
                            bindToWifi(tcp)
                            tcp.connect(InetSocketAddress(targetIp, MESH_PORT), 400)
                            val out = tcp.getOutputStream()
                            out.write(wireBytes)
                            out.flush()
                        }
                    } catch (_: Exception) {}
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast packet", e)
            false
        }
    }
}
