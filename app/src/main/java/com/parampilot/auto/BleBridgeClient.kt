package com.parampilot.auto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.security.interfaces.ECPublicKey
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Android client for the Galaxy Bluetooth bridge. Tunnels Galaxy HTTP API
 * requests over BLE to the comma-side bridge service (starpilot galaxy_bluetooth).
 *
 * Fully supports:
 *  - Automatic pairing claim (ECDH P-256 + HKDF-SHA256 + AES-GCM) with comma
 *    when comma's pairing claim window is open.
 *  - Automatic opening of comma's pairing window over LAN if comma is reachable via Wi-Fi.
 *  - Request fragmentation and flow-controlled read-stream reassembly on TX characteristic.
 *  - Full compatibility with Android 13+ (API 33+) Bluetooth GATT APIs.
 *
 * All public methods BLOCK — call them from a worker thread, never the UI thread.
 */
@Suppress("DEPRECATION")
class BleBridgeClient(
    private val context: Context,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "BleBridgeClient"
        private const val PREFS = "galaxy_ble"
        private const val KEY_PAIRING_HEX = "pairing_key_hex"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val clientMutex = Any()

        @Volatile private var sharedClient: BleBridgeClient? = null
        // Atomic, not guarded by clientMutex: startIfPaired runs on
        // the main thread (MainActivity's Bluetooth receiver) and
        // must never wait for that lock — connect()/request() hold
        // it for whole BLE operations (up to 15-30 s), and parking
        // the UI thread behind one froze the app (watchdog report
        // 2026-11-11: main thread blocked 5,991 ms in startIfPaired
        // while driving with the comma link down).
        private val isMaintainerRunning = AtomicBoolean(false)

        @Volatile
        var onConnectionStateChanged: ((Boolean) -> Unit)? = null

        @Synchronized
        fun getSharedClient(context: Context): BleBridgeClient {
            return sharedClient ?: BleBridgeClient(context.applicationContext).also { sharedClient = it }
        }

        fun isSharedClientConnected(): Boolean {
            val mode = NavStateManager.connectionMode
            if (mode == CommaClient.MODE_LOCAL || mode == CommaClient.MODE_CLOUD) return false
            return sharedClient?.isConnected() == true
        }

        fun getConnectedClient(): BleBridgeClient? {
            val mode = NavStateManager.connectionMode
            if (mode == CommaClient.MODE_LOCAL || mode == CommaClient.MODE_CLOUD) return null
            val c = sharedClient
            return if (c?.isConnected() == true) c else null
        }

        fun stopMaintainer() {
            // The flag flips synchronously (atomic): the maintainer
            // loop sees it and exits on its own. The disconnect and
            // client drop run on a worker thread — they wait on
            // clientMutex, which an in-flight connect()/request()
            // can hold for 15-30 s, and stopMaintainer is reached
            // from MainActivity.onStop via CommaClient detach/stop:
            // parking there froze the UI (watchdog report
            // 2026-11-11, vc28: main thread blocked 5,009 ms in
            // onStop -> stopMaintainer).
            isMaintainerRunning.set(false)
            Thread({
                synchronized(clientMutex) {
                    // A restart may have claimed the maintainer while
                    // this cleanup waited for the lock — then the
                    // shared client belongs to the new maintainer and
                    // must not be disconnected or dropped here.
                    if (!isMaintainerRunning.get()) {
                        try {
                            sharedClient?.disconnect()
                        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                        sharedClient = null
                        NavStateManager.setBleStatus("Off")
                    }
                }
            }, "ble-maintainer-stop").start()
        }

        fun startIfPaired(context: Context) {
            val mode = NavStateManager.connectionMode
            if (mode == CommaClient.MODE_LOCAL || mode == CommaClient.MODE_CLOUD) return
            if (!hasPairingKey(context)) return
            if (missingPermissions(context).isNotEmpty()) return
            val appContext = context.applicationContext
            if (!isMaintainerRunning.compareAndSet(false, true)) return
            Thread({
                Log.i(TAG, "Starting BLE connection maintainer thread")
                while (isMaintainerRunning.get()) {
                    try {
                        if (!hasPairingKey(appContext) || missingPermissions(appContext).isNotEmpty()) {
                            Thread.sleep(5000)
                            continue
                        }
                        val client = getSharedClient(appContext)
                        if (!client.isConnected()) {
                            try {
                                client.connect(15000)
                                Log.i(TAG, "BLE maintainer connected: session=${client.session}")
                            } catch (e: Exception) {
                                Log.w(TAG, "BLE maintainer connect attempt failed: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "BLE maintainer error: ${e.message}")
                    }
                    try {
                        Thread.sleep(5000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                Log.i(TAG, "BLE connection maintainer stopped")
            }, "ble-background-maintainer").start()
        }

        fun savedKeyHex(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAIRING_HEX, null)

        fun saveKeyHex(context: Context, hex: String?) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (hex.isNullOrBlank()) {
                prefs.edit().remove(KEY_PAIRING_HEX).apply()
            } else {
                prefs.edit().putString(KEY_PAIRING_HEX, hex.lowercase()).apply()
            }
        }

        fun hasPairingKey(context: Context): Boolean = !savedKeyHex(context).isNullOrBlank()

        /**
         * Attempts to pair and configure the 32-byte AES key from Comma:
         * 1. If reachable over Wi-Fi/LAN, opens Comma's 2-minute pairing window.
         * 2. Connects over BLE and claims the pairing key via ECDH.
         * 3. Saves the key in private SharedPreferences.
         */
        fun fetchAndSaveKeyFromComma(context: Context, candidateIp: String? = null): String? {
            val ip = candidateIp?.trim().takeIf { !it.isNullOrEmpty() && !it.contains("galaxy.firestar.link") }
                ?: context.getSharedPreferences("comma_client", Context.MODE_PRIVATE)
                    .getString("comma_ip", null)?.takeIf { !it.contains("galaxy.firestar.link") }
                ?: "192.168.68.16"

            val candidates = linkedSetOf(ip, "192.168.68.16", "192.168.68.14", "172.31.254.1", "192.168.43.1")
            for (cand in candidates) {
                try {
                    if (LanPairing.openPairingWindow(cand)) {
                        Log.i(TAG, "Opened pairing window on $cand")
                        break
                    }
                } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }

            return try {
                val client = getSharedClient(context)
                client.claimAndSaveKey()
            } catch (e: Exception) {
                Log.w(TAG, "fetchAndSaveKeyFromComma failed: ${e.message}")
                null
            }
        }

        /**
         * Checks if a Comma Bluetooth device is bonded or in range. If found and no
         * pairing key is currently saved, launches a background worker to claim and configure it.
         */
        @SuppressLint("MissingPermission")
        fun autoConfigureIfBonded(context: Context, candidateIp: String? = null) {
            val mode = NavStateManager.connectionMode
            if (mode == CommaClient.MODE_LOCAL || mode == CommaClient.MODE_CLOUD) return
            if (hasPairingKey(context)) return
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
            val adapter = manager.adapter ?: return
            if (!adapter.isEnabled) return

            val bondedDevices = try { adapter.bondedDevices } catch (_: SecurityException) { null } ?: return
            val hasComma = bondedDevices.any { dev ->
                dev.name?.contains("comma", ignoreCase = true) == true ||
                    dev.address.equals("39:90:21:64:07:00", ignoreCase = true)
            }
            if (hasComma) {
                Thread({
                    fetchAndSaveKeyFromComma(context, candidateIp)
                }, "comma-bt-autoconfig").start()
            }
        }

        /** Runtime permissions the BLE flow needs on this Android version. */
        fun missingPermissions(context: Context): List<String> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
            val needed = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            return needed.filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var infoChar: BluetoothGattCharacteristic? = null

    @Volatile private var session: String = ""
    @Volatile private var challenge: ByteArray = ByteArray(0)
    @Volatile private var connected: Boolean = false
    @Volatile private var mtu: Int = 23
    private var counter: Long = 0

    // One-shot latches completed by GATT callbacks.
    @Volatile private var connectLatch: CountDownLatch? = null
    @Volatile private var discoverLatch: CountDownLatch? = null
    @Volatile private var mtuLatch: CountDownLatch? = null
    @Volatile private var descLatch: CountDownLatch? = null
    @Volatile private var readLatch: CountDownLatch? = null
    @Volatile private var readValue: ByteArray? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var gattError: String? = null

    fun isConnected(): Boolean = connected && session.isNotEmpty()

    private fun status(msg: String) {
        NavStateManager.setBleStatus(msg)
        // Connection journal (2026-11-19): every BLE status passes
        // through here; record the connection transitions only.
        when {
            msg == "Connected" || msg.startsWith("Connected (") ->
                ConnectionJournal.record(ConnectionJournal.CAT_BLE, "BLE connected")
            msg == "Disconnected" ->
                ConnectionJournal.record(ConnectionJournal.CAT_BLE, "BLE disconnected")
            msg == "Off" ->
                ConnectionJournal.record(ConnectionJournal.CAT_BLE, "BLE off")
        }
        try { onStatus(msg) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        if (msg == "Connected") {
            try { onConnectionStateChanged?.invoke(true) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        } else if (msg == "Disconnected" || msg == "Off") {
            try { onConnectionStateChanged?.invoke(false) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun pairingKey(): ByteArray? {
        val hex = savedKeyHex(context) ?: return null
        return try { GalaxyBle.hexToBytes(hex) } catch (_: Exception) { null }
    }

    /**
     * Executes the ECDH claim protocol to pair with Comma and obtain a new 32-byte AES key.
     */
    fun claimAndSaveKey(timeoutMs: Long = 30000): String = synchronized(clientMutex) {
        status("Connecting for claim…")
        ensureGattConnection(timeoutMs)
        val chal = challenge.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No challenge received from Comma INFO characteristic")

        val keyPair = ClaimPairing.newKeyPair()
        val pubPoint = ClaimPairing.point(keyPair.public as ECPublicKey)
        val devName = Build.MODEL ?: "Pixel"
        val claimReq = ClaimPairing.request(pubPoint, devName)

        val frame = GalaxyBle.uint32be(claimReq.size) + claimReq
        val packets = GalaxyBle.packets(frame, mtu)

        status("Sending claim request…")
        for (p in packets) {
            writeSync(rxChar!!, p)
        }

        status("Waiting for Comma claim response…")
        val replyFrame = readResponse(timeoutMs = 25000)

        val claim = ClaimPairing.open(replyFrame, keyPair.private, chal)
        val keyHex = GalaxyBle.bytesToHex(claim.key)
        saveKeyHex(context, keyHex)
        NavStateManager.setBleStatus("Paired")
        status("Paired (${claim.adapterName.ifEmpty { "comma" }})")
        Log.i(TAG, "Successfully claimed pairing key (id=${claim.id})")
        return@synchronized keyHex
    }

    /** Blocking connect + bridge health check. Returns the health JSON. */
    fun connectAndHealth(timeoutMs: Long = 30000): JSONObject {
        connect(timeoutMs)
        val health = try {
            request("GET", "/_bridge/health", timeoutMs = 15000)
        } catch (e: Exception) {
            if (e is java.security.GeneralSecurityException || e.message?.contains("auth", true) == true || e.message?.contains("decrypt", true) == true) {
                Log.w(TAG, "Auth failure with current key: ${e.message}. Clearing key and re-claiming from Comma...")
                saveKeyHex(context, null)
                claimAndSaveKey(timeoutMs)
                request("GET", "/_bridge/health", timeoutMs = 15000)
            } else {
                throw e
            }
        }
        if (health.status != 200) {
            throw IllegalStateException("Bridge health check returned HTTP ${health.status}")
        }
        val json = health.bodyJson() ?: throw IllegalStateException("Bridge health response was not JSON")
        val streams = buildString {
            append("protocol ").append(json.optInt("protocol", 0))
            if (json.optBoolean("readStream")) append(" · read-stream")
            if (json.optBoolean("notificationStream")) append(" · notify")
        }
        status("Connected ($streams)")
        return json
    }

    /** Blocking connect: scan, GATT, MTU, INFO handshake. Auto-claims if no key is saved. */
    @SuppressLint("MissingPermission")
    fun connect(timeoutMs: Long = 30000) = synchronized(clientMutex) {
        if (isConnected() && hasPairingKey(context)) return@synchronized

        // If no pairing key is saved, try to open the claim window via LAN first
        if (!hasPairingKey(context)) {
            val savedIp = context.getSharedPreferences("comma_client", Context.MODE_PRIVATE)
                .getString("comma_ip", null)?.takeIf { !it.contains("galaxy.firestar.link") }
            val candidates = linkedSetOf(savedIp, "192.168.68.16", "192.168.68.14", "172.31.254.1", "192.168.43.1").filterNotNull()
            for (cand in candidates) {
                if (LanPairing.openPairingWindow(cand)) {
                    Log.i(TAG, "Opened pairing window on $cand")
                    break
                }
            }
        }

        ensureGattConnection(timeoutMs)

        if (!hasPairingKey(context)) {
            claimAndSaveKey(timeoutMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureGattConnection(timeoutMs: Long) {
        if (isConnected()) return

        val missing = missingPermissions(context)
        if (missing.isNotEmpty()) {
            throw SecurityException("Bluetooth permission not granted: ${missing.joinToString()}")
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IllegalStateException("Bluetooth is not available on this phone")
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth is not available on this phone")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")

        status("Scanning for Galaxy bridge…")
        val device = scanForBridge(adapter, minOf(timeoutMs, 6000))
            ?: throw IllegalStateException(
                "Galaxy Bluetooth bridge not found. The comma must be running the Galaxy Bluetooth " +
                    "build with pairing enabled, in range, and not already connected to another phone."
            )

        status("Connecting…")
        var g: BluetoothGatt? = null
        var lastErr: String? = null
        for (attempt in 1..2) {
            try {
                try { gatt?.disconnect() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                try { gatt?.close() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                gatt = null
                gattError = null
                connectLatch = CountDownLatch(1)
                discoverLatch = CountDownLatch(1)
                val transport = BluetoothDevice.TRANSPORT_LE
                Log.i(TAG, "Connecting GATT attempt $attempt to ${device.address} with TRANSPORT_LE (bondState=${device.bondState})")
                g = device.connectGatt(context, false, gattCallback, transport)
                gatt = g
                if (!connectLatch!!.await(minOf(timeoutMs, 10000), TimeUnit.MILLISECONDS)) {
                    throw TimeoutException("Timed out connecting to the Galaxy bridge")
                }
                gattError?.let { throw IllegalStateException(it) }
                if (!discoverLatch!!.await(minOf(timeoutMs, 12000), TimeUnit.MILLISECONDS)) {
                    throw TimeoutException("Timed out discovering Galaxy bridge services")
                }
                gattError?.let { throw IllegalStateException(it) }
                lastErr = null
                break
            } catch (e: Exception) {
                lastErr = e.message
                Log.w(TAG, "GATT connect attempt $attempt failed: ${e.message}")
                try { g?.disconnect() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                try { g?.close() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                gatt = null
                gattError = null
                if (attempt < 2) Thread.sleep(1200)
            }
        }
        if (lastErr != null) {
            disconnect()
            throw IllegalStateException(lastErr)
        }

        val activeGatt = g ?: run {
            disconnect()
            throw IllegalStateException(lastErr ?: "Connection failed")
        }
        val service = activeGatt.getService(UUID.fromString(GalaxyBle.SERVICE_UUID))
            ?: run { disconnect(); throw IllegalStateException("Galaxy Bluetooth service was not found") }
        rxChar = service.getCharacteristic(UUID.fromString(GalaxyBle.RX_UUID))
        txChar = service.getCharacteristic(UUID.fromString(GalaxyBle.TX_UUID))
        infoChar = service.getCharacteristic(UUID.fromString(GalaxyBle.INFO_UUID))
        if (rxChar == null || txChar == null || infoChar == null) {
            disconnect()
            throw IllegalStateException("The comma bridge has incompatible Bluetooth characteristics")
        }

        // Request MTU 517 (packet payloads up to 512 bytes)
        try {
            mtuLatch = CountDownLatch(1)
            if (activeGatt.requestMtu(517)) mtuLatch!!.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }

        // Enable notifications on NOTIFY_UUID if available
        val notifyChar = service.getCharacteristic(UUID.fromString(GalaxyBle.NOTIFY_UUID))
        if (notifyChar != null && (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            activeGatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                descLatch = CountDownLatch(1)
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activeGatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    activeGatt.writeDescriptor(cccd)
                }
                if (ok) descLatch!!.await(3000, TimeUnit.MILLISECONDS)
            }
        }

        // Session handshake: read INFO characteristic [version byte][16-byte session challenge]
        val info = readSync(infoChar!!)
        if (info.size < 17 || info[0].toInt() != 1) {
            disconnect()
            throw IllegalStateException("Invalid bridge session handshake (size=${info.size})")
        }
        challenge = info.copyOfRange(1, 17)
        session = GalaxyBle.bytesToHex(challenge)
        counter = 0
        connected = true
        status("Connected")
        // A bridge connection alone does NOT identify the app
        // transport (Param, 2026-11-04): only a heartbeat served over
        // this bridge does — see HeartbeatTransport.
        Log.i(TAG, "BLE connected successfully")
    }

    /** Blocking bridge request. One at a time; call from a worker thread. */
    fun request(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10000
    ): GalaxyBle.BridgeResponse = synchronized(clientMutex) {
        if (!isConnected()) {
            connect(timeoutMs)
        }
        var key = pairingKey()
        if (key == null) {
            claimAndSaveKey(timeoutMs)
            key = pairingKey() ?: throw IllegalStateException("Pairing key missing after claim")
        }

        val id = UUID.randomUUID().toString()
        counter += 1
        val myCounter = counter

        val requestJson = GalaxyBle.buildRequest(id, session, myCounter, path, method, headers, body)

        val frame = GalaxyBle.sealRequest(requestJson, key)
        val packets = GalaxyBle.packets(frame, mtu)

        for (p in packets) {
            writeSync(rxChar!!, p)
        }
        val responseFrame = readResponse(timeoutMs)
        val responseJson = GalaxyBle.openResponse(responseFrame, key)
        return@synchronized GalaxyBle.parseResponse(responseJson, id, session, myCounter)
    }

    /**
     * Reads TX characteristic in a polling loop, assembling incoming packets
     * and acknowledging with [2][seq] upon reassembly completion.
     */
    private fun readResponse(timeoutMs: Long): ByteArray {
        val assembler = GalaxyBle.FrameAssembler()
        val deadline = System.currentTimeMillis() + timeoutMs
        var emptyPollMs = 5L

        while (System.currentTimeMillis() < deadline) {
            val tx = txChar ?: throw IllegalStateException("Bluetooth disconnected during read")
            val packet = readSync(tx)
            if (packet.isEmpty() || (packet.size == 1 && packet[0].toInt() == 0)) {
                // Adaptive poll (audit finding 27): a fixed 30 ms spin
                // burned CPU in the bridge's mutex for the whole wait;
                // back off 5 ms -> 40 ms while no data is arriving.
                try { Thread.sleep(emptyPollMs) } catch (_: InterruptedException) {}
                emptyPollMs = (emptyPollMs * 2).coerceAtMost(40L)
                continue
            }
            val frame = assembler.append(packet)
            if (frame != null) {
                val rx = rxChar
                if (rx != null && packet.size >= 5) {
                    val ack = byteArrayOf(2) + packet.copyOfRange(1, 5)
                    try { writeSync(rx, ack) } catch (e: Exception) {
                        Log.w(TAG, "ACK write failed: ${e.message}")
                    }
                }
                return frame
            }
        }
        throw TimeoutException("Bluetooth response timed out on TX")
    }

    fun disconnect() {
        connected = false
        session = ""
        challenge = ByteArray(0)
        try { gatt?.disconnect() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        try { gatt?.close() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        gatt = null
        rxChar = null
        txChar = null
        infoChar = null
        status("Off")
    }

    @SuppressLint("MissingPermission")
    private fun scanForBridge(adapter: android.bluetooth.BluetoothAdapter, timeoutMs: Long): BluetoothDevice? {
        Log.i(TAG, "Starting BLE scan for Comma bridge...")
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            val latch = CountDownLatch(1)
            val foundRef = java.util.concurrent.atomic.AtomicReference<BluetoothDevice?>()
            val serviceUuid = ParcelUuid.fromString(GalaxyBle.SERVICE_UUID)

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val dev = result.device ?: return
                    val rec = result.scanRecord
                    val devName = dev.name ?: rec?.deviceName ?: ""
                    val hasUuid = rec?.serviceUuids?.contains(serviceUuid) == true
                    if (hasUuid || devName.contains("Galaxy", ignoreCase = true) ||
                        devName.contains("StarPilot", ignoreCase = true) ||
                        devName.contains("comma", ignoreCase = true) ||
                        dev.address.equals("39:90:21:64:07:00", ignoreCase = true)) {
                        Log.i(TAG, "Discovered Comma LE peripheral: ${dev.address} ($devName)")
                        if (foundRef.compareAndSet(null, dev)) {
                            latch.countDown()
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed with error code: $errorCode")
                    latch.countDown()
                }
            }

            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(serviceUuid).build(),
                ScanFilter.Builder().setDeviceAddress("39:90:21:64:07:00").build(),
                ScanFilter.Builder().setDeviceName("Galaxy").build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                scanner.startScan(filters, settings, callback)
                latch.await(minOf(timeoutMs, 6000), TimeUnit.MILLISECONDS)
            } finally {
                try { scanner.stopScan(callback) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
            val scanned = foundRef.get()
            if (scanned != null) return scanned
        }

        try {
            val bonded = adapter.bondedDevices
            val commaBond = bonded?.firstOrNull { dev ->
                dev.address.equals("39:90:21:64:07:00", ignoreCase = true) ||
                    dev.name?.contains("comma", ignoreCase = true) == true
            }
            if (commaBond != null) {
                Log.i(TAG, "Found bonded Comma device fallback: ${commaBond.address} (${commaBond.name})")
                return commaBond
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check bonded devices: ${e.message}")
        }
        return null
    }

    /** Synchronous characteristic write. */
    private fun writeSync(char: BluetoothGattCharacteristic, bytes: ByteArray) {
        synchronized(this) {
            val g = gatt ?: throw IllegalStateException("Bluetooth disconnected")
            val latch = CountDownLatch(1)
            writeLatch = latch
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
            if (!ok) {
                throw IllegalStateException("Bluetooth write failed to start")
            }
            if (!latch.await(8000, TimeUnit.MILLISECONDS)) {
                throw TimeoutException("Bluetooth write timed out")
            }
        }
    }

    /** Synchronous characteristic read. */
    private fun readSync(char: BluetoothGattCharacteristic): ByteArray {
        synchronized(this) {
            val g = gatt ?: throw IllegalStateException("Bluetooth disconnected")
            readValue = null
            val latch = CountDownLatch(1)
            readLatch = latch
            val ok = g.readCharacteristic(char)
            if (!ok) {
                throw IllegalStateException("Bluetooth read failed to start on ${char.uuid}")
            }
            if (!latch.await(6000, TimeUnit.MILLISECONDS)) {
                throw TimeoutException("Bluetooth read timed out on ${char.uuid}")
            }
            return readValue ?: ByteArray(0)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gattError = "Bluetooth connection failed (status $status)"
                connected = false
                session = ""
                challenge = ByteArray(0)
                status("Disconnected")
                connectLatch?.countDown()
                discoverLatch?.countDown()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectLatch?.countDown()
                try { g.discoverServices() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                session = ""
                challenge = ByteArray(0)
                status("Disconnected")
                connectLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gattError = "Bluetooth service discovery failed (status $status)"
            }
            discoverLatch?.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$newMtu, status=$status")
            mtu = newMtu
            mtuLatch?.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: desc=${descriptor.uuid}, status=$status")
            descLatch?.countDown()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readValue = value
            } else {
                Log.w(TAG, "onCharacteristicRead status=$status for char=${characteristic.uuid}")
            }
            readLatch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (readValue == null) {
                @Suppress("DEPRECATION")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    readValue = characteristic.value
                }
            }
            readLatch?.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeLatch?.countDown()
        }
    }
}
