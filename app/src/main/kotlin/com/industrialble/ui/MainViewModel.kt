package com.industrialble.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.industrialble.network.NetworkScanner
import com.industrialble.network.PortScanner
import com.industrialble.network.WiFiHack
import com.industrialble.tools.ExtraTools
import com.industrialble.tools.RootChecker
import com.industrialble.tools.WordlistGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val networkScanner = NetworkScanner(context)
    private val portScanner = PortScanner()
    private val wifiHack = WiFiHack(context)

    // ===== ROOT STATUS =====
    private val _rootStatus = MutableStateFlow(RootChecker.RootStatus())
    val rootStatus: StateFlow<RootChecker.RootStatus> = _rootStatus.asStateFlow()

    // ===== NETWORK SCANNER =====
    private val _networkDevices = MutableStateFlow<List<NetworkScanner.NetworkDevice>>(emptyList())
    val networkDevices: StateFlow<List<NetworkScanner.NetworkDevice>> = _networkDevices.asStateFlow()

    private val _localIp = MutableStateFlow("0.0.0.0")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _gateway = MutableStateFlow("0.0.0.0")
    val gateway: StateFlow<String> = _gateway.asStateFlow()

    private val _scanProgress = MutableStateFlow(Pair(0, 0))
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    private val _scanRunning = MutableStateFlow(false)
    val scanRunning: StateFlow<Boolean> = _scanRunning.asStateFlow()

    // ===== PORT SCANNER =====
    private val _portResults = MutableStateFlow<List<PortScanner.ScanResult>>(emptyList())
    val portResults: StateFlow<List<PortScanner.ScanResult>> = _portResults.asStateFlow()

    private val _portScanRunning = MutableStateFlow(false)
    val portScanRunning: StateFlow<Boolean> = _portScanRunning.asStateFlow()

    private val _portProgress = MutableStateFlow(Pair(0, 0))
    val portProgress: StateFlow<Pair<Int, Int>> = _portProgress.asStateFlow()

    // ===== WIFI =====
    private val _wifiNetworks = MutableStateFlow<List<WiFiHack.WiFiNetwork>>(emptyList())
    val wifiNetworks: StateFlow<List<WiFiHack.WiFiNetwork>> = _wifiNetworks.asStateFlow()

    private val _wifiScanRunning = MutableStateFlow(false)
    val wifiScanRunning: StateFlow<Boolean> = _wifiScanRunning.asStateFlow()

    private val _savedNetworks = MutableStateFlow<List<String>>(emptyList())
    val savedNetworks: StateFlow<List<String>> = _savedNetworks.asStateFlow()

    // WiFi Bruteforce
    private val _bruteForceProgress = MutableStateFlow(Triple(0, 0, ""))
    val bruteForceProgress: StateFlow<Triple<Int, Int, String>> = _bruteForceProgress.asStateFlow()

    private val _bruteForceRunning = MutableStateFlow(false)
    val bruteForceRunning: StateFlow<Boolean> = _bruteForceRunning.asStateFlow()

    private val _bruteForceFound = MutableStateFlow("")
    val bruteForceFound: StateFlow<String> = _bruteForceFound.asStateFlow()

    // ===== WORDLIST GENERATOR =====
    private val _wordlist = MutableStateFlow<List<String>>(emptyList())
    val wordlist: StateFlow<List<String>> = _wordlist.asStateFlow()

    private val _wordlistSize = MutableStateFlow(0)
    val wordlistSize: StateFlow<Int> = _wordlistSize.asStateFlow()

    private val _wordlistGenerating = MutableStateFlow(false)
    val wordlistGenerating: StateFlow<Boolean> = _wordlistGenerating.asStateFlow()

    // ===== EXTRA TOOLS =====
    private val _dnsResult = MutableStateFlow(ExtraTools.DnsResult())
    val dnsResult: StateFlow<ExtraTools.DnsResult> = _dnsResult.asStateFlow()

    private val _base64Result = MutableStateFlow("")
    val base64Result: StateFlow<String> = _base64Result.asStateFlow()

    private val _subnetResult = MutableStateFlow(ExtraTools.SubnetInfo())
    val subnetResult: StateFlow<ExtraTools.SubnetInfo> = _subnetResult.asStateFlow()

    private val _macVendor = MutableStateFlow("")
    val macVendor: StateFlow<String> = _macVendor.asStateFlow()

    private val _hashResult = MutableStateFlow(ExtraTools.HashResult())
    val hashResult: StateFlow<ExtraTools.HashResult> = _hashResult.asStateFlow()

    private val _pingResults = MutableStateFlow<List<Long>>(emptyList())
    val pingResults: StateFlow<List<Long>> = _pingResults.asStateFlow()

    // ===== LOGS =====
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // ===== SYSTEM INFO =====
    private val _publicIp = MutableStateFlow("...")
    val publicIp: StateFlow<String> = _publicIp.asStateFlow()

    /** Inicializa la app */
    fun initApp() {
        viewModelScope.launch {
            _rootStatus.value = RootChecker.check(context)
            val (ip, gw) = networkScanner.getNetworkInfo()
            _localIp.value = ip
            _gateway.value = gw

            // IP pública
            _publicIp.value = withContext(Dispatchers.IO) { ExtraTools.getPublicIp() }

            addLog("🔧 App iniciada")
            addLog("📡 IP local: $ip | Gateway: $gw")
            if (_rootStatus.value.isRooted) {
                addLog("✅ Root detectado: ${_rootStatus.value.details}")
            } else {
                addLog("ℹ️ Sin root: ${_rootStatus.value.details}")
            }
        }
    }

    // ===== NETWORK SCANNER =====
    fun startNetworkScan() {
        _scanRunning.value = true
        _networkDevices.value = emptyList()
        addLog("📡 Escaneando red...")

        viewModelScope.launch(Dispatchers.IO) {
            val devices = networkScanner.scanNetwork(
                subnet = _localIp.value.substringBeforeLast("."),
                progressCallback = { current, total ->
                    _scanProgress.value = Pair(current, total)
                }
            )
            _networkDevices.value = devices
            _scanRunning.value = false
            addLog("✅ Escaneo completado: ${devices.size} dispositivos encontrados")
        }
    }

    fun stopScan() {
        _scanRunning.value = false
        addLog("⏹️ Escaneo detenido")
    }

    // ===== PORT SCANNER =====
    fun startPortScan(ip: String, ports: List<Int> = PortScanner.TOP_20) {
        _portScanRunning.value = true
        _portResults.value = emptyList()
        addLog("🔍 Escaneando puertos en $ip...")

        viewModelScope.launch(Dispatchers.IO) {
            val results = portScanner.scanPorts(
                ip = ip,
                ports = ports,
                timeoutMs = 300,
                onProgress = { current, total ->
                    _portProgress.value = Pair(current, total)
                }
            )
            val open = results.filter { it.isOpen }
            _portResults.value = open
            _portScanRunning.value = false
            addLog("✅ Escaneo de puertos: ${open.size} abiertos en $ip")
        }
    }

    // ===== WIFI =====
    fun scanWifi() {
        _wifiScanRunning.value = true
        _wifiNetworks.value = emptyList()
        addLog("📶 Escaneando redes WiFi...")

        wifiHack.startScan()

        viewModelScope.launch(Dispatchers.IO) {
            delay(2000) // Esperar a que el scan termine
            val networks = wifiHack.scanNetworks()
            _wifiNetworks.value = networks
            _savedNetworks.value = wifiHack.getSavedNetworks()
            _wifiScanRunning.value = false
            addLog("✅ Redes WiFi encontradas: ${networks.size}")
        }
    }

    // ===== WIFI BRUTEFORCE =====
    fun startBruteForce(ssid: String, passwords: List<String>) {
        if (passwords.isEmpty()) {
            addLog("⚠️ No hay contraseñas en la wordlist")
            return
        }
        _bruteForceRunning.value = true
        _bruteForceFound.value = ""
        addLog("🔑 Iniciando fuerza bruta contra '$ssid' (${passwords.size} passwords)")

        wifiHack.bruteForce(
            ssid = ssid,
            passwords = passwords,
            onProgress = { current, total, pass ->
                _bruteForceProgress.value = Triple(current, total, pass)
            },
            onFound = { password ->
                _bruteForceFound.value = password
                addLog("🎉 CONTRASEÑA ENCONTRADA: $password")
            },
            onFinish = { success ->
                _bruteForceRunning.value = false
                if (!success) addLog("❌ Fuerza bruta completada sin éxito")
            }
        )
    }

    // ===== WORDLIST GENERATOR =====
    fun generateWordlist(info: WordlistGenerator.PersonalInfo) {
        _wordlistGenerating.value = true
        addLog("🔑 Generando wordlist...")

        viewModelScope.launch(Dispatchers.Default) {
            val generator = WordlistGenerator()
            val words = generator.generateWithMutations(info, 50000)
            _wordlist.value = words
            _wordlistSize.value = words.size
            _wordlistGenerating.value = false
            addLog("✅ Wordlist generada: ${words.size} palabras")
        }
    }

    // ===== EXTRA TOOLS =====
    fun dnsLookup(hostname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = ExtraTools.dnsLookup(hostname)
            _dnsResult.value = result
            if (result.ips.isNotEmpty()) {
                addLog("🌐 DNS $hostname → ${result.ips.joinToString(", ")}")
            } else {
                addLog("❌ DNS lookup falló para $hostname")
            }
        }
    }

    fun base64Encode(input: String) {
        _base64Result.value = ExtraTools.base64Encode(input)
    }

    fun base64Decode(input: String) {
        _base64Result.value = ExtraTools.base64Decode(input)
    }

    fun calculateSubnet(ipCidr: String) {
        _subnetResult.value = ExtraTools.calculateSubnet(ipCidr)
    }

    fun lookupMac(mac: String) {
        _macVendor.value = ExtraTools.lookupMacVendor(mac)
    }

    fun generateHashes(input: String) {
        _hashResult.value = ExtraTools.generateHashes(input)
    }

    fun pingHost(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _pingResults.value = emptyList()
            addLog("📡 Ping a $ip...")
            val results = networkScanner.ping(ip, 4)
            _pingResults.value = results
            val success = results.count { it >= 0 }
            addLog("✅ Ping: $success/${results.size} respuestas")
        }
    }

    // ===== LOGS =====
    fun addLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$timestamp] $msg"
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
