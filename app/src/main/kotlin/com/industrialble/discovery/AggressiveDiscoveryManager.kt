package com.industrialble.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.industrialble.l2cap.L2CAPConnectionManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Escaneo BLE agresivo para encontrar sensores en entornos RF ruidosos.
 *
 * Combina escaneo continuo en baja latencia con "sondeo activo":
 * cuando se descubre un dispositivo con nuestro Service UUID, inmediatamente
 * intenta una conexión L2CAP rápida para verificar que sea un sensor real,
 * y la cierra. Esto permite "escuchar" más allá de los paquetes de anuncio.
 */
class AggressiveDiscoveryManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val verificationManager: L2CAPConnectionManager,
    private val targetServiceUUID: ParcelUuid,
    private val verificationPSM: Int
) {

    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner
    private var scanCallback: ScanCallback? = null
    private val pendingVerification = ConcurrentLinkedQueue<BluetoothDevice>()
    private val verificationExecutor = Executors.newSingleThreadExecutor(
        ThreadFactory { Thread(it, "active-probe-${probeCounter.incrementAndGet()}") }
    )
    private val discoveredDevices = mutableSetOf<String>()
    private val isScanning = AtomicBoolean(false)
    private val activeProbeRunning = AtomicBoolean(false)

    // Callbacks
    var onSensorDiscovered: ((BluetoothDevice, ScanResult) -> Unit)? = null
    var onScanError: ((Int) -> Unit)? = null
    var onProbeComplete: ((BluetoothDevice, Boolean) -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null

    data class DiscoveryStats(
        val isScanning: Boolean,
        val discoveredCount: Int,
        val pendingProbes: Int,
        val isProbing: Boolean
    )

    // ────────────────────────────────────────────────────────────
    // SCAN
    // ────────────────────────────────────────────────────────────
    fun startAggressiveScan() {
        if (isScanning.getAndSet(true)) return
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner no disponible")
            onScanError?.invoke(-1)
            return
        }

        synchronized(discoveredDevices) { discoveredDevices.clear() }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .apply {
                if (Build.VERSION.SDK_INT >= 26) {
                    setLegacy(false)
                }
            }
            .build()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(targetServiceUUID)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device != null) {
                    val alreadyKnown: Boolean
                    synchronized(discoveredDevices) {
                        alreadyKnown = discoveredDevices.contains(device.address)
                    }
                    if (!alreadyKnown) {
                        pendingVerification.add(device)
                        iniciarSondeoActivo()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan falló con código: $errorCode")
                onScanError?.invoke(errorCode)
                isScanning.set(false)
                onScanStateChanged?.invoke(false)
            }
        }

        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.i(TAG, "Escaneo agresivo iniciado (LOW_LATENCY)")
        onScanStateChanged?.invoke(true)
    }

    fun stopScan() {
        isScanning.set(false)
        try {
            scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo scan: ${e.message}")
        }
        scanCallback = null
        pendingVerification.clear()
        verificationExecutor.submit { /* drenar tareas pendientes */ }
        onScanStateChanged?.invoke(false)
    }

    // ────────────────────────────────────────────────────────────
    // ACTIVE PROBING
    // ────────────────────────────────────────────────────────────
    private fun iniciarSondeoActivo() {
        if (activeProbeRunning.getAndSet(true)) return

        verificationExecutor.submit {
            try {
                while (true) {
                    val device = pendingVerification.poll() ?: break

                    val alreadyKnown: Boolean
                    synchronized(discoveredDevices) {
                        alreadyKnown = discoveredDevices.contains(device.address)
                    }
                    if (alreadyKnown) continue

                    val isSensor = verifyDevice(device)

                    synchronized(discoveredDevices) {
                        if (isSensor) {
                            discoveredDevices.add(device.address)
                        }
                    }

                    onProbeComplete?.invoke(device, isSensor)
                    Thread.sleep(PROBE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                activeProbeRunning.set(false)
                if (pendingVerification.isNotEmpty()) {
                    iniciarSondeoActivo()
                }
            }
        }
    }

    /**
     * Intenta una conexión L2CAP rápida para verificar si el dispositivo
     * es un sensor real. Cierra la conexión inmediatamente.
     */
    private fun verifyDevice(device: BluetoothDevice): Boolean {
        return try {
            val socket = if (Build.VERSION.SDK_INT >= 34) {
                device.createL2capChannel(verificationPSM)
            } else {
                device.createL2capChannel(verificationPSM)
            }

            val future = CompletableFuture<Boolean>()
            verificationExecutor.submit {
                try {
                    socket.connect()
                    future.complete(true)
                } catch (e: Exception) {
                    future.complete(false)
                }
            }

            val connected = try {
                future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                false
            }

            if (connected) {
                Log.i(TAG, "✓ Sensor verificado: ${device.address}")
            } else {
                Log.d(TAG, "✗ ${device.address} no es un sensor")
            }

            try { socket.close() } catch (_: Exception) { }
            connected
        } catch (e: Exception) {
            Log.d(TAG, "✗ ${device.address} error: ${e.message}")
            false
        }
    }

    // ────────────────────────────────────────────────────────────
    // QUERIES
    // ────────────────────────────────────────────────────────────
    fun getDiscoveredDevices(): Set<String> =
        synchronized(discoveredDevices) { discoveredDevices.toSet() }

    fun getDiscoveredCount(): Int =
        synchronized(discoveredDevices) { discoveredDevices.size }

    fun isScanning(): Boolean = isScanning.get()

    fun isProbing(): Boolean = activeProbeRunning.get()

    fun getStats(): DiscoveryStats = DiscoveryStats(
        isScanning = isScanning.get(),
        discoveredCount = getDiscoveredCount(),
        pendingProbes = pendingVerification.size,
        isProbing = activeProbeRunning.get()
    )

    companion object {
        private const val TAG = "AggressiveDiscovery"
        private const val PROBE_TIMEOUT_MS = 2000L
        private const val PROBE_INTERVAL_MS = 50L
        private val probeCounter = AtomicInteger(0)
    }
}
