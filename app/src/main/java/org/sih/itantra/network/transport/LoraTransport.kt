package org.sih.itantra.network.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType

/**
 * LoRa bearer through a USB-OTG attached radio bridge (ESP32 + SX1262 running `firmware/lora_bridge`).
 *
 * Serial framing (both directions):
 *   phone -> radio : `A5 5A lenHi lenLo <packet bytes>`
 *   radio -> phone : `A5 5A lenHi lenLo rssi(int8) snr(int8) <packet bytes>`   (len includes rssi+snr)
 *
 * USB drivers: CDC-ACM (ESP32-S3 native USB, RP2040, nRF52, CH9102) and Silicon Labs CP210x
 * (Heltec WiFi LoRa 32 V3). A single LoRa frame carries at most [MAX_LORA_PAYLOAD] bytes, so large
 * packets are refused here - the adaptive compressor picks compact semantic packets on slow links.
 */
class LoraTransport(private val context: Context) : NetworkTransport {
    override val transportType: TransportType = TransportType.LORA

    companion object {
        private const val TAG = "LoraTransport"
        private const val ACTION_USB_PERMISSION = "org.sih.itantra.LORA_USB_PERMISSION"
        const val MAX_LORA_PAYLOAD = 255
        private const val BAUD = 115200
        private const val SYNC0: Byte = 0xA5.toByte()
        private const val SYNC1: Byte = 0x5A
        private const val VID_SILABS = 0x10C4

        /** Parses radio->phone frames out of [buffer]; returns (packetBytes, rssi) list and leftover bytes. */
        fun parseFrames(buffer: ByteArray): Pair<List<Pair<ByteArray, Int>>, ByteArray> {
            val out = mutableListOf<Pair<ByteArray, Int>>()
            var i = 0
            while (i + 4 <= buffer.size) {
                if (buffer[i] != SYNC0 || buffer[i + 1] != SYNC1) { i++; continue }
                val len = ((buffer[i + 2].toInt() and 0xFF) shl 8) or (buffer[i + 3].toInt() and 0xFF)
                if (len < 2 || len > MAX_LORA_PAYLOAD + 2) { i++; continue }
                if (i + 4 + len > buffer.size) break // incomplete frame - wait for more bytes
                val rssi = buffer[i + 4].toInt() // signed int8 dBm
                val body = buffer.copyOfRange(i + 6, i + 4 + len)
                out += body to rssi
                i += 4 + len
            }
            return out to buffer.copyOfRange(i, buffer.size)
        }

        fun encodeFrame(packetBytes: ByteArray): ByteArray =
            byteArrayOf(SYNC0, SYNC1, (packetBytes.size shr 8).toByte(), packetBytes.size.toByte()) + packetBytes
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = Mutex()

    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var readJob: Job? = null
    @Volatile private var lastRssi: Int? = null

    /** (packet, rssi) callback into the TransportManager. */
    var onPacketReceived: ((Packet, Int?) -> Unit)? = null

    override val isAvailable: Boolean get() = connection != null

    val hardwareBridgeConnected: Boolean get() = isAvailable
    fun lastLinkRssi(): Int? = lastRssi

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION, UsbManager.ACTION_USB_DEVICE_ATTACHED -> scope.launch { tryOpen() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> close()
            }
        }
    }
    private var receiverRegistered = false

    override suspend fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        tryOpen()
    }

    override suspend fun stop() {
        close()
        if (receiverRegistered) {
            try { context.unregisterReceiver(usbReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
    }

    private fun findBridge(): UsbDevice? = usbManager?.deviceList?.values?.firstOrNull { dev ->
        dev.vendorId == VID_SILABS || (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
    }

    @Synchronized
    private fun tryOpen() {
        if (connection != null) return
        val mgr = usbManager ?: return
        val device = findBridge() ?: return
        if (!mgr.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
            mgr.requestPermission(device, pi)
            return
        }
        val conn = mgr.openDevice(device) ?: return
        try {
            val isCp210x = device.vendorId == VID_SILABS
            val iface = if (isCp210x) device.getInterface(0) else
                (0 until device.interfaceCount).map { device.getInterface(it) }.first { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            if (!conn.claimInterface(iface, true)) error("claimInterface failed")
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep else endpointOut = ep
            }
            if (endpointIn == null || endpointOut == null) error("bulk endpoints missing")
            if (isCp210x) configureCp210x(conn) else configureCdcAcm(conn, device)
            connection = conn
            dataInterface = iface
            startReader()
            Log.i(TAG, "LoRa bridge attached: ${device.productName ?: device.deviceName}")
        } catch (t: Throwable) {
            Log.w(TAG, "LoRa bridge open failed: ${t.message}")
            try { conn.close() } catch (_: Throwable) {}
            endpointIn = null; endpointOut = null
        }
    }

    private fun configureCdcAcm(conn: UsbDeviceConnection, device: UsbDevice) {
        val commIface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        val index = commIface?.id ?: 0
        // SET_LINE_CODING: baud (LE u32), 1 stop bit, no parity, 8 data bits
        val lineCoding = byteArrayOf(
            (BAUD and 0xFF).toByte(), ((BAUD shr 8) and 0xFF).toByte(), ((BAUD shr 16) and 0xFF).toByte(), ((BAUD shr 24) and 0xFF).toByte(),
            0, 0, 8
        )
        conn.controlTransfer(0x21, 0x20, 0, index, lineCoding, lineCoding.size, 1000)
        // SET_CONTROL_LINE_STATE: DTR | RTS (ESP32 boards need DTR to start talking)
        conn.controlTransfer(0x21, 0x22, 0x03, index, null, 0, 1000)
    }

    private fun configureCp210x(conn: UsbDeviceConnection) {
        conn.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, 1000)            // IFC_ENABLE
        val baud = byteArrayOf((BAUD and 0xFF).toByte(), ((BAUD shr 8) and 0xFF).toByte(), ((BAUD shr 16) and 0xFF).toByte(), 0)
        conn.controlTransfer(0x41, 0x1E, 0, 0, baud, 4, 1000)                  // SET_BAUDRATE
        conn.controlTransfer(0x41, 0x03, 0x0800, 0, null, 0, 1000)             // 8N1
        conn.controlTransfer(0x41, 0x07, 0x0303, 0, null, 0, 1000)             // DTR+RTS on
    }

    private fun startReader() {
        readJob?.cancel()
        readJob = scope.launch {
            val buf = ByteArray(512)
            var pending = ByteArray(0)
            while (isActive) {
                val conn = connection ?: break
                val n = conn.bulkTransfer(endpointIn, buf, buf.size, 250)
                if (n < 0) continue
                if (n == 0) continue
                pending += buf.copyOfRange(0, n)
                val (frames, rest) = parseFrames(pending)
                pending = if (rest.size > 4096) ByteArray(0) else rest
                for ((bytes, rssi) in frames) {
                    lastRssi = rssi
                    Packet.fromByteArray(bytes)?.let { onPacketReceived?.invoke(it, rssi) }
                }
            }
        }
    }

    @Synchronized
    private fun close() {
        readJob?.cancel(); readJob = null
        val conn = connection ?: return
        connection = null
        try { dataInterface?.let { conn.releaseInterface(it) } } catch (_: Throwable) {}
        try { conn.close() } catch (_: Throwable) {}
        endpointIn = null; endpointOut = null; dataInterface = null
        Log.i(TAG, "LoRa bridge detached")
    }

    override suspend fun sendPacket(packet: Packet, destinationAddress: String?): Boolean = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext false
        val out = endpointOut ?: return@withContext false
        val bytes = packet.toByteArray()
        if (bytes.size > MAX_LORA_PAYLOAD) {
            Log.d(TAG, "Packet ${bytes.size}B exceeds LoRa frame limit; not sent over LoRa")
            return@withContext false
        }
        val frame = encodeFrame(bytes)
        writeLock.withLock {
            conn.bulkTransfer(out, frame, frame.size, 1000) == frame.size
        }
    }
}
