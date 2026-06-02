package com.industrialble

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.industrialble.network.NetworkScanner
import com.industrialble.network.PortScanner
import com.industrialble.tools.WordlistGenerator
import com.industrialble.ui.MainViewModel
import com.industrialble.ui.theme.IndustrialBLETheme
import com.industrialble.updater.ReleaseInfo
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.industrialble.network.NetworkScanner
import com.industrialble.network.PortScanner
import com.industrialble.tools.WordlistGenerator
import com.industrialble.ui.MainViewModel
import com.industrialble.ui.theme.IndustrialBLETheme
import com.industrialble.updater.ReleaseInfo

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Solicitar permisos necesarios al iniciar la app
        requestAppPermissions()

        setContent {
            IndustrialBLETheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp()
                }
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf<String>()

        // Permisos de ubicación (necesarios para WiFi Scan)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Android 13+ permisos de notificaciones
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        // Android 12- permisos de bluetooth
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        // Almacenamiento (Android 10-12)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Filtrar solo los que NO están concedidos
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val rootStatus by viewModel.rootStatus.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initApp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("HackDroid", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("Security Toolkit", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    // Indicador de root
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (rootStatus.isRooted) Color(0xFF00C853)
                                else Color(0xFF757575)
                            )
                    )
                    Text(
                        if (rootStatus.isRooted) "ROOT" else "NO ROOT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (rootStatus.isRooted) Color(0xFF00C853) else Color(0xFF757575),
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomTab(0, "Red", Icons.Filled.DeviceHub, selectedTab) { selectedTab = it }
                    BottomTab(1, "WiFi", Icons.Filled.Wifi, selectedTab) { selectedTab = it }
                    BottomTab(2, "Wordlist", Icons.Filled.Lock, selectedTab) { selectedTab = it }
                    BottomTab(3, "Extra", Icons.Filled.Extension, selectedTab) { selectedTab = it }
                    BottomTab(4, "Ajustes", Icons.Filled.Settings, selectedTab) { selectedTab = it }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> NetworkTab(viewModel)
                1 -> WiFiTab(viewModel)
                2 -> WordlistTab(viewModel)
                3 -> ExtraTab(viewModel)
                4 -> AjustesTab(viewModel)
            }
        }
    }
}

@Composable
fun BottomTab(index: Int, label: String, icon: ImageVector, selectedTab: Int, onClick: (Int) -> Unit) {
    val isSelected = selectedTab == index
    val color = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .clickable { onClick(index) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = color
        )
    }
}

// ==================== NETWORK TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTab(viewModel: MainViewModel) {
    val devices by viewModel.networkDevices.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val gateway by viewModel.gateway.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val scanRunning by viewModel.scanRunning.collectAsState()
    val portResults by viewModel.portResults.collectAsState()
    val portScanRunning by viewModel.portScanRunning.collectAsState()
    val pingResults by viewModel.pingResults.collectAsState()

    var targetIp by remember { mutableStateOf("") }
    var showPortScanner by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Info de red
        item {
            InfoCard(
                title = "Mi Red",
                items = listOf(
                    "IP Local" to localIp,
                    "Gateway" to gateway,
                    "Dispositivos" to devices.size.toString()
                )
            )
        }

        // Botón escanear
        item {
            Button(
                onClick = { viewModel.startNetworkScan() },
                enabled = !scanRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (scanRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Escaneando ${scanProgress.first}/${scanProgress.second}...")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Escanear Red")
                }
            }
        }

        // Dispositivos encontrados
        if (devices.isNotEmpty()) {
            item {
                Text(
                    "📡 Dispositivos (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(devices) { device ->
                DeviceCard(device) { ip ->
                    targetIp = ip
                    showPortScanner = true
                }
            }
        }

        // Port Scanner
        if (showPortScanner && targetIp.isNotEmpty()) {
            item {
                PortScannerSection(
                    ip = targetIp,
                    viewModel = viewModel,
                    onClose = { showPortScanner = false }
                )
            }
        }

        // Ping tool
        item {
            PingSection(viewModel)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun InfoCard(title: String, items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: NetworkScanner.NetworkDevice, onScanPorts: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onScanPorts(device.ip) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00C853))
                )
                Spacer(Modifier.width(8.dp))
                Text(device.ip, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
            }
            if (device.mac.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("MAC: ${device.mac}", fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (device.vendor.isNotEmpty()) {
                Text("Vendor: ${device.vendor}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (device.hostname.isNotEmpty()) {
                Text("Host: ${device.hostname}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text("Tocar para escanear puertos →", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun PortScannerSection(ip: String, viewModel: MainViewModel, onClose: () -> Unit) {
    val portResults by viewModel.portResults.collectAsState()
    val portScanRunning by viewModel.portScanRunning.collectAsState()
    val portProgress by viewModel.portProgress.collectAsState()

    var quickScan by remember { mutableStateOf(true) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Dns, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Puertos en $ip", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                }
            }
            Spacer(Modifier.height(8.dp))

            Row {
                FilterChip(
                    selected = quickScan,
                    onClick = { quickScan = true },
                    label = { Text("Rápido") },
                    modifier = Modifier.padding(end = 8.dp)
                )
                FilterChip(
                    selected = !quickScan,
                    onClick = { quickScan = false },
                    label = { Text("Completo") }
                )
            }
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val ports = if (quickScan) PortScanner.TOP_20 else PortScanner.TOP_20 + (1..1024).toList()
                    viewModel.startPortScan(ip, ports)
                },
                enabled = !portScanRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (portScanRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Escaneando ${portProgress.first}/${portProgress.second}...")
                } else {
                    Text("Escanear Puertos")
                }
            }

            if (portResults.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                portResults.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${r.port}", fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                        Text(r.serviceName.ifEmpty { "Desconocido" },
                            modifier = Modifier.weight(1f))
                        Text("ABIERTO", color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PingSection(viewModel: MainViewModel) {
    val pingResults by viewModel.pingResults.collectAsState()
    var ip by remember { mutableStateOf("8.8.8.8") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("📡 Ping", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.pingHost(ip) })
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.pingHost(ip) }) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                }
            }
            if (pingResults.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row {
                    pingResults.forEachIndexed { index, rtt ->
                        Text(
                            if (rtt >= 0) "${rtt}ms" else "✗",
                            color = if (rtt >= 0) Color(0xFF00C853) else Color(0xFFD32F2F),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== WIFI TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiTab(viewModel: MainViewModel) {
    val networks by viewModel.wifiNetworks.collectAsState()
    val scanRunning by viewModel.wifiScanRunning.collectAsState()
    val bruteForceProgress by viewModel.bruteForceProgress.collectAsState()
    val bruteForceRunning by viewModel.bruteForceRunning.collectAsState()
    val bruteForceFound by viewModel.bruteForceFound.collectAsState()
    val wordlist by viewModel.wordlist.collectAsState()
    val savedNetworks by viewModel.savedNetworks.collectAsState()

    var selectedSsid by remember { mutableStateOf("") }
    var showBruteForce by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Botón escanear
        item {
            Button(
                onClick = { viewModel.scanWifi() },
                enabled = !scanRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (scanRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Escaneando...")
                } else {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Escanear Redes WiFi")
                }
            }
        }

        // Redes guardadas
        if (savedNetworks.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("📂 Redes Guardadas", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        savedNetworks.take(5).forEach { ssid ->
                            Text(ssid.removeSurrounding("\""), fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Lista de redes
        if (networks.isNotEmpty()) {
            item {
                Text("Redes encontradas (${networks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            items(networks) { network ->
                WifiNetworkCard(
                    network = network,
                    isSelected = selectedSsid == network.ssid,
                    onClick = {
                        selectedSsid = network.ssid
                        showBruteForce = true
                    }
                )
            }
        }

        // Brute Force
        if (showBruteForce && selectedSsid.isNotEmpty()) {
            item {
                BruteForceSection(
                    ssid = selectedSsid,
                    viewModel = viewModel,
                    wordlist = wordlist,
                    bruteForceProgress = bruteForceProgress,
                    bruteForceRunning = bruteForceRunning,
                    bruteForceFound = bruteForceFound,
                    onClose = {
                        showBruteForce = false
                        selectedSsid = ""
                    }
                )
            }
        }

        // Info
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("ℹ️ Información", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "La fuerza bruta WiFi funciona sin root usando WifiManager. " +
                                "Ve a la pestaña Wordlist para generar contraseñas primero.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun WifiNetworkCard(
    network: com.industrialble.network.WiFiHack.WiFiNetwork,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val signalColor = when {
        network.level >= -50 -> Color(0xFF00C853)
        network.level >= -70 -> Color(0xFFFFA000)
        else -> Color(0xFFD32F2F)
    }
    val bars = when {
        network.level >= -50 -> 4
        network.level >= -60 -> 3
        network.level >= -70 -> 2
        else -> 1
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Señal
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(40.dp)) {
                repeat(bars) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height((4 + it * 4).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(signalColor)
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    if (network.ssid.isNotEmpty()) network.ssid else "<Red Oculta>",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text(
                    "${network.encryption} · Ch ${network.channel} · ${network.frequency}MHz",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("${network.level}dBm", fontSize = 11.sp,
                color = signalColor, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun BruteForceSection(
    ssid: String,
    viewModel: MainViewModel,
    wordlist: List<String>,
    bruteForceProgress: Triple<Int, Int, String>,
    bruteForceRunning: Boolean,
    bruteForceFound: String,
    onClose: () -> Unit
) {
    val bruteForceDelay by viewModel.bruteForceDelay.collectAsState()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Fuerza Bruta: $ssid", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            Spacer(Modifier.height(8.dp))

            Text("Wordlist: ${wordlist.size} passwords", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (wordlist.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("⚠️ Genera una wordlist primero en la pestaña Wordlist",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))

            // ===== VELOCIDAD =====
            if (!bruteForceRunning) {
                Text("⚡ Velocidad: ${bruteForceDelay}ms entre intentos",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rápido", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = bruteForceDelay.toFloat(),
                        onValueChange = { viewModel.setBruteForceDelay(it.toInt()) },
                        valueRange = 500f..5000f,
                        steps = 8,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("Lento", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("500ms", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5s", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }

            // ===== BOTÓN PRINCIPAL =====
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!bruteForceRunning) {
                    Button(
                        onClick = { viewModel.startBruteForce(ssid, wordlist) },
                        enabled = wordlist.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.FlashOn, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Iniciar Ataque")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.stopBruteForce() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Cancelar")
                    }
                }
            }

            // ===== PROGRESO =====
            if (bruteForceRunning) {
                Spacer(Modifier.height(8.dp))

                // Barra de progreso
                val progress = if (bruteForceProgress.second > 0)
                    bruteForceProgress.first.toFloat() / bruteForceProgress.second.toFloat()
                else 0f
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )

                Spacer(Modifier.height(4.dp))

                // Info de progreso
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "🔑 Probando ${bruteForceProgress.first}/${bruteForceProgress.second}",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Password: \"${bruteForceProgress.third}\"",
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            color = Color(0xFF00FF41),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${((progress * 100).toInt())}% completado",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ===== CONTRASEÑA ENCONTRADA =====
            if (bruteForceFound.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1B5E20)
                    )
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎉 CONTRASEÑA ENCONTRADA!", fontWeight = FontWeight.Bold,
                            color = Color(0xFF69F0AE), fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(bruteForceFound, fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = Color.White)
                    }
                }
            }
        }
    }
}

// ==================== WORDLIST TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordlistTab(viewModel: MainViewModel) {
    val wordlistSize by viewModel.wordlistSize.collectAsState()
    val wordlist by viewModel.wordlist.collectAsState()
    val generating by viewModel.wordlistGenerating.collectAsState()
    val maxSize by viewModel.maxWordlistSize.collectAsState()
    val wordlistSource by viewModel.wordlistSource.collectAsState()
    val focusManager = LocalFocusManager.current

    var tab by remember { mutableIntStateOf(0) } // 0=generar, 1=manual, 2=importar
    var nombres by remember { mutableStateOf("") }
    var apellidos by remember { mutableStateOf("") }
    var apodo by remember { mutableStateOf("") }
    var fecha by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }
    var empresa by remember { mutableStateOf("") }
    var mascota by remember { mutableStateOf("") }
    var ciudad by remember { mutableStateOf("") }
    var palabrasAdicionales by remember { mutableStateOf("") }
    var manualText by remember { mutableStateOf("") }
    var showPasswordList by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🔑 Wordlist", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        Text("$wordlistSize contraseñas • Origen: $wordlistSource", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Tabs de método
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Generar") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Manual") })
            FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Importar") })
        }

        when (tab) {
            0 -> {
                // === GENERADOR ===
                OutlinedTextField(value = nombres, onValueChange = { nombres = it },
                    label = { Text("Nombres") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apellidos, onValueChange = { apellidos = it },
                    label = { Text("Apellidos") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apodo, onValueChange = { apodo = it },
                    label = { Text("Apodo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fecha, onValueChange = { fecha = it },
                    label = { Text("Fecha (DD/MM/AAAA)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = telefono, onValueChange = { telefono = it },
                    label = { Text("Teléfono") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = empresa, onValueChange = { empresa = it },
                    label = { Text("Empresa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mascota, onValueChange = { mascota = it },
                    label = { Text("Mascota") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ciudad, onValueChange = { ciudad = it },
                    label = { Text("Ciudad") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = palabrasAdicionales, onValueChange = { palabrasAdicionales = it },
                    label = { Text("Palabras extra (separadas por coma)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))

                // Slider de tamaño
                Text("Tamaño máximo: $maxSize", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = maxSize.toFloat(),
                    onValueChange = { viewModel.setMaxWordlistSize(it.toInt()) },
                    valueRange = 100f..500000f,
                    steps = 9
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("100", fontSize = 10.sp)
                    Text("500K", fontSize = 10.sp)
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val info = WordlistGenerator.PersonalInfo(
                            nombres = nombres, apellidos = apellidos, apodo = apodo,
                            fechaNacimiento = fecha, telefono = telefono, empresa = empresa,
                            mascota = mascota, ciudad = ciudad,
                            palabrasClave = palabrasAdicionales.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        )
                        viewModel.generateWordlist(info)
                    },
                    enabled = !generating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Generando...")
                    } else {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generar Wordlist")
                    }
                }
            }

            1 -> {
                // === MANUAL ===
                Text("Escribe una contraseña por línea:", fontSize = 13.sp)
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    label = { Text("Contraseñas (1 por línea)") },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setManualPasswords(manualText) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Usar")
                    }
                    Button(
                        onClick = { manualText = ""; viewModel.setManualPasswords("") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF616161)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Clear, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Limpiar")
                    }
                }
            }

            2 -> {
                // === IMPORTAR .TXT ===
                Text("Pega el contenido del archivo .txt aquí (una contraseña por línea):",
                    fontSize = 13.sp)
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    label = { Text("Contenido del .txt") },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                )
                Button(
                    onClick = { viewModel.importWordlist(manualText) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Importar Wordlist")
                }
            }
        }

        // Wordlist actual
        if (wordlistSize > 0) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📋 Wordlist: $wordlistSize contraseñas", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearWordlist() }) {
                            Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("Usa en la pestaña WiFi para fuerza bruta", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))

                    // Botón para mostrar/ocultar lista
                    TextButton(onClick = { showPasswordList = !showPasswordList }) {
                        Text(if (showPasswordList) "Ocultar lista" else "Ver/Editar lista")
                    }

                    if (showPasswordList) {
                        Divider()
                        Spacer(Modifier.height(4.dp))

                        // Input para agregar contraseña
                        var addPw by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            OutlinedTextField(
                                value = addPw,
                                onValueChange = { addPw = it },
                                placeholder = { Text("+ password", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).height(48.dp),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    viewModel.addPasswordToWordlist(addPw)
                                    addPw = ""
                                })
                            )
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    viewModel.addPasswordToWordlist(addPw)
                                    addPw = ""
                                },
                                modifier = Modifier.height(48.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Agregar", fontSize = 12.sp)
                            }
                        }

                        wordlist.take(100).forEachIndexed { index, pw ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}.", fontSize = 10.sp, modifier = Modifier.width(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(pw, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { viewModel.removePasswordFromWordlist(index) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Filled.RemoveCircleOutline, null,
                                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (wordlistSize > 100) {
                            Text("... y ${wordlistSize - 100} más", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ==================== EXTRA TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraTab(viewModel: MainViewModel) {
    val dnsResult by viewModel.dnsResult.collectAsState()
    val base64Result by viewModel.base64Result.collectAsState()
    val subnetResult by viewModel.subnetResult.collectAsState()
    val macVendor by viewModel.macVendor.collectAsState()
    val hashResult by viewModel.hashResult.collectAsState()
    val publicIp by viewModel.publicIp.collectAsState()

    var dnsInput by remember { mutableStateOf("") }
    var b64Input by remember { mutableStateOf("") }
    var subnetInput by remember { mutableStateOf("192.168.1.0/24") }
    var macInput by remember { mutableStateOf("") }
    var hashInput by remember { mutableStateOf("") }
    var b64Mode by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🧰 Herramientas Extra",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)

        // IP Pública
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Language, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("IP Pública:", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(publicIp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        // DNS Lookup
        ToolCard("🌐 DNS Lookup", "Resuelve nombres de dominio a direcciones IP. Útil para verificar si un sitio está activo y su IP.") {
            OutlinedTextField(value = dnsInput, onValueChange = { dnsInput = it },
                label = { Text("Hostname/IP") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.dnsLookup(dnsInput) }))
            Spacer(Modifier.height(4.dp))
            Button(onClick = { viewModel.dnsLookup(dnsInput) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Resolver DNS")
            }
            if (dnsResult.ips.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                dnsResult.ips.forEach { ip ->
                    Text("→ $ip", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }

        // MAC Vendor
        ToolCard("🔍 MAC Vendor", "Identifica el fabricante de un dispositivo por su dirección MAC. Ayuda a saber qué dispositivo encontraste en la red.") {
            OutlinedTextField(value = macInput, onValueChange = { macInput = it },
                label = { Text("MAC (ej: 00:11:22:33:44:55)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Button(onClick = { viewModel.lookupMac(macInput) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Buscar Vendor")
            }
            if (macVendor.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Vendor: $macVendor", fontWeight = FontWeight.Bold)
            }
        }

        // Subnet Calculator
        ToolCard("📐 Calculadora de Subred", "Calcula la dirección de red, broadcast, rango de hosts y máscara a partir de una IP y CIDR.") {
            OutlinedTextField(value = subnetInput, onValueChange = { subnetInput = it },
                label = { Text("IP/CIDR (ej: 192.168.1.0/24)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Button(onClick = { viewModel.calculateSubnet(subnetInput) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Calcular")
            }
            if (subnetResult.networkAddress.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column {
                    listOf(
                        "Red" to subnetResult.networkAddress,
                        "Broadcast" to subnetResult.broadcastAddress,
                        "Hosts" to subnetResult.totalHosts.toString(),
                        "Máscara" to subnetResult.netmask,
                        "Rango" to "${subnetResult.firstHost} - ${subnetResult.lastHost}",
                        "Tipo" to if (subnetResult.isPrivate) "Privada" else "Pública"
                    ).forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("$k: ", fontSize = 12.sp, modifier = Modifier.width(80.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Base64
        ToolCard("🔐 Base64", "Codifica o decodifica texto en Base64. Útil para leer tokens, datos ocultos o configuraciones.") {
            Row {
                FilterChip(selected = b64Mode, onClick = { b64Mode = true },
                    label = { Text("Encode") }, modifier = Modifier.padding(end = 8.dp))
                FilterChip(selected = !b64Mode, onClick = { b64Mode = false },
                    label = { Text("Decode") })
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(value = b64Input, onValueChange = { b64Input = it },
                label = { Text(if (b64Mode) "Texto a codificar" else "Base64 a decodificar") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                if (b64Mode) viewModel.base64Encode(b64Input)
                else viewModel.base64Decode(b64Input)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (b64Mode) "Codificar" else "Decodificar")
            }
            if (base64Result.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(base64Result, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }

        // Hashes
        ToolCard("🔒 Generar Hashes", "Genera hashes MD5, SHA-1 y SHA-256 de cualquier texto. Sirve para verificar integridad de archivos.") {
            OutlinedTextField(value = hashInput, onValueChange = { hashInput = it },
                label = { Text("Texto a hashear") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Button(onClick = { viewModel.generateHashes(hashInput) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Generar Hashes")
            }
            if (hashResult.md5.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("MD5:    ${hashResult.md5}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("SHA-1:  ${hashResult.sha1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("SHA256: ${hashResult.sha256}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ToolCard(title: String, description: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (description.isNotEmpty()) {
                Text(description, fontSize = 11.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ==================== AJUSTES TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AjustesTab(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val rootStatus by viewModel.rootStatus.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val checkingUpdate by viewModel.checkingUpdate.collectAsState()
    val downloading by viewModel.downloading.collectAsState()
    val publicIp by viewModel.publicIp.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("⚙️ Ajustes", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)

        // ===== AUTO-UPDATE =====
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Actualizaciones", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))

                // ===== BARRA DE PROGRESO =====
                if (downloading) {
                    val (downloaded, total) = downloadProgress
                    val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                    val progressPct = (progress * 100).toInt()
                    val downloadedMb = downloaded / (1024 * 1024)
                    val totalMb = total / (1024 * 1024)

                    Text("📥 Descargando... $progressPct%",
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("${downloadedMb}MB / ${totalMb}MB",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))

                    Button(
                        onClick = { viewModel.cancelDownload() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("❌ Cancelar descarga") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.checkForUpdates() },
                            enabled = !checkingUpdate,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (checkingUpdate) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (checkingUpdate) "Buscando..." else "🔍 Buscar")
                        }
                        if (updateInfo != null && updateInfo!!.downloadUrl.isNotBlank()) {
                            Button(
                                onClick = { viewModel.downloadUpdate() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📥 Descargar")
                            }
                        }
                    }
                }

                if (updateInfo != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Versión: ${updateInfo!!.latestVersion}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ===== GUÍA ROOT =====
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = if (rootStatus.isRooted) Color(0xFF00C853) else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("👑 Root", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(10.dp).clip(CircleShape)
                        .background(if (rootStatus.isRooted) Color(0xFF00C853) else Color(0xFF757575)))
                    Spacer(Modifier.width(4.dp))
                    Text(if (rootStatus.isRooted) "ROOT" else "NO ROOT", fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (rootStatus.isRooted) Color(0xFF00C853) else Color(0xFF757575))
                }
                Spacer(Modifier.height(8.dp))

                if (!rootStatus.isRooted) {
                    Text("📱 Root para Redmi Note 11 Pro 5G", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    val pasos = listOf(
                        "1. Desbloquea el bootloader en miui.com (espera 7-30 días)",
                        "2. Descarga e instala XiaomiTool V2 en tu PC",
                        "3. Habilita 'Depuración USB' y 'OEM Unlock' en Ajustes > Info",
                        "4. Conecta el teléfono a la PC y abre XiaomiTool",
                        "5. XiaomiTool flasheará TWRP y Magisk automáticamente",
                        "6. Al reiniciar, verás Magisk en el cajón de apps",
                        "7. ¡Root listo! Abre esta app y verás el indicador verde"
                    )
                    pasos.forEach { paso ->
                        Text(paso, fontSize = 12.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("⚠️ Rootear anula la garantía. Respeta las leyes locales.",
                        fontSize = 11.sp, color = Color(0xFFFFA000))
                } else {
                    Text("✅ ¡Dispositivo rooteado!\n${rootStatus.details}", fontSize = 12.sp,
                        color = Color(0xFF00C853))
                }
            }
        }

        // ===== ACERCA DE =====
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("ℹ️ Acerca de", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                InfoRow("App", "HackDroid v${com.industrialble.BuildConfig.VERSION_NAME}")
                InfoRow("IP Pública", publicIp)
                InfoRow("Package", "com.industrialble.app")
            }
        }

        // ===== CONSOLA DE LOGS =====
        Text("📋 Consola de Actividad", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { viewModel.clearLogs() }) {
                Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Limpiar")
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D1A))
        ) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Consola vacía", color = Color(0xFF666666), fontSize = 12.sp)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    items(logs) { log ->
                        Text(log, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = Color(0xFF00FF41), lineHeight = 16.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace)
    }
}
