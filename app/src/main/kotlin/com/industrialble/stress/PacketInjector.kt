package com.industrialble.stress

import android.util.Log
import com.industrialble.l2cap.L2CAPConnectionManager
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Motor de inyección de paquetes para pruebas de estrés del protocolo.
 *
 * Envía tramas por el OutputStream de múltiples conexiones activas
 * de forma asíncrona, no bloqueante, a una tasa configurable.
 * Ideal para pruebas de rendimiento y resistencia del servidor sensor.
 */
class PacketInjector(
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(),
        ThreadFactory { Thread(it, "packet-inject-${threadCounter.incrementAndGet()}") }
    )
) {
    companion object {
        private const val TAG = "PacketInjector"
        private const val DEFAULT_RATE_HZ = 1000L
        private val threadCounter = AtomicInteger(0)
    }

    private var scheduledTask: ScheduledFuture<*>? = null
    private val frameCounter = AtomicLong(0)
    private val errorCounter = AtomicLong(0)
    @Volatile
    private var burstStartTime: Long = 0L
    private var isRunning = false

    data class BurstStats(
        val totalFramesSent: Long,
        val totalErrors: Long,
        val elapsedMs: Long,
        val effectiveRateHz: Double,
        val isRunning: Boolean
    )

    data class BurstConfig(
        val rateHz: Long = DEFAULT_RATE_HZ,
        val durationMs: Long = -1L,    // -1 = hasta stop manual
        val frameData: ByteArray,
        val connections: List<L2CAPConnectionManager.SocketInfo>
    )

    // ────────────────────────────────────────────────────────────
    // BURST MODE
    // ────────────────────────────────────────────────────────────
    /**
     * Inicia una ráfaga de inyección de tramas a una tasa configurable.
     * Las tramas se envían a todas las conexiones activas en paralelo.
     */
    fun startBurst(config: BurstConfig): CompletableFuture<BurstStats> {
        stopBurst()
        isRunning = true
        frameCounter.set(0)
        errorCounter.set(0)

        val periodMs = maxOf(1000L / config.rateHz, 1L)
        val totalTargetFrames = if (config.durationMs > 0) {
            config.rateHz * config.durationMs / 1000L
        } else Long.MAX_VALUE

        burstStartTime = System.currentTimeMillis()
        val completionFuture = CompletableFuture<BurstStats>()

        scheduledTask = scheduler.scheduleAtFixedRate({
            val currentCount = frameCounter.incrementAndGet()
            if (currentCount > totalTargetFrames) {
                completionFuture.complete(getStats())
                stopBurst()
                return@scheduleAtFixedRate
            }

            // Enviar a TODAS las conexiones en paralelo
            config.connections.parallelStream().forEach { conn ->
                try {
                    conn.outputStream.write(config.frameData)
                    conn.outputStream.flush()
                } catch (e: IOException) {
                    errorCounter.incrementAndGet()
                    Log.w(TAG, "Error en conexión ${conn.remoteDevice.address}: ${e.message}")
                }
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS)

        // Si tiene duración finita, programar parada automática
        if (config.durationMs > 0) {
            scheduler.schedule({
                completionFuture.complete(getStats())
                stopBurst()
            }, config.durationMs, TimeUnit.MILLISECONDS)
        }

        return completionFuture
    }

    /**
     * Detiene la ráfaga actual.
     */
    fun stopBurst() {
        scheduledTask?.cancel(false)
        scheduledTask = null
        isRunning = false
    }

    // ────────────────────────────────────────────────────────────
    // SINGLE SHOT
    // ────────────────────────────────────────────────────────────
    /**
     * Inyecta una sola trama en una o más conexiones de forma asíncrona.
     */
    fun injectFrame(
        connections: List<L2CAPConnectionManager.SocketInfo>,
        frameData: ByteArray,
        callback: ((Boolean, Int) -> Unit)? = null
    ) {
        if (connections.isEmpty()) return

        scheduler.submit {
            var successCount = 0
            connections.parallelStream().forEach { conn ->
                try {
                    conn.outputStream.write(frameData)
                    conn.outputStream.flush()
                    successCount++
                    bytesSentCounter.addAndGet(frameData.size.toLong())
                } catch (e: IOException) {
                    Log.w(TAG, "Error en single shot: ${e.message}")
                }
            }
            callback?.invoke(successCount > 0, successCount)
        }
    }

    // ────────────────────────────────────────────────────────────
    // QUERIES
    // ────────────────────────────────────────────────────────────
    fun isRunning(): Boolean = isRunning

    fun getTotalFramesSent(): Long = frameCounter.get()

    fun getTotalErrors(): Long = errorCounter.get()

    fun getStats(): BurstStats {
        val elapsed = if (isRunning && burstStartTime > 0) {
            System.currentTimeMillis() - burstStartTime
        } else 0L
        val sent = frameCounter.get()
        return BurstStats(
            totalFramesSent = sent,
            totalErrors = errorCounter.get(),
            elapsedMs = elapsed,
            effectiveRateHz = if (elapsed > 0) sent * 1000.0 / elapsed else 0.0,
            isRunning = isRunning
        )
    }

    fun shutdown() {
        stopBurst()
        scheduler.shutdown()
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            scheduler.shutdownNow()
        }
    }

    companion object {
        private val bytesSentCounter = AtomicLong(0)
    }
}
