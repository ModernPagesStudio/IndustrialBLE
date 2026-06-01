package com.industrialble.l2cap

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Gestor de conexiones L2CAP orientadas a conexión (CoC).
 *
 * Maneja el ciclo de vida completo de conexiones Bluetooth L2CAP,
 * tanto en modo servidor (listen) como cliente (connect).
 * Utiliza un pool de hilos para manejar múltiples conexiones simultáneas.
 *
 * Requiere API Level 29+ (Android 10).
 */
class L2CAPConnectionManager(
    private val bluetoothAdapter: BluetoothAdapter,
    connectionPoolSize: Int = Runtime.getRuntime().availableProcessors() * 2
) {
    companion object {
        private const val TAG = "L2CAPManager"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
    }

    // Pool de hilos para I/O de conexiones
    private val ioExecutor: ExecutorService = ThreadPoolExecutor(
        corePoolSize = connectionPoolSize,
        maximumPoolSize = connectionPoolSize * 2,
        keepAliveTime = 60L, TimeUnit.SECONDS,
        workQueue = LinkedBlockingQueue<Runnable>(),
        threadFactory = ThreadFactory {
            Thread(it, "l2cap-worker-${threadCounter.incrementAndGet()}")
        }
    )

    // ────────────────────────────────────────────────────────────
    // TYPES
    // ────────────────────────────────────────────────────────────
    data class SocketInfo(
        val socket: BluetoothSocket,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val remoteDevice: BluetoothDevice,
        val psm: Int,
        val connectedAt: Long = System.currentTimeMillis(),
        val id: Long = connectionIdCounter.incrementAndGet()
    )

    enum class ConnectionState {
        DISCONNECTED,
        LISTENING,
        CONNECTED,
        ERROR
    }

    interface ConnectionCallback {
        fun onConnected(socketInfo: SocketInfo)
        fun onDisconnected(socketInfo: SocketInfo, reason: String)
        fun onError(socketInfo: SocketInfo?, error: String)
        fun onDataReceived(socketInfo: SocketInfo, data: ByteArray)
    }

    // ────────────────────────────────────────────────────────────
    // STATE
    // ────────────────────────────────────────────────────────────
    private val activeConnections = ConcurrentHashMap<Long, SocketInfo>()
    private var serverSocket: BluetoothServerSocket? = null
    private val isListening = AtomicBoolean(false)
    private var serverPsm: Int = -1
    private var serverJob: Future<*>? = null
    private var connectionCallback: ConnectionCallback? = null

    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)

    // ────────────────────────────────────────────────────────────
    // SERVER MODE
    // ────────────────────────────────────────────────────────────
    fun listenForIncomingConnections(
        psm: Int,
        callback: ConnectionCallback? = null
    ) {
        if (isListening.getAndSet(true)) {
            Log.w(TAG, "Ya hay un servidor L2CAP escuchando")
            return
        }
        serverPsm = psm
        connectionCallback = callback

        serverJob = ioExecutor.submit {
            try {
                val tmp = if (Build.VERSION.SDK_INT >= 33) {
                    bluetoothAdapter.listenUsingL2capChannel(psm)
                } else {
                    bluetoothAdapter.listenUsingL2capChannel()
                }
                serverSocket = tmp
                Log.i(TAG, "Servidor L2CAP escuchando en PSM=$psm")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val clientSocket: BluetoothSocket = tmp.accept()
                        Log.i(TAG, "Conexión entrante: ${clientSocket.remoteDevice.address}")

                        handleNewConnection(clientSocket)
                    } catch (e: IOException) {
                        if (!Thread.currentThread().isInterrupted) {
                            Log.e(TAG, "Error en accept(): ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error creando server socket: ${e.message}")
                isListening.set(false)
                callback?.onError(null, "Error creando server socket: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isListening.set(false)
        serverJob?.cancel(true)
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
    }

    // ────────────────────────────────────────────────────────────
    // CLIENT MODE
    // ────────────────────────────────────────────────────────────
    fun connectToServer(
        deviceMac: String,
        psm: Int,
        callback: ConnectionCallback? = null
        ): CompletableFuture<SocketInfo> {
        val future = CompletableFuture<SocketInfo>()

        val device = bluetoothAdapter.getRemoteDevice(deviceMac)
        connectionCallback = callback

        ioExecutor.submit {
            try {
                val socket = device.createL2capChannel(psm)
                val connectFuture = CompletableFuture<BluetoothSocket>()

                ioExecutor.submit {
                    try {
                        socket.connect()
                        connectFuture.complete(socket)
                    } catch (e: Exception) {
                        connectFuture.completeExceptionally(e)
                    }
                }

                val connectedSocket = connectFuture.get(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val sockInfo = registerConnection(connectedSocket, psm)
                future.complete(sockInfo)
                callback?.onConnected(sockInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error conectando a $deviceMac PSM=$psm: ${e.message}")
                future.completeExceptionally(e)
                callback?.onError(null, "Error conectando: ${e.message}")
            }
        }

        return future
    }

    // ────────────────────────────────────────────────────────────
    // DATA I/O
    // ────────────────────────────────────────────────────────────
    fun sendData(socketId: Long, data: ByteArray) {
        val sockInfo = activeConnections[socketId] ?: return
        ioExecutor.submit {
            try {
                sockInfo.outputStream.write(data)
                sockInfo.outputStream.flush()
                bytesSent.addAndGet(data.size.toLong())
            } catch (e: IOException) {
                Log.e(TAG, "Error enviando datos: ${e.message}")
                connectionCallback?.onError(sockInfo, "Error de escritura: ${e.message}")
                disconnectSocket(socketId, "Error de escritura")
            }
        }
    }

    fun broadcastData(data: ByteArray) {
        val snapshot = activeConnections.values.toList()
        snapshot.forEach { sockInfo ->
            sendData(sockInfo.id, data)
        }
    }

    // ────────────────────────────────────────────────────────────
    // CONNECTION MANAGEMENT
    // ────────────────────────────────────────────────────────────
    private fun handleNewConnection(socket: BluetoothSocket) {
        try {
            val sockInfo = registerConnection(socket, serverPsm)
            connectionCallback?.onConnected(sockInfo)
        } catch (e: IOException) {
            Log.e(TAG, "Error registrando conexión: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun registerConnection(socket: BluetoothSocket, psm: Int): SocketInfo {
        val sockInfo = SocketInfo(
            socket = socket,
            inputStream = socket.inputStream,
            outputStream = socket.outputStream,
            remoteDevice = socket.remoteDevice,
            psm = psm
        )
        activeConnections[sockInfo.id] = sockInfo
        startReadLoop(sockInfo)
        return sockInfo
    }

    private fun startReadLoop(sockInfo: SocketInfo) {
        ioExecutor.submit {
            try {
                val buffer = ByteArray(1024)
                while (!Thread.currentThread().isInterrupted &&
                    sockInfo.socket.isConnected
                ) {
                    val bytesRead = sockInfo.inputStream.read(buffer)
                    if (bytesRead == -1) {
                        disconnectSocket(sockInfo.id, "Stream cerrado por el remoto")
                        break
                    }
                    val data = buffer.copyOfRange(0, bytesRead)
                    bytesReceived.addAndGet(bytesRead.toLong())
                    connectionCallback?.onDataReceived(sockInfo, data)
                }
            } catch (e: IOException) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "Error en read loop: ${e.message}")
                    connectionCallback?.onError(sockInfo, "Error de lectura: ${e.message}")
                }
            } finally {
                disconnectSocket(sockInfo.id, "Read loop terminado")
            }
        }
    }

    fun disconnectSocket(socketId: Long, reason: String = "Solicitado") {
        val sockInfo = activeConnections.remove(socketId) ?: return
        try { sockInfo.socket.close() } catch (_: IOException) { }
        connectionCallback?.onDisconnected(sockInfo, reason)
    }

    fun disconnectAll() {
        val keys = activeConnections.keys.toList()
        keys.forEach { disconnectSocket(it, "Desconexión masiva") }
    }

    fun shutdown() {
        stopListening()
        disconnectAll()
        ioExecutor.shutdown()
        try {
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            ioExecutor.shutdownNow()
        }
    }

    // ────────────────────────────────────────────────────────────
    // QUERIES
    // ────────────────────────────────────────────────────────────
    fun getActiveConnections(): List<SocketInfo> =
        activeConnections.values.toList()

    fun getActiveConnectionCount(): Int = activeConnections.size

    fun isServerListening(): Boolean = isListening.get()

    fun getServerPsm(): Int = serverPsm

    fun getBytesSent(): Long = bytesSent.get()

    fun getBytesReceived(): Long = bytesReceived.get()

    fun getStats(): ConnectionStats {
        val conns = activeConnections.values
        return ConnectionStats(
            activeConnections = conns.size,
            isListening = isListening.get(),
            serverPsm = if (isListening.get()) serverPsm else -1,
            totalBytesSent = bytesSent.get(),
            totalBytesReceived = bytesReceived.get(),
            connections = conns.map { it.toConnectionBrief() }
        )
    }

    data class ConnectionStats(
        val activeConnections: Int,
        val isListening: Boolean,
        val serverPsm: Int,
        val totalBytesSent: Long,
        val totalBytesReceived: Long,
        val connections: List<ConnectionBrief>
    )

    data class ConnectionBrief(
        val id: Long,
        val deviceAddress: String,
        val deviceName: String,
        val psm: Int,
        val uptimeMs: Long
    )

    private fun SocketInfo.toConnectionBrief() = ConnectionBrief(
        id = id,
        deviceAddress = remoteDevice.address,
        deviceName = remoteDevice.name ?: "Desconocido",
        psm = psm,
        uptimeMs = System.currentTimeMillis() - connectedAt
    )

    companion object {
        private val threadCounter = AtomicInteger(0)
        private val connectionIdCounter = AtomicLong(0)
    }
}
