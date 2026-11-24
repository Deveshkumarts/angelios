package org.sih.itantra.network.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bluetooth Low Energy mesh bearer.
 *
 * Every node runs both roles at once:
 *  - **Peripheral**: advertises [SERVICE_UUID] and hosts a GATT server with one writable characteristic.
 *    Whatever a peer writes there is reassembled into a [Packet] and handed to the mesh router.
 *  - **Central**: scans for [SERVICE_UUID]; to send, it connects to each recently-seen peer (connections
 *    are cached), negotiates a larger MTU and writes the packet to the peer's characteristic.
 *
 * Packets larger than one ATT write are split into fragments: fragId, index, count, then the chunk.
 * The mesh router's duplicate suppression absorbs the copies that also arrive over Wi-Fi.
 */
class BluetoothTransport(private val context: Context) : NetworkTransport {
    override val transportType: TransportType = TransportType.BLE

    companion object {
        private const val TAG = "BluetoothTransport"
        /** Custom 128-bit UUIDs (not SIG-assigned) for the iTANTRA mesh service. */
        val SERVICE_UUID: UUID = UUID.fromString("6e1a0001-7c3b-4b8e-9a51-1f2d3c4b5a69")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6e1a0002-7c3b-4b8e-9a51-1f2d3c4b5a69")
        private const val FRAG_HEADER = 3
        private const val REQUESTED_MTU = 247
        private const val PEER_STALE_MS = 30_000L
        private const val MAX_PEERS_PER_SEND = 7
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    @Volatile private var started = false
    private var gattServer: BluetoothGattServer? = null

    /** (packet, rssi-or-null) callback into the TransportManager. */
    var onPacketReceived: ((Packet, Int?) -> Unit)? = null

    private data class Peer(val device: BluetoothDevice, val rssi: Int, val lastSeen: Long)
    private val peers = ConcurrentHashMap<String, Peer>()

    private class Link(val gatt: BluetoothGatt) {
        val lock = Mutex()
        @Volatile var ready = CompletableDeferred<Boolean>()
        @Volatile var mtu = 23
        @Volatile var pendingWrite: CompletableDeferred<Boolean>? = null
        @Volatile var characteristic: BluetoothGattCharacteristic? = null
    }
    private val links = ConcurrentHashMap<String, Link>()

    private val fragCounter = AtomicInteger(0)
    private data class Reassembly(val parts: Array<ByteArray?>, val created: Long = System.currentTimeMillis())
    private val reassembly = ConcurrentHashMap<String, Reassembly>()

    override val isAvailable: Boolean
        get() = adapter?.isEnabled == true && hasPermissions() && started

    private fun hasPermissions(): Boolean {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT) &&
                granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun start(): Unit = withContext(Dispatchers.Main) {
        if (started) return@withContext
        val bt = adapter
        if (bt == null || !bt.isEnabled || !hasPermissions()) {
            Log.i(TAG, "BLE not started (adapter=${bt != null}, enabled=${bt?.isEnabled}, perms=${hasPermissions()})")
            return@withContext
        }
        try {
            openGattServer()
            startAdvertising(bt)
            startScanning(bt)
            started = true
            Log.i(TAG, "BLE mesh bearer started")
        } catch (t: Throwable) {
            Log.e(TAG, "BLE start failed: ${t.message}", t)
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop(): Unit = withContext(Dispatchers.Main) {
        started = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Throwable) {}
        links.values.forEach { try { it.gatt.close() } catch (_: Throwable) {} }
        links.clear()
        try { gattServer?.close() } catch (_: Throwable) {}
        gattServer = null
    }

    // ---------------------------------------------------------------- peripheral role

    @SuppressLint("MissingPermission")
    private fun openGattServer() {
        val server = bluetoothManager?.openGattServer(context, serverCallback) ?: error("No GATT server")
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) {
                try { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (_: Throwable) {}
            }
            if (characteristic.uuid == CHARACTERISTIC_UUID && value != null) {
                onFragment(device.address, value)
            }
        }
    }

    private fun onFragment(address: String, frame: ByteArray) {
        if (frame.size <= FRAG_HEADER) return
        val fragId = frame[0].toInt() and 0xFF
        val index = frame[1].toInt() and 0xFF
        val count = frame[2].toInt() and 0xFF
        if (count == 0 || index >= count) return
        val chunk = frame.copyOfRange(FRAG_HEADER, frame.size)

        val whole: ByteArray? = if (count == 1) chunk else {
            val key = "$address#$fragId"
            val r = reassembly.getOrPut(key) { Reassembly(arrayOfNulls(count)) }
            if (r.parts.size != count) { reassembly.remove(key); return }
            r.parts[index] = chunk
            if (r.parts.all { it != null }) {
                reassembly.remove(key)
                r.parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
            } else null
        }
        // Drop half-received messages after 10 s
        val now = System.currentTimeMillis()
        reassembly.entries.removeIf { now - it.value.created > 10_000 }

        whole ?: return
        val packet = Packet.fromByteArray(whole) ?: return
        onPacketReceived?.invoke(packet, peers[address]?.rssi)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(bt: BluetoothAdapter) {
        val advertiser = bt.bluetoothLeAdvertiser ?: run { Log.w(TAG, "BLE advertising unsupported"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "Advertising failed: $errorCode") }
    }

    // ---------------------------------------------------------------- central role

    @SuppressLint("MissingPermission")
    private fun startScanning(bt: BluetoothAdapter) {
        val scanner = bt.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(filters, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            peers[result.device.address] = Peer(result.device, result.rssi, System.currentTimeMillis())
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "BLE scan failed: $errorCode") }
    }

    /** Strongest RSSI among currently visible BLE peers, or null. */
    fun bestPeerRssi(): Int? {
        val now = System.currentTimeMillis()
        return peers.values.filter { now - it.lastSeen < PEER_STALE_MS }.maxOfOrNull { it.rssi }
    }

    override suspend fun sendPacket(packet: Packet, destinationAddress: String?): Boolean {
        if (!started && adapter?.isEnabled == true && hasPermissions()) start() // permission granted after launch
        if (!isAvailable) return false

        val now = System.currentTimeMillis()
        peers.entries.removeIf { now - it.value.lastSeen > PEER_STALE_MS }
        val targets = peers.values.sortedByDescending { it.rssi }.take(MAX_PEERS_PER_SEND)
        if (targets.isEmpty()) return false

        val bytes = packet.toByteArray()
        var anyOk = false
        for (peer in targets) {
            if (writeToPeer(peer.device, bytes)) anyOk = true
        }
        return anyOk
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeToPeer(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val link = links[device.address] ?: connect(device) ?: return false
        if (withTimeoutOrNull(8_000) { link.ready.await() } != true) {
            dropLink(device.address); return false
        }
        val ch = link.characteristic ?: return false
        val chunkSize = (link.mtu - 3 - FRAG_HEADER).coerceAtLeast(16)
        val count = (bytes.size + chunkSize - 1) / chunkSize
        if (count > 255) return false
        val fragId = fragCounter.getAndIncrement() and 0xFF

        return link.lock.withLock {
            for (i in 0 until count) {
                val chunk = bytes.copyOfRange(i * chunkSize, minOf(bytes.size, (i + 1) * chunkSize))
                val frame = byteArrayOf(fragId.toByte(), i.toByte(), count.toByte()) + chunk
                val done = CompletableDeferred<Boolean>()
                link.pendingWrite = done
                val issued = withContext(Dispatchers.Main) { issueWrite(link.gatt, ch, frame) }
                if (!issued || withTimeoutOrNull(3_000) { done.await() } != true) {
                    dropLink(device.address)
                    return@withLock false
                }
            }
            true
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun issueWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = frame
            gatt.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice): Link? = withContext(Dispatchers.Main) {
        if (links.size >= MAX_PEERS_PER_SEND) {
            // Evict the oldest connection to make room (Android caps concurrent GATT links)
            links.keys.firstOrNull()?.let { dropLink(it) }
        }
        val gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE) ?: return@withContext null
        Link(gatt).also { links[device.address] = it }
    }

    @SuppressLint("MissingPermission")
    private fun dropLink(address: String) {
        links.remove(address)?.let { try { it.gatt.disconnect(); it.gatt.close() } catch (_: Throwable) {} }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val link = links[gatt.device.address] ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
            } else {
                link.ready.complete(false)
                link.pendingWrite?.complete(false)
                dropLink(gatt.device.address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            links[gatt.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = links[gatt.device.address] ?: return
            link.characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            link.ready.complete(link.characteristic != null)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            links[gatt.device.address]?.pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
}
