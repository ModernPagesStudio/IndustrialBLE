package com.industrialble.stress

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simulador de entorno de red para pruebas de estrés del servidor.
 *
 * Crea múltiples conexiones L2CAP falsas a un dispositivo servidor
 * sin completar el handshake del protocolo de aplicación.
 * Útil para encontrar el límite de conexiones simultáneas del sensor.
 */
class NetworkEnvironmentSimulator(
    private val targetPSM: Int
) {
    companion object {
        private const val TAG = "NetSimulator"
        private const val MAX_SIMULATED_CONNECTIONS = 200
        private const val KEEPALIVE_INTERVAL_S = 10L
        private val threadCounter = AtomicInteger(0)
    }

    private val simulationExecutor = Executors.newCachedThreadPool(
        ThreadFactory { Thread(it, "sim-conn-${threadCounter.incrementAndGet()}") }
    )
    private val keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { Thread(it, "sim-keepalive") }
    )
    private val simulatedConnections = CopyOnWriteArrayList<BluetoothSocket>()
    private val keepAliveTasks = CopyOnWriteArrayList<ScheduledFuture<*>>()
    private val isSimulating = AtomicBoolean(false)

    data class SimulationStats(
        val activeConnections: Int,
        val targetCount: Int,
        val isSimulating: Boolean,
        val serverAddress: String
    )

    // ────────────────────────────────────────────────────────────
    // SCALE CONNECTIONS
    // ────────────────────────────────────────────────────────────
    /**
     * Escala el número de conexiones falsas al servidor.
     * Puede aumentar o reducir según targetCount.
     */
    fun scaleConnections(
        targetCount: Int,
        serverAddress: String,
        timeoutSec: Long = 30L,
        onProgress: ((current: Int, target: Int) -> Unit)? = null
    ): SimulationResult {
        require(targetCount in 1..MAX_SIMULATED_CONNECTIONS) {
            "targetCount debe estar entre 1 y $MAX_SIMULATED_CONNECTIONS"
        }
        isSimulating.set(true)

        // Reducir si es necesario
        while (simulatedConnections.size > targetCount) {
            val socket = simulatedConnections.removeAt(simulatedConnections.size - 1)
            closeSocket(socket)
        }
        cancelKeepAlives()

        val currentCount = simulatedConnections.size
        if (currentCount >= targetCount) {
            isSimulating.set(false)
            restartKeepAlives()
            return SimulationResult(
                success = true,
                establishedCount = simulatedConnections.size,
                targetCount = targetCount,
                errors = emptyList()
            )
        }

        // Crear nuevas conexiones
        val connectionsToCreate = targetCount - currentCount
        val latch = CountDownLatch(connectionsToCreate)
        val errors = CopyOnWriteArrayList<String>()
        val successCount = AtomicInteger(0)

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            isSimulating.set(false)
            return SimulationResult(false, 0, targetCount, listOf("BluetoothAdapter no disponible"))
        }

        val device = try {
            adapter.getRemoteDevice(serverAddress)
        } catch (e: Exception) {
            isSimulating.set(false)
            return SimulationResult(false, 0, targetCount, listOf("Dirección MAC inválida: $serverAddress"))
        }

        repeat(connectionsToCreate) { index ->
            simulationExecutor.submit {
                try {
                    val socket = if (Build.VERSION.SDK_INT >= 34) {
                        device.createL2capChannel(targetPSM)
                    } else {
                        device.createL2capChannel(targetPSM)
                    }

                    socket.connect()
                    simulatedConnections.add(socket)

                    val succeeded = successCount.incrementAndGet()
                    onProgress?.invoke(succeeded, targetCount)
                    Log.d(TAG, "Conexión simulada #${index + 1} establecida")
                } catch (e: Exception) {
                    errors.add("Conexión #${index + 1}: ${e.message}")
                    Log.w(TAG, "Fallo #${index + 1}: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }

        // Esperar a que terminen
        try {
            latch.await(timeoutSec, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        restartKeepAlives()
        isSimulating.set(false)
        onProgress?.invoke(simulatedConnections.size, targetCount)

        return SimulationResult(
            success = errors.isEmpty(),
            establishedCount = simulatedConnections.size,
            targetCount = targetCount,
            errors = errors.toList()
        )
    }

    // ────────────────────────────────────────────────────────────
    // KEEP-ALIVE
    // ────────────────────────────────────────────────────────────
    /**
     * Envía bytes de relleno periódicamente para mantener las conexiones
     * activas sin activar el protocolo de aplicación.
     */
    private fun restartKeepAlives() {
        cancelKeepAlives()
        simulatedConnections.forEach { socket ->
            if (socket.isConnected) {
                val task = keepAliveScheduler.scheduleAtFixedRate({
                    try {
                        if (socket.isConnected) {
                            // Byte nulo: no es una trama válida del protocolo,
                            // el servidor lo ignorará como ruido
                            socket.outputStream.write(0x00)
                            socket.outputStream.flush()
                        }
                    } catch (_: Exception) { }
                }, KEEPALIVE_INTERVAL_S, KEEPALIVE_INTERVAL_S, TimeUnit.SECONDS)
                keepAliveTasks.add(task)
            }
        }
    }

    private fun cancelKeepAlives() {
        keepAliveTasks.forEach { it.cancel(false) }
        keepAliveTasks.clear()
    }

    // ────────────────────────────────────────────────────────────
    // STOP
    // ────────────────────────────────────────────────────────────
    fun stopAll() {
        isSimulating.set(false)
        cancelKeepAlives()
        simulatedConnections.forEach { closeSocket(it) }
        simulatedConnections.clear()
    }

    fun shutdown() {
        stopAll()
        keepAliveScheduler.shutdown()
        simulationExecutor.shutdown()
        try {
            keepAliveScheduler.awaitTermination(3, TimeUnit.SECONDS)
            simulationExecutor.awaitTermination(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            keepAliveScheduler.shutdownNow()
            simulationExecutor.shutdownNow()
        }
    }

    // ────────────────────────────────────────────────────────────
    // QUERIES
    // ────────────────────────────────────────────────────────────
    fun getActiveConnectionCount(): Int = simulatedConnections.size

    fun isSimulating(): Boolean = isSimulating.get()

    fun getStats(): SimulationStats {
        return SimulationStats(
            activeConnections = simulatedConnections.size,
            targetCount = simulatedConnections.size,
            isSimulating = isSimulating.get(),
            serverAddress = simulatedConnections.firstOrNull()?.remoteDevice?.address ?: ""
        )
    }

    private fun closeSocket(socket: BluetoothSocket) {
        try { if (socket.isConnected) socket.close() } catch (_: Exception) { }
    }

    data class SimulationResult(
        val success: Boolean,
        val establishedCount: Int,
        val targetCount: Int,
        val errors: List<String>
    )
}
