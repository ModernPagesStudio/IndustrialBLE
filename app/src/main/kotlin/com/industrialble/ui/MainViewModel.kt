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
import com.industrialble.updater.AutoUpdateManager
import com.industrialble.updater.GitHubReleaseChecker
import com.industrialble.updater.ReleaseInfo
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

    private val _wifiEnabled = MutableStateFlow(false)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _savedNetworks = MutableStateFlow<List<String>>(emptyList())
    val savedNetworks: StateFlow<List<String>> = _savedNetworks.asStateFlow()

    // WiFi Bruteforce
    private val _bruteForceProgress = MutableStateFlow(Triple(0, 0, ""))
    val bruteForceProgress: StateFlow<Triple<Int, Int, String>> = _bruteForceProgress.asStateFlow()

    private val _bruteForceRunning = MutableStateFlow(false)
    val bruteForceRunning: StateFlow<Boolean> = _bruteForceRunning.asStateFlow()

    private val _bruteForceFound = MutableStateFlow("")
    val bruteForceFound: StateFlow<String> = _bruteForceFound.asStateFlow()

    private val _bruteForceDelay = MutableStateFlow(1500) // ms entre intentos
    val bruteForceDelay: StateFlow<Int> = _bruteForceDelay.asStateFlow()

    // ===== WORDLIST GENERATOR =====
    private val _wordlist = MutableStateFlow<List<String>>(emptyList())
    val wordlist: StateFlow<List<String>> = _wordlist.asStateFlow()

    private val _wordlistSize = MutableStateFlow(0)
    val wordlistSize: StateFlow<Int> = _wordlistSize.asStateFlow()

    private val _wordlistGenerating = MutableStateFlow(false)
    val wordlistGenerating: StateFlow<Boolean> = _wordlistGenerating.asStateFlow()

    private val _maxWordlistSize = MutableStateFlow(50000)
    val maxWordlistSize: StateFlow<Int> = _maxWordlistSize.asStateFlow()

    private val _manualPasswords = MutableStateFlow<List<String>>(emptyList())
    val manualPasswords: StateFlow<List<String>> = _manualPasswords.asStateFlow()

    private val _wordlistSource = MutableStateFlow("generated") // "generated" o "manual" o "imported"
    val wordlistSource: StateFlow<String> = _wordlistSource.asStateFlow()

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

    // ===== AUTO-UPDATE =====
    private val githubReleaseChecker = GitHubReleaseChecker()
    private val autoUpdateManager = AutoUpdateManager(context)

    private val _updateInfo = MutableStateFlow<ReleaseInfo?>(null)
    val updateInfo: StateFlow<ReleaseInfo?> = _updateInfo.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(Pair(0L, 0L))
    val downloadProgress: StateFlow<Pair<Long, Long>> = _downloadProgress.asStateFlow()

    private var progressPollingJob: kotlinx.coroutines.Job? = null

    // ===== SYSTEM INFO =====
    private val _publicIp = MutableStateFlow("...")
    val publicIp: StateFlow<String> = _publicIp.asStateFlow()

    /** Inicializa la app y verifica actualizaciones automáticamente */
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

            // Auto-check de actualizaciones
            autoCheckForUpdates()
        }
    }

    /** Verifica actualizaciones automáticamente al iniciar */
    private suspend fun autoCheckForUpdates() {
        addLog("🔄 Verificando actualizaciones...")
        val info = withContext(Dispatchers.IO) {
            githubReleaseChecker.checkForUpdate("1.0.0")
        }
        _updateInfo.value = info

        if (info != null && info.downloadUrl.isNotBlank()) {
            addLog("📦 Actualización encontrada: ${info.latestVersion}")
            addLog("📥 Descargando automáticamente...")

            // Auto-descargar con barra de progreso
            _downloading.value = true
            _downloadProgress.value = Pair(0L, 0L)
            startProgressPolling()
            autoUpdateManager.downloadAndInstall(
                downloadUrl = info.downloadUrl,
                onProgress = { downloaded, total ->
                    _downloadProgress.value = Pair(downloaded, total)
                },
                onComplete = {
                    _downloading.value = false
                    progressPollingJob?.cancel()
                    addLog("✅ Descarga completada. Instalando...")
                }
            )
        } else {
            addLog("✅ Versión actualizada")
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
        _wifiEnabled.value = wifiHack.isWifiEnabled()
        if (!_wifiEnabled.value) {
            addLog("⚠️ WiFi apagado. Actívalo desde Ajustes.")
            _wifiScanRunning.value = false
            return
        }
        _wifiScanRunning.value = true
        _wifiNetworks.value = emptyList()
        addLog("📶 Escaneando redes WiFi...")

        viewModelScope.launch(Dispatchers.IO) {
            wifiHack.startScanWithFlow().collect { networks ->
                _wifiNetworks.value = networks
                _savedNetworks.value = wifiHack.getSavedNetworks()
                _wifiScanRunning.value = false
                if (networks.isEmpty()) {
                    addLog("⚠️ No se encontraron redes. ¿WiFi encendido? ¿Permiso de ubicación?")
                } else {
                    addLog("✅ Redes WiFi encontradas: ${networks.size}")
                }
            }
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
        addLog("🔑 Iniciando fuerza bruta contra '$ssid' (${passwords.size} passwords, delay ${_bruteForceDelay.value}ms)")

        wifiHack.bruteForce(
            ssid = ssid,
            passwords = passwords,
            delayMs = _bruteForceDelay.value,
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

    fun stopBruteForce() {
        wifiHack.cancelBruteForce()
        _bruteForceRunning.value = false
        addLog("⏹️ Fuerza bruta cancelada")
    }

    fun setBruteForceDelay(delay: Int) {
        _bruteForceDelay.value = delay
    }

    // ===== WORDLIST GENERATOR =====
    fun setMaxWordlistSize(size: Int) {
        _maxWordlistSize.value = size
    }

    fun generateWordlist(info: WordlistGenerator.PersonalInfo) {
        _wordlistGenerating.value = true
        _wordlistSource.value = "generated"
        addLog("🔑 Generando wordlist (max ${_maxWordlistSize.value})...")

        viewModelScope.launch(Dispatchers.Default) {
            val generator = WordlistGenerator()
            val words = generator.generateWithMutations(info, _maxWordlistSize.value)
            _wordlist.value = words
            _wordlistSize.value = words.size
            _wordlistGenerating.value = false
            addLog("✅ Wordlist generada: ${words.size} palabras")
        }
    }

    fun setManualPasswords(text: String) {
        val passwords = wifiHack.parseWordlistFromText(text)
        _manualPasswords.value = passwords
        _wordlist.value = passwords
        _wordlistSize.value = passwords.size
        _wordlistSource.value = "manual"
        addLog("📝 Wordlist manual: ${passwords.size} contraseñas")
    }

    fun importWordlist(text: String) {
        val passwords = wifiHack.parseWordlistFromText(text)
        if (passwords.isNotEmpty()) {
            _wordlist.value = passwords
            _wordlistSize.value = passwords.size
            _wordlistSource.value = "imported"
            addLog("📂 Wordlist importada: ${passwords.size} contraseñas")
        } else {
            addLog("❌ No se encontraron contraseñas válidas en el archivo")
        }
    }

    fun removePasswordFromWordlist(index: Int) {
        val current = _wordlist.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _wordlist.value = current
            _wordlistSize.value = current.size
            addLog("🗑️ Contraseña eliminada")
        }
    }

    fun clearWordlist() {
        _wordlist.value = emptyList()
        _wordlistSize.value = 0
        _manualPasswords.value = emptyList()
        addLog("🗑️ Wordlist limpiada")
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

    // ===== AUTO-UPDATE =====
    fun checkForUpdates() {
        _checkingUpdate.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val info = githubReleaseChecker.checkForUpdate("1.0.0")
            _updateInfo.value = info
            _checkingUpdate.value = false
            if (info != null && info.downloadUrl.isNotBlank()) {
                addLog("📦 Actualización disponible: ${info.latestVersion}")
            } else {
                addLog("ℹ️ No se pudo verificar actualizaciones")
            }
        }
    }

    fun downloadUpdate() {
        val info = _updateInfo.value ?: return
        if (info.downloadUrl.isBlank()) {
            addLog("❌ No hay URL de descarga disponible")
            return
        }
        _downloading.value = true
        _downloadProgress.value = Pair(0L, 0L)
        startProgressPolling()
        addLog("📥 Descargando actualización...")
        autoUpdateManager.downloadAndInstall(
            downloadUrl = info.downloadUrl,
            onProgress = { downloaded, total ->
                _downloadProgress.value = Pair(downloaded, total)
            },
            onComplete = {
                _downloading.value = false
                progressPollingJob?.cancel()
                addLog("✅ Descarga completada. Instalando...")
            }
        )
    }

    fun cancelDownload() {
        autoUpdateManager.cleanup()
        _downloading.value = false
        _downloadProgress.value = Pair(0L, 0L)
        progressPollingJob?.cancel()
        addLog("⏹️ Descarga cancelada")
    }

    private fun startProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = viewModelScope.launch {
            while (true) {
                delay(300)
                val progress = autoUpdateManager.getDownloadProgress()
                if (progress.second > 0) {
                    _downloadProgress.value = progress
                }
            }
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

    override fun onCleared() {
        super.onCleared()
        autoUpdateManager.cleanup()
    }
}
