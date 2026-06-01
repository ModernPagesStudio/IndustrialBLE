package com.industrialble.ui

import android.bluetooth.BluetoothAdapter
import android.os.ParcelUuid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.industrialble.discovery.AggressiveDiscoveryManager
import com.industrialble.l2cap.L2CAPConnectionManager
import com.industrialble.protocol.FrameReassemblyBuffer
import com.industrialble.protocol.ProtocolFrameBuilder
import com.industrialble.stress.NetworkEnvironmentSimulator
import com.industrialble.stress.PacketInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

// ─────────────────────────────────────────────────────────────────
// ESTADOS DE UI
// ─────────────────────────────────────────────────────────────────
data class AppUiState(
    // Bluetooth
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,

    // Server
    val serverListening: Boolean = false,
    val serverPsm: Int = -1,
    val serverConnections: Int = 0,

    // Client
    val clientConnections: List<L2CAPConnectionManager.ConnectionBrief> = emptyList(),
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,

    // Discovery
    val isScanning: Boolean = false,
    val isProbing: Boolean = false,
    val discoveredDevices: Set<String> = emptySet(),
    val pendingProbes: Int = 0,
    val scanError: String? = null,

    // Stress - Packet Injection
    val injectorRunning: Boolean = false,
    val injectionFramesSent: Long = 0,
    val injectionErrors: Long = 0,
    val burstRateHz: Long = 100,
    val burstDurationMs: Long = 5000L,

    // Stress - Simulated Connections
    val simulatedConnections: Int = 0,
    val isSimulating: Boolean = false,
    val targetSimulatedCount: Int = 10,
    val simulationServerAddress: String = "00:00:00:00:00:00",
    val simulationResult: String? = null,

    // Logs
    val logs: List<LogEntry> = emptyList(),
    val logFilter: String = "",

    // Config
    val targetServiceUuid: String = "0000FE95-0000-1000-8000-00805F9B34FB",
    val verificationPsm: Int = 0x0101,
    val clientConnectAddress: String = "",
    val clientConnectPsm: Int = 0x0101
)

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
)

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

// ─────────────────────────────────────────────────────────────────
// VIEWMODEL
// ─────────────────────────────────────────────────────────────────
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    // Core components
    private var l2capManager: L2CAPConnectionManager? = null
    private var discoveryManager: AggressiveDiscoveryManager? = null
    private var packetInjector: PacketInjector? = null
    private var networkSimulator: NetworkEnvironmentSimulator? = null

    // Protocol
    private val frameBuilder = ProtocolFrameBuilder
    private val reassemblyBuffer = FrameReassemblyBuffer()

    init {
        // Iniciar monitoreo de estado
        viewModelScope.launch {
            while (isActive) {
                refreshStats()
                delay(500)
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────
    fun initialize(bluetoothAdapter: BluetoothAdapter?) {
        if (bluetoothAdapter == null) {
            addLog(LogLevel.ERROR, "System", "Bluetooth no disponible en este dispositivo")
            return
        }

        val state = _uiState.value

        l2capManager = L2CAPConnectionManager(bluetoothAdapter)
        discoveryManager = AggressiveDiscoveryManager(
            bluetoothAdapter = bluetoothAdapter,
            verificationManager = l2capManager!!,
            targetServiceUUID = ParcelUuid.fromString(state.targetServiceUuid),
            verificationPSM = state.verificationPsm
        )
        packetInjector = PacketInjector()
        networkSimulator = NetworkEnvironmentSimulator(state.verificationPsm)

        setupCallbacks()
        addLog(LogLevel.INFO, "System", "Módulos inicializados correctamente")
    }

    private fun setupCallbacks() {
        // Discovery callbacks
        discoveryManager?.onSensorDiscovered = { device, result ->
            addLog(LogLevel.INFO, "Discovery", "Sensor encontrado: ${device.address} (RSSI: ${result.rssi})")
        }
        discoveryManager?.onProbeComplete = { device, success ->
            val level = if (success) LogLevel.INFO else LogLevel.DEBUG
            val msg = if (success) "✓ Verificado: ${device.address}"
            else "✗ No responde: ${device.address}"
            addLog(level, "Probe", msg)
        }
        discoveryManager?.onScanError = { code ->
            addLog(LogLevel.ERROR, "Scan", "Error de escaneo: código $code")
        }
        discoveryManager?.onScanStateChanged = { scanning ->
            _uiState.update { it.copy(isScanning = scanning) }
        }

        // Los callbacks L2CAP se pasan inline en cada llamada a startServer/connectToServer
    }

    // ────────────────────────────────────────────────────────────
    // SERVIDOR L2CAP
    // ────────────────────────────────────────────────────────────
    fun startServer(psm: Int) {
        val manager = l2capManager ?: return
        manager.listenForIncomingConnections(psm, object : L2CAPConnectionManager.ConnectionCallback {
            override fun onConnected(socketInfo: L2CAPConnectionManager.SocketInfo) {
                addLog(LogLevel.INFO, "Server", "Cliente conectado: ${socketInfo.remoteDevice.address}")
            }
            override fun onDisconnected(socketInfo: L2CAPConnectionManager.SocketInfo, reason: String) {
                addLog(LogLevel.WARN, "Server", "Cliente desconectado: ${socketInfo.remoteDevice.address}")
            }
            override fun onError(socketInfo: L2CAPConnectionManager.SocketInfo?, error: String) {
                addLog(LogLevel.ERROR, "Server", "Error: $error")
            }
            override fun onDataReceived(socketInfo: L2CAPConnectionManager.SocketInfo, data: ByteArray) {
                reassemblyBuffer.feed(data)
                val frames = reassemblyBuffer.drain()
                frames.forEach { frame ->
                    addLog(LogLevel.DEBUG, "Server-RX",
                        "CMD=0x${frame.commandId.toString(16)} Len=${frame.payload.size}")
                }
            }
        })
        _uiState.update { it.copy(serverListening = true, serverPsm = psm) }
        addLog(LogLevel.INFO, "Server", "Servidor iniciado en PSM=$psm")
    }

    fun stopServer() {
        l2capManager?.stopListening()
        _uiState.update { it.copy(serverListening = false) }
        addLog(LogLevel.INFO, "Server", "Servidor detenido")
    }

    // ────────────────────────────────────────────────────────────
    // CLIENTE L2CAP
    // ────────────────────────────────────────────────────────────
    fun connectToServer(address: String, psm: Int) {
        val manager = l2capManager ?: return
        addLog(LogLevel.INFO, "Client", "Conectando a $address PSM=$psm...")
        manager.connectToServer(address, psm, object : L2CAPConnectionManager.ConnectionCallback {
            override fun onConnected(socketInfo: L2CAPConnectionManager.SocketInfo) {
                addLog(LogLevel.INFO, "Client", "Conectado a ${socketInfo.remoteDevice.address}")
            }
            override fun onDisconnected(socketInfo: L2CAPConnectionManager.SocketInfo, reason: String) {
                addLog(LogLevel.WARN, "Client", "Desconectado: $reason")
            }
            override fun onError(socketInfo: L2CAPConnectionManager.SocketInfo?, error: String) {
                addLog(LogLevel.ERROR, "Client", "Error: $error")
            }
            override fun onDataReceived(socketInfo: L2CAPConnectionManager.SocketInfo, data: ByteArray) {
                reassemblyBuffer.feed(data)
                val frames = reassemblyBuffer.drain()
                frames.forEach { frame ->
                    addLog(LogLevel.DEBUG, "Client-RX",
                        "CMD=0x${frame.commandId.toString(16)} Len=${frame.payload.size}")
                }
            }
        })
    }

    fun sendFrame(socketId: Long, commandId: Int, payload: ByteArray = ByteArray(0)) {
        val frame = frameBuilder.buildDataFrame(commandId, payload)
        l2capManager?.sendData(socketId, frame)
        addLog(LogLevel.DEBUG, "TX", "Enviado CMD=0x${commandId.toString(16)} (${frame.size}B)")
    }

    fun disconnectAll() {
        l2capManager?.disconnectAll()
        addLog(LogLevel.INFO, "Client", "Todas las conexiones cerradas")
    }

    // ────────────────────────────────────────────────────────────
    // DISCOVERY
    // ────────────────────────────────────────────────────────────
    fun startDiscovery() {
        discoveryManager?.startAggressiveScan()
        addLog(LogLevel.INFO, "Discovery", "Escaneo agresivo iniciado")
    }

    fun stopDiscovery() {
        discoveryManager?.stopScan()
        addLog(LogLevel.INFO, "Discovery", "Escaneo detenido")
    }

    // ────────────────────────────────────────────────────────────
    // PACKET INJECTOR
    // ────────────────────────────────────────────────────────────
    fun startInjection(rateHz: Long, durationMs: Long) {
        val manager = l2capManager ?: return
        val connections = manager.getActiveConnections()
        if (connections.isEmpty()) {
            addLog(LogLevel.WARN, "Injector", "No hay conexiones activas para inyectar")
            return
        }

        val frameData = frameBuilder.buildDataFrame(0x01, ByteArray(16))
        packetInjector?.startBurst(
            PacketInjector.BurstConfig(
                rateHz = rateHz,
                durationMs = durationMs,
                frameData = frameData,
                connections = connections
            )
        )
        _uiState.update { it.copy(injectorRunning = true) }
        addLog(LogLevel.INFO, "Injector", "Inyección iniciada: $rateHz tps, ${durationMs}ms")
    }

    fun stopInjection() {
        packetInjector?.stopBurst()
        _uiState.update { it.copy(injectorRunning = false) }
        addLog(LogLevel.INFO, "Injector", "Inyección detenida")
    }

    // ────────────────────────────────────────────────────────────
    // SIMULATED CONNECTIONS
    // ────────────────────────────────────────────────────────────
    fun scaleSimulatedConnections(targetCount: Int, serverAddress: String) {
        addLog(LogLevel.INFO, "Simulator", "Escalando a $targetCount conexiones simuladas...")
        viewModelScope.launch(Dispatchers.IO) {
            val result = networkSimulator?.scaleConnections(targetCount, serverAddress)
            _uiState.update {
                it.copy(
                    simulatedConnections = result?.establishedCount ?: 0,
                    isSimulating = false,
                    simulationResult = if (result?.success == true) "✓ $targetCount conexiones establecidas"
                    else "✗ Errores: ${result?.errors?.size ?: 0}"
                )
            }
            val msg = if (result?.success == true) "✓ Escalado completado: ${result.establishedCount} conexiones"
            else "✗ Escalado parcial: ${result?.establishedCount ?: 0} / ${result?.targetCount ?: targetCount}"
            addLog(if (result?.success == true) LogLevel.INFO else LogLevel.WARN, "Simulator", msg)
        }
    }

    fun stopSimulatedConnections() {
        networkSimulator?.stopAll()
        _uiState.update { it.copy(simulatedConnections = 0, isSimulating = false) }
        addLog(LogLevel.INFO, "Simulator", "Todas las conexiones simuladas cerradas")
    }

    // ────────────────────────────────────────────────────────────
    // UI UPDATES
    // ────────────────────────────────────────────────────────────
    fun updateConfig(
        serviceUuid: String? = null,
        verificationPsm: Int? = null,
        targetSimulatedCount: Int? = null,
        simulationAddress: String? = null,
        burstRate: Long? = null,
        burstDuration: Long? = null,
        clientAddress: String? = null,
        clientPsm: Int? = null
    ) {
        _uiState.update { state ->
            state.copy(
                targetServiceUuid = serviceUuid ?: state.targetServiceUuid,
                verificationPsm = verificationPsm ?: state.verificationPsm,
                targetSimulatedCount = targetSimulatedCount ?: state.targetSimulatedCount,
                simulationServerAddress = simulationAddress ?: state.simulationServerAddress,
                burstRateHz = burstRate ?: state.burstRateHz,
                burstDurationMs = burstDuration ?: state.burstDurationMs,
                clientConnectAddress = clientAddress ?: state.clientConnectAddress,
                clientConnectPsm = clientPsm ?: state.clientConnectPsm
            )
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun setLogFilter(filter: String) {
        _uiState.update { it.copy(logFilter = filter) }
    }

    // ────────────────────────────────────────────────────────────
    // STATS REFRESH
    // ────────────────────────────────────────────────────────────
    private fun refreshStats() {
        val manager = l2capManager ?: return
        val injector = packetInjector
        val discovery = discoveryManager
        val simulator = networkSimulator

        val stats = manager.getStats()
        val injectorStats = injector?.getStats()
        val discoveryStats = discovery?.getStats()
        val simStats = simulator?.getStats()

        _uiState.update { state ->
            state.copy(
                serverListening = stats.isListening,
                serverPsm = stats.serverPsm,
                serverConnections = stats.activeConnections,
                clientConnections = stats.connections,
                totalBytesSent = stats.totalBytesSent,
                totalBytesReceived = stats.totalBytesReceived,
                discoveredDevices = discovery?.discoveredDevices ?: emptySet(),
                isProbing = discoveryStats?.isProbing ?: false,
                pendingProbes = discoveryStats?.pendingProbes ?: 0,
                injectorRunning = injectorStats?.isRunning ?: false,
                injectionFramesSent = injectorStats?.totalFramesSent ?: 0,
                injectionErrors = injectorStats?.totalErrors ?: 0,
                simulatedConnections = simStats?.activeConnections ?: 0,
                isSimulating = simStats?.isSimulating ?: false
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // LOGGING
    // ────────────────────────────────────────────────────────────
    private fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        _uiState.update { state ->
            val newLogs = (state.logs + entry).takeLast(500)  // Máximo 500 entradas
            state.copy(logs = newLogs)
        }
    }

    // ────────────────────────────────────────────────────────────
    // CLEANUP
    // ────────────────────────────────────────────────────────────
    override fun onCleared() {
        super.onCleared()
        discoveryManager?.stopScan()
        packetInjector?.shutdown()
        networkSimulator?.shutdown()
        l2capManager?.shutdown()
    }
}
