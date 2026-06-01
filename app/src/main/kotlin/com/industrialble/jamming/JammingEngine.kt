package com.industrialble.jamming

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Motor de saturación Bluetooth.
 *
 * NO es un jammer RF real (imposible desde Android).
 * Lo que hace es saturar el chip Bluetooth del teléfono
 * alternando escaneos Classic y BLE a máxima velocidad,
 * forzando al radio a estar siempre ocupado.
 *
 * Efecto: interfiere con la capacidad del teléfono de mantener
 * conexiones Bluetooth estables (audio, datos).
 *
 * ⚠️ Solo para pruebas en dispositivos propios.
 */
class JammingEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) {

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        4,
        ThreadFactory { Thread(it, "jammer-${threadCounter.incrementAndGet()}") }
    )

    private val isActive = AtomicBoolean(false)
    private val jamCycles = AtomicLong(0)
    private val jamStartTime = AtomicLong(0)

    // Classic BT discovery
    private var isDiscovering = false

    // BLE scanner
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner
    private var scanCallback: ScanCallback? = null
    private var isBleScanning = false

    // Target address (optional)
    var targetAddress: String? = null
        private set

    // Discovered devices
    private val _discoveredDevices = CopyOnWriteArrayList<String>()
    val discoveredDevices: List<String> get() = _discoveredDevices.toList()

    // Discovery receiver
    private var discoveryReceiver: BroadcastReceiver? = null

    // Callbacks
    var onDeviceDiscovered: ((String, String?) -> Unit)? = null
    var onJamStateChanged: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    data class JamStats(
        val isActive: Boolean,
        val cycles: Long,
        val elapsedSeconds: Long,
        val devicesFound: Int
    )

    // ────────────────────────────────────────────────────────────
    // INICIAR / DETENER JAMMING
    // ────────────────────────────────────────────────────────────

    /**
     * Inicia la saturación del radio Bluetooth.
     * Alterna entre escaneo Classic y BLE a máxima velocidad.
     */
    fun start(targetMac: String? = null) {
        if (isActive.getAndSet(true)) return
        targetAddress = targetMac
        jamCycles.set(0)
        jamStartTime.set(System.currentTimeMillis())

        log("🚀 INICIANDO SATURACIÓN BLUETOOTH")
        if (targetMac != null) {
            log("🎯 Objetivo: $targetMac")
        }
        log("⚠️ El chip Bluetooth se saturará al 100%")

        // Registrar receiver para descubrimiento Classic
        registerDiscoveryReceiver()

        // Iniciar ciclos de escaneo
        scheduleNextClassicCycle()
        scheduleNextBleCycle()

        onJamStateChanged?.invoke(true)
    }

    /**
     * Detiene la saturación y limpia todo.
     */
    fun stop() {
        if (!isActive.getAndSet(false)) return
        log("🛑 DETENIENDO SATURACIÓN")

        scheduler.shutdownNow()
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }

        // Detener Classic discovery
        stopClassicDiscovery()

        // Detener BLE scan
        stopBleScan()

        // Limpiar receiver
        unregisterDiscoveryReceiver()

        val elapsed = (System.currentTimeMillis() - jamStartTime.get()) / 1000
        log("✅ Saturación detenida después de ${elapsed}s")
        log("📊 Ciclos completados: ${jamCycles.get()}")

        onJamStateChanged?.invoke(false)
    }

    /**
     * Obtiene estadísticas actuales.
     */
    fun getStats(): JamStats = JamStats(
        isActive = isActive.get(),
        cycles = jamCycles.get(),
        elapsedSeconds = if (isActive.get())
            (System.currentTimeMillis() - jamStartTime.get()) / 1000
        else 0L,
        devicesFound = _discoveredDevices.size
    )

    /**
     * Limpia la lista de dispositivos descubiertos.
     */
    fun clearDevices() {
        _discoveredDevices.clear()
    }

    fun shutdown() {
        stop()
    }

    // ────────────────────────────────────────────────────────────
    // CICLO CLASSIC BLUETOOTH
    // ────────────────────────────────────────────────────────────

    private fun scheduleNextClassicCycle() {
        if (!isActive.get()) return
        scheduler.schedule({
            if (!isActive.get()) return@schedule
            try {
                // Iniciar descubrimiento Classic
                if (!bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.startDiscovery()
                    isDiscovering = true
                }
                // Esperar 500ms y cancelar
                scheduler.schedule({
                    if (isActive.get()) {
                        stopClassicDiscovery()
                        jamCycles.incrementAndGet()
                        // Programar siguiente ciclo
                        scheduleNextClassicCycle()
                    }
                }, 500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                log("⚠️ Error en ciclo Classic: ${e.message}")
                if (isActive.get()) scheduleNextClassicCycle()
            }
        }, 50, TimeUnit.MILLISECONDS)
    }

    private fun stopClassicDiscovery() {
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (_: Exception) { }
        isDiscovering = false
    }

    // ────────────────────────────────────────────────────────────
    // CICLO BLE SCAN
    // ────────────────────────────────────────────────────────────

    private fun scheduleNextBleCycle() {
        if (!isActive.get()) return
        scheduler.schedule({
            if (!isActive.get()) return@schedule
            try {
                if (bluetoothLeScanner != null) {
                    val callback = createScanCallback()
                    scanCallback = callback
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0L)
                        .build()
                    bluetoothLeScanner.startScan(null, settings, callback)
                    isBleScanning = true
                }
                // Esperar 300ms y cancelar
                scheduler.schedule({
                    if (isActive.get()) {
                        stopBleScan()
                        scheduleNextBleCycle()
                    }
                }, 300, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (isActive.get()) scheduleNextBleCycle()
            }
        }, 30, TimeUnit.MILLISECONDS)
    }

    private fun stopBleScan() {
        try {
            scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
        } catch (_: Exception) { }
        scanCallback = null
        isBleScanning = false
    }

    private fun createScanCallback(): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (!_discoveredDevices.contains(device.address)) {
                _discoveredDevices.add(device.address)
                onDeviceDiscovered?.invoke(device.address, device.name)
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // DISCOVERY RECEIVER (Classic BT)
    // ────────────────────────────────────────────────────────────

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
            context.registerReceiver(discoveryReceiver, filter)
        } catch (e: Exception) {
            log("⚠️ Error registrando receiver: ${e.message}")
        }
    }

    private fun unregisterDiscoveryReceiver() {
        try {
            discoveryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
        discoveryReceiver = null
    }

    // ────────────────────────────────────────────────────────────
    // LOGGING
    // ────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    companion object {
        private const val TAG = "JammingEngine"
        private val threadCounter = AtomicLong(0)
    }
}
