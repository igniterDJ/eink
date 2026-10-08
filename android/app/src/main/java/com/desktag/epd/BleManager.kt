package com.desktag.epd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * Manages BLE scanning, connection, and the send protocol to an EPD-DeskTag device.
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val statusCallback: StatusCallback
) {
    companion object {
        private const val TAG = "BleManager"

        val SERVICE_UUID: UUID = UUID.fromString("a0e1c000-5b9d-4c3a-9a39-2f6d8e1b7c00")
        val CTRL_UUID: UUID = UUID.fromString("a0e1c001-5b9d-4c3a-9a39-2f6d8e1b7c00")
        val DATA_UUID: UUID = UUID.fromString("a0e1c002-5b9d-4c3a-9a39-2f6d8e1b7c00")
        val STATUS_UUID: UUID = UUID.fromString("a0e1c003-5b9d-4c3a-9a39-2f6d8e1b7c00")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 247
        private const val FRAME_TOTAL_LEN = 8000

        /** Operation timeout in milliseconds */
        const val TIMEOUT_MS = 60_000L
    }

    interface StatusCallback {
        fun onStatus(message: String)
        fun onGattConnected()
        fun onGattDisconnected()
        fun onTransferComplete()
        fun onTransferError(error: String)
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var ctrlChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private var negotiatedMtu: Int = 23 // BLE default MTU

    private var pendingChunks: List<ByteArray> = emptyList()
    private var chunkIndex: Int = 0
    private var currentFrame: ByteArray? = null
    private var currentCrc: Long = 0L
    private var lastCtrlCommand: Int = -1
    private var pendingCommitOpcode: Int = 0x02

    private enum class TransferState {
        IDLE,
        SENDING,
        WAIT_RESULT
    }

    // Explicit state machine (review2.md #4)
    @Volatile private var transferState: TransferState = TransferState.IDLE

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private var scanning: Boolean = false

    // CCCD must be fully written before we send BEGIN (review.md #7)
    private val cccdWriteComplete = AtomicBoolean(false)

    // ---------- public API ----------

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun startScan() {
        if (!isBluetoothEnabled()) {
            statusCallback.onStatus("Bluetooth not enabled")
            return
        }
        if (scanning) return
        scanning = true
        statusCallback.onStatus("Scanning for DeskTag...")

        // review.md #9: scan by service UUID; name fallback not needed
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter
            ?.bluetoothLeScanner
            ?.startScan(filters, settings, scanCallback)

        handler.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    fun connect(device: BluetoothDevice) {
        statusCallback.onStatus("Connecting to ${device.address}...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        cancelTimeout()
        scanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        ctrlChar = null
        dataChar = null
        statusChar = null
        cccdWriteComplete.set(false)

        // reset state machine on local disconnect
        transferState = TransferState.IDLE
    }

    fun sendFrame(frame: ByteArray, asBackground: Boolean = false) {
        if (frame.size != FRAME_TOTAL_LEN) {
            statusCallback.onTransferError("Frame must be $FRAME_TOTAL_LEN bytes")
            return
        }
        val gatt = bluetoothGatt
        val ctrl = ctrlChar
        val data = dataChar
        val status = statusChar
        if (gatt == null || ctrl == null || data == null || status == null) {
            statusCallback.onTransferError("Not connected or characteristics missing")
            return
        }
        if (!cccdWriteComplete.get()) {
            statusCallback.onTransferError("Notifications not enabled yet")
            return
        }

        // on Send start set state=SENDING (reset all flags)
        transferState = TransferState.SENDING
        pendingCommitOpcode = if (asBackground) 0x05 else 0x02

        currentFrame = frame
        currentCrc = CRC32().apply { update(frame) }.value

        /* ATT value capacity is MTU - 3; reserve two bytes for our offset. */
        val payloadMax = (negotiatedMtu - 3 - 2).coerceAtLeast(18)
        pendingChunks = buildChunks(frame, payloadMax)
        chunkIndex = 0

        statusCallback.onStatus(
            "Sending BEGIN... (MTU=$negotiatedMtu, chunks=${pendingChunks.size})"
        )
        Log.i(TAG, "Frame fingerprint ${frameFingerprint(frame)}")

        startTimeout("Transfer timed out")

        writeCtrl(beginPayload())
    }

    private fun frameFingerprint(frame: ByteArray): String {
        var sum = 0L
        for (b in frame) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFFFFFFFFL
        }
        fun hexByte(index: Int): String =
            (frame[index].toInt() and 0xFF).toString(16).padStart(2, '0')
        val first = (0 until 8).joinToString("") { hexByte(it) }
        val last = (frame.size - 8 until frame.size).joinToString("") { hexByte(it) }
        return "crc=${currentCrc.toString(16).padStart(8, '0')} sum=${sum.toString(16).padStart(8, '0')} first=$first last=$last"
    }

    fun clearDisplay() {
        val ctrl = ctrlChar ?: run {
            statusCallback.onTransferError("Not connected")
            return
        }
        if (!cccdWriteComplete.get()) {
            statusCallback.onTransferError("Notifications not enabled yet")
            return
        }

        // treat CLEAR as a transfer that waits for STATUS result
        transferState = TransferState.SENDING

        startTimeout("Clear timed out")
        writeCtrl(byteArrayOf(0x03))
    }

    fun redrawBackground() {
        val ctrl = ctrlChar ?: run {
            statusCallback.onTransferError("Not connected")
            return
        }
        if (!cccdWriteComplete.get()) {
            statusCallback.onTransferError("Notifications not enabled yet")
            return
        }

        // treat REDRAW_BG as a transfer that waits for STATUS result
        transferState = TransferState.SENDING

        startTimeout("Redraw background timed out")
        writeCtrl(byteArrayOf(0x04))
    }

    // ---------- internal helpers ----------

    private fun startTimeout(message: String) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            transferState = TransferState.IDLE
            statusCallback.onTransferError(message)
            disconnect()
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun buildChunks(frame: ByteArray, payloadMax: Int): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val len = minOf(payloadMax, frame.size - offset)
            val chunk = ByteArray(2 + len)
            // u16 LE offset
            chunk[0] = (offset and 0xFF).toByte()
            chunk[1] = ((offset shr 8) and 0xFF).toByte()
            System.arraycopy(frame, offset, chunk, 2, len)
            chunks.add(chunk)
            offset += len
        }
        return chunks
    }

    private fun beginPayload(): ByteArray {
        val b = ByteBuffer.allocate(1 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x01)
        b.putInt(FRAME_TOTAL_LEN)
        return b.array()
    }

    private fun commitPayload(): ByteArray {
        val b = ByteBuffer.allocate(1 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(pendingCommitOpcode.toByte())
        b.putInt(currentCrc.toInt())
        return b.array()
    }

    private fun writeCtrl(payload: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val ctrl = ctrlChar ?: return
        lastCtrlCommand = payload.getOrNull(0)?.toInt() ?: -1
        val ok = writeCharacteristicCompat(gatt, ctrl, payload)
        if (!ok) {
            cancelTimeout()
            transferState = TransferState.IDLE
            statusCallback.onTransferError("Failed to initiate CTRL write")
        }
    }

    private fun writeData(payload: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val data = dataChar ?: return
        val ok = writeCharacteristicCompat(gatt, data, payload)
        if (!ok) {
            cancelTimeout()
            transferState = TransferState.IDLE
            statusCallback.onTransferError("Failed to initiate DATA write")
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val desc = characteristic.getDescriptor(CCCD_UUID)
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            stopScan()
            statusCallback.onStatus("Found device: ${device.address}")
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            statusCallback.onTransferError("Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                statusCallback.onGattConnected()
                statusCallback.onStatus("Connected. Discovering services...")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasActive = transferState != TransferState.IDLE

                cancelTimeout()
                transferState = TransferState.IDLE

                statusCallback.onGattDisconnected()
                disconnect()

                if (wasActive) {
                    statusCallback.onTransferError("Disconnected during transfer")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                statusCallback.onTransferError("Service discovery failed: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                statusCallback.onTransferError("Service not found")
                return
            }

            ctrlChar = service.getCharacteristic(CTRL_UUID)
            dataChar = service.getCharacteristic(DATA_UUID)
            statusChar = service.getCharacteristic(STATUS_UUID)

            if (ctrlChar == null || dataChar == null || statusChar == null) {
                statusCallback.onTransferError("Missing characteristics")
                return
            }

            statusCallback.onStatus("Requesting MTU $TARGET_MTU...")
            gatt.requestMtu(TARGET_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            statusCallback.onStatus("MTU negotiated: $mtu. Enabling notifications...")
            statusChar?.let { enableNotifications(gatt, it) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cccdWriteComplete.set(true)
                    statusCallback.onStatus("STATUS notifications enabled")
                } else {
                    statusCallback.onTransferError("CCCD write failed: $status")
                }
            }
        }

        private fun handleStatusNotification(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != STATUS_UUID) return
            if (value.isEmpty()) return
            val code = value[0].toInt() and 0xFF
            Log.i(TAG, "STATUS notification code=0x${code.toString(16).padStart(2, '0')} state=$transferState")

            // Notifications in IDLE are ignored.
            if (transferState == TransferState.IDLE) {
                return
            }

            when {
                // STATUS 0x01 while WAIT_RESULT -> keep waiting
                code == 0x01 && transferState == TransferState.WAIT_RESULT -> {
                    statusCallback.onStatus("Rendering...")
                }

                // STATUS 0x00 while WAIT_RESULT -> success
                // Also accept 0x00 arriving in SENDING (race with COMMIT/CLEAR ack)
                code == 0x00 && (transferState == TransferState.WAIT_RESULT || transferState == TransferState.SENDING) -> {
                    cancelTimeout()
                    transferState = TransferState.IDLE
                    statusCallback.onTransferComplete()
                }

                // any code >= 0xE0 while SENDING or WAIT_RESULT -> error
                code >= 0xE0 -> {
                    cancelTimeout()
                    transferState = TransferState.IDLE
                    statusCallback.onTransferError("Device error: 0x${code.toString(16)}")
                }

                else -> {
                    statusCallback.onStatus("STATUS: 0x${code.toString(16).padStart(2, '0')}")
                }
            }
        }

        @Deprecated("Used on Android 12L and older")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleStatusNotification(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleStatusNotification(characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cancelTimeout()
                transferState = TransferState.IDLE
                statusCallback.onTransferError("Write failed: $status")
                return
            }

            if (characteristic.uuid == CTRL_UUID) {
                if (lastCtrlCommand == 0x01) {
                    // BEGIN acknowledged, start sending first data chunk
                    statusCallback.onStatus("BEGIN ok. Sending chunks...")
                    chunkIndex = 0
                    if (pendingChunks.isNotEmpty()) {
                        writeData(pendingChunks[chunkIndex])
                    }
                } else if (lastCtrlCommand == 0x02 || lastCtrlCommand == 0x05) {
                    // A fast error/OK notification can arrive before this ATT
                    // write acknowledgement.  Its handler has already completed
                    // the operation and set IDLE; do not resurrect WAIT_RESULT
                    // with no timeout running.
                    if (transferState != TransferState.SENDING) return
                    // after COMMIT write ack state=WAIT_RESULT
                    statusCallback.onStatus("COMMIT sent. Waiting for result...")
                    transferState = TransferState.WAIT_RESULT
                } else if (lastCtrlCommand == 0x03) {
                    if (transferState != TransferState.SENDING) return
                    // after CLEAR write ack state=WAIT_RESULT
                    statusCallback.onStatus("CLEAR sent. Waiting for result...")
                    transferState = TransferState.WAIT_RESULT
                } else if (lastCtrlCommand == 0x04) {
                    if (transferState != TransferState.SENDING) return
                    // after REDRAW_BG write ack state=WAIT_RESULT
                    statusCallback.onStatus("REDRAW_BG sent. Waiting for result...")
                    transferState = TransferState.WAIT_RESULT
                }
            } else if (characteristic.uuid == DATA_UUID) {
                chunkIndex++
                if (chunkIndex < pendingChunks.size) {
                    writeData(pendingChunks[chunkIndex])
                } else {
                    statusCallback.onStatus("All chunks sent. Sending COMMIT...")
                    writeCtrl(commitPayload())
                }
            }
        }
    }
}
