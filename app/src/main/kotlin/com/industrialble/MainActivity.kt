package com.industrialble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.industrialble.ui.LogEntry
import com.industrialble.ui.LogLevel
import com.industrialble.ui.MainViewModel
import com.industrialble.ui.theme.IndustrialBLETheme
import com.industrialble.ui.theme.StatusBusy
import com.industrialble.ui.theme.StatusError
import com.industrialble.ui.theme.StatusOffline
import com.industrialble.ui.theme.StatusOnline
import com.industrialble.ui.theme.StatusScanning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        setContent {
            IndustrialBLETheme(darkTheme = true) {
                val viewModel: MainViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()
                val context = LocalContext.current

                // Inicializar Bluetooth
                LaunchedEffect(Unit) {
                    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
                    val adapter = bluetoothManager?.adapter
                    viewModel.initialize(adapter)
                }

                IndustrialBLEScaffold(viewModel = viewModel, uiState = uiState)
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }
}

// ─────────────────────────────────────────────────────────────────
// SCAFFOLD PRINCIPAL
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndustrialBLEScaffold(
    viewModel: MainViewModel,
    uiState: com.industrialble.ui.AppUiState
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "Discovery", "Stress Test", "Jamming", "Logs")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BluetoothConnected, contentDescription = null,
                            tint = if (uiState.bluetoothEnabled) StatusOnline else StatusOffline,
                            modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Industrial BLE", fontWeight = FontWeight.Bold)
                            Text(
                                if (uiState.serverListening) "Servidor activo • PSM=${uiState.serverPsm}"
                                else if (uiState.clientConnections.isNotEmpty()) "Cliente • ${uiState.clientConnections.size} conexiones"
                                else "Listo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Filled.Dashboard
                                    1 -> Icons.Filled.Radar
                                    2 -> Icons.Filled.Speed
                                    3 -> Icons.Filled.BluetoothDisabled
                                    4 -> Icons.Filled.Terminal
                                    else -> Icons.Filled.Circle
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title, fontSize = 10.sp) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> DashboardTab(viewModel, uiState)
                1 -> DiscoveryTab(viewModel, uiState)
                2 -> StressTestTab(viewModel, uiState)
                3 -> JammingTab(viewModel, uiState)
                4 -> LogsTab(viewModel, uiState)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════
// TAB 1: DASHBOARD
// ═════════════════════════════════════════════════════════════════
@Composable
fun DashboardTab(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Server Card
        item {
            ServerControlCard(viewModel, uiState)
        }

        // Client Card
        item {
            ClientControlCard(viewModel, uiState)
        }

        // Stats Card
        item {
            StatsCard(uiState)
        }

        // Quick Actions
        item {
            QuickActionsRow(viewModel, uiState)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun ServerControlCard(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    var psmInput by remember { mutableStateOf("257") } // 0x0101

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, contentDescription = null,
                    tint = if (uiState.serverListening) StatusOnline else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text("Servidor L2CAP", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                StatusIndicator(active = uiState.serverListening,
                    label = if (uiState.serverListening) "ACTIVO" else "INACTIVO")
            }

            Spacer(Modifier.height(12.dp))

            if (uiState.serverListening) {
                InfoRow("PSM", uiState.serverPsm.toString())
                InfoRow("Conexiones", uiState.serverConnections.toString())
                Button(
                    onClick = { viewModel.stopServer() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Detener Servidor") }
            } else {
                OutlinedTextField(
                    value = psmInput,
                    onValueChange = { psmInput = it.filter { c -> c.isDigit() } },
                    label = { Text("PSM") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.startServer(psmInput.toIntOrNull() ?: 0x0101) },
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Iniciar Servidor") }
            }
        }
    }
}

@Composable
fun ClientControlCard(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    var addressInput by remember { mutableStateOf("") }
    var psmInput by remember { mutableStateOf("257") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Devices, contentDescription = null,
                    tint = if (uiState.clientConnections.isNotEmpty()) StatusOnline else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text("Cliente L2CAP", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (uiState.clientConnections.isNotEmpty()) {
                    Text("${uiState.clientConnections.size} conexiones",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusOnline)
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                label = { Text("Dirección MAC") },
                placeholder = { Text("00:11:22:33:44:55") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = psmInput,
                    onValueChange = { psmInput = it.filter { c -> c.isDigit() } },
                    label = { Text("PSM") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val addr = addressInput.trim()
                        val psm = psmInput.toIntOrNull() ?: 0x0101
                        if (addr.isNotEmpty()) viewModel.connectToServer(addr, psm)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = addressInput.trim().isNotEmpty()
                ) { Text("Conectar") }
            }
            if (uiState.clientConnections.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Conexiones activas:", style = MaterialTheme.typography.labelMedium)
                uiState.clientConnections.take(3).forEach { conn ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Icon(Icons.Filled.Circle, contentDescription = null,
                            modifier = Modifier.size(8.dp), tint = StatusOnline)
                        Spacer(Modifier.width(8.dp))
                        Text("${conn.deviceAddress} (PSM=${conn.psm})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                if (uiState.clientConnections.size > 3) {
                    Text("... y ${uiState.clientConnections.size - 3} más",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnectAll() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Desconectar Todo") }
            }
        }
    }
}

@Composable
fun StatsCard(uiState: com.industrialble.ui.AppUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Estadísticas", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Enviados", formatBytes(uiState.totalBytesSent), Icons.Filled.Upload)
                StatItem("Recibidos", formatBytes(uiState.totalBytesReceived), Icons.Filled.Download)
                StatItem("Conectados", "${uiState.serverConnections + uiState.clientConnections.size}", Icons.Filled.Link)
                StatItem("Descubiertos", "${uiState.discoveredDevices.size}", Icons.Filled.Visibility)
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun JamStatItem(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun QuickActionsRow(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Acciones Rápidas", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    icon = Icons.Filled.Radar,
                    label = if (uiState.isScanning) "Detener" else "Escanear",
                    onClick = {
                        if (uiState.isScanning) viewModel.stopDiscovery()
                        else viewModel.startDiscovery()
                    },
                    color = if (uiState.isScanning) StatusScanning else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    icon = Icons.Filled.Speed,
                    label = "Inyectar",
                    onClick = {
                        viewModel.startInjection(uiState.burstRateHz, uiState.burstDurationMs)
                    },
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.clientConnections.isNotEmpty()
                )
                ActionButton(
                    icon = Icons.Filled.ClearAll,
                    label = "Limpiar Logs",
                    onClick = { viewModel.clearLogs() },
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 10.sp)
        }
    }
}

// ═════════════════════════════════════════════════════════════════
// TAB 2: DISCOVERY
// ═════════════════════════════════════════════════════════════════
@Composable
fun DiscoveryTab(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Scan Controls
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Radar, contentDescription = null,
                            tint = if (uiState.isScanning) StatusScanning else Color.Gray)
                        Spacer(Modifier.width(8.dp))
                        Text("Escaneo Agresivo", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        AnimatedVisibility(uiState.isScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = StatusScanning
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("ESCANEANDO", color = StatusScanning,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (uiState.isScanning) viewModel.stopDiscovery()
                                else viewModel.startDiscovery()
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (uiState.isScanning)
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            else ButtonDefaults.buttonColors()
                        ) {
                            Icon(
                                if (uiState.isScanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (uiState.isScanning) "Detener" else "Iniciar Escaneo")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        DiscoveryStat("Descubiertos", "${uiState.discoveredDevices.size}", Icons.Filled.Devices)
                        DiscoveryStat("Sondeo", if (uiState.isProbing) "ACTIVO" else "INACTIVO",
                            if (uiState.isProbing) Icons.Filled.WifiTethering else Icons.Filled.WifiOff)
                        DiscoveryStat("Pendientes", "${uiState.pendingProbes}", Icons.Filled.HourglassEmpty)
                    }
                }
            }
        }

        // Discovered Devices List
        item {
            Text("Dispositivos Descubiertos",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        if (uiState.discoveredDevices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Radar, contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text("Inicia el escaneo para descubrir sensores",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        } else {
            items(uiState.discoveredDevices.toList()) { device ->
                DeviceCard(device)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun DiscoveryStat(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DeviceCard(address: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* Expand details */ },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.DeviceHub, contentDescription = null,
                tint = StatusOnline)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(address, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text("Sensor industrial verificado",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.CheckCircle, contentDescription = "Verificado",
                tint = StatusOnline, modifier = Modifier.size(20.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════
// TAB 3: STRESS TEST
// ═════════════════════════════════════════════════════════════════
@Composable
fun StressTestTab(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Packet Injection
        item {
            PacketInjectionCard(viewModel, uiState)
        }

        // Simulated Connections
        item {
            SimulatedConnectionsCard(viewModel, uiState)
        }

        // Test Frame Builder
        item {
            FrameBuilderCard(viewModel, uiState)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun PacketInjectionCard(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    var rateInput by remember { mutableStateOf("100") }
    var durationInput by remember { mutableStateOf("5000") }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, contentDescription = null,
                    tint = if (uiState.injectorRunning) StatusBusy else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text("Inyector de Paquetes", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                StatusIndicator(active = uiState.injectorRunning,
                    label = if (uiState.injectorRunning) "INYECTANDO" else "DETENIDO")
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rateInput,
                    onValueChange = { rateInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Tasa (tps)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = durationInput,
                    onValueChange = { durationInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Duración (ms)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(8.dp))

            if (uiState.injectorRunning) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("Enviados: ${uiState.injectionFramesSent}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Errores: ${uiState.injectionErrors}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.injectionErrors > 0) StatusError else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.stopInjection() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Detener Inyección") }
            } else {
                Button(
                    onClick = {
                        viewModel.startInjection(
                            rateInput.toLongOrNull() ?: 100L,
                            durationInput.toLongOrNull() ?: 5000L
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.clientConnections.isNotEmpty()
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Iniciar Inyección en Ráfaga") }
                if (uiState.clientConnections.isEmpty()) {
                    Text("Requiere conexiones activas (tab Dashboard)",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusBusy)
                }
            }
        }
    }
}

@Composable
fun SimulatedConnectionsCard(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    var countInput by remember { mutableStateOf("10") }
    var addressInput by remember { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Dns, contentDescription = null,
                    tint = if (uiState.isSimulating) StatusBusy else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text("Simulador de Red", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("${uiState.simulatedConnections} conexiones",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.simulatedConnections > 0) StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                label = { Text("Dirección MAC del servidor") },
                placeholder = { Text("00:11:22:33:44:55") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = countInput,
                    onValueChange = { countInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Conexiones") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        viewModel.scaleSimulatedConnections(
                            countInput.toIntOrNull() ?: 10,
                            addressInput.trim()
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = addressInput.trim().isNotEmpty()
                ) { Text("Escalar") }
            }

            uiState.simulationResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.startsWith("✓")) StatusOnline else StatusError)
            }

            if (uiState.simulatedConnections > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.stopSimulatedConnections() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Cerrar Conexiones Simuladas") }
            }
        }
    }
}

@Composable
fun FrameBuilderCard(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    var cmdIdInput by remember { mutableStateOf("01") }
    var payloadHexInput by remember { mutableStateOf("") }
    var builtFrameHex by remember { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Code, contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text("Constructor de Tramas", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmdIdInput,
                    onValueChange = { cmdIdInput = it.take(2).filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } },
                    label = { Text("Comando (hex)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    prefix = { Text("0x") }
                )
                OutlinedTextField(
                    value = payloadHexInput,
                    onValueChange = { payloadHexInput = it.filter { c -> c.isWhitespace() || c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } },
                    label = { Text("Payload (hex)") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val cmd = cmdIdInput.toIntOrNull(16) ?: 1
                    val payload = try {
                        if (payloadHexInput.isBlank()) ByteArray(0)
                        else payloadHexInput.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    } catch (_: Exception) { ByteArray(0) }
                    val frame = com.industrialble.protocol.ProtocolFrameBuilder.buildDataFrame(cmd, payload)
                    builtFrameHex = com.industrialble.protocol.ProtocolFrameBuilder.bytesToHex(frame)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Filled.Build, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Construir Trama") }
            if (builtFrameHex.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Trama construida:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(builtFrameHex,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════
// TAB 4: JAMMING — SATURACIÓN DE RADIO
// ═════════════════════════════════════════════════════════════════
@Composable
fun JammingTab(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    var showGuide by remember { mutableStateOf(false) }
    var targetInput by remember { mutableStateOf("") }

    // Diálogo de guía para novatos
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("🎯 Guía Rápida")
                }
            },
            text = {
                Column {
                    Text("¿Qué hace este botón?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Esta función satura el chip Bluetooth " +
                            "de tu teléfono al máximo, haciendo que " +
                            "el Bluetooth se vuelva inestable.",
                        style = MaterialTheme.typography.bodyMedium)

                    Spacer(Modifier.height(16.dp))
                    Text("📌 ¿Para qué sirve?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("Si tu parlante está conectado a este " +
                            "teléfono y activas la saturación, " +
                            "el audio se entrecortará o se " +
                            "desconectará. Así pruebas la " +
                            "resistencia del dispositivo.")

                    Spacer(Modifier.height(16.dp))
                    Text("⚡ ¿Cómo usarlo?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("1. Conecta tu parlante al teléfono\n" +
                            "2. Anota su dirección MAC (opcional)\n" +
                            "3. Pon la MAC en el campo de texto\n" +
                            "4. Toca el botón INICIAR JAM\n" +
                            "5. Escucha si el audio se interrumpe\n" +
                            "6. Toca el botón DETENER para salir")

                    Spacer(Modifier.height(16.dp))
                    Text("⚠️ ADVERTENCIA",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text("Esto NO es un jammer real. Solo satura tu " +
                            "propio chip Bluetooth. Úsalo solo en " +
                            "dispositivos que te pertenezcan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) {
                    Text("Entendido")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ BOTÓN PRINCIPAL JAM ═══
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isJamming)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Indicador de estado
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isJamming) MaterialTheme.colorScheme.error
                                else StatusOffline
                            )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (uiState.isJamming) "⚠️ SATURANDO RADIO" else "MODO REPOSO",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.isJamming) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))

                    // ═══ BOTÓN GRANDE JAM ═══
                    Button(
                        onClick = {
                            if (uiState.isJamming) {
                                viewModel.stopJamming()
                            } else {
                                viewModel.startJamming(targetInput.trim())
                            }
                        },
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isJamming)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 8.dp,
                            pressedElevation = 16.dp
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (uiState.isJamming) Icons.Filled.Stop
                                else Icons.Filled.BluetoothDisabled,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (uiState.isJamming) "DETENER" else "INICIAR",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (uiState.isJamming) "JAM" else "JAM",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Dirección MAC opcional
                    OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        label = { Text("Dirección MAC del objetivo (opcional)") },
                        placeholder = { Text("00:11:22:33:44:55") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !uiState.isJamming,
                        leadingIcon = {
                            Icon(Icons.Filled.MyLocation, contentDescription = null)
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // Botón de guía
                    OutlinedButton(
                        onClick = { showGuide = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Filled.Help, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("📖 Guía para novatos")
                    }
                }
            }
        }

        // ═══ ESTADÍSTICAS ═══
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📊 Estadísticas de Saturación",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        JamStatItem(
                            "Tiempo",
                            "${uiState.jamElapsedSeconds}s",
                            Icons.Filled.Timer
                        )
                        JamStatItem(
                            "Ciclos",
                            "${uiState.jamCycles}",
                            Icons.Filled.Repeat
                        )
                        JamStatItem(
                            "Descubiertos",
                            "${uiState.discoveredBtDevices.size}",
                            Icons.Filled.Visibility
                        )
                    }

                    if (uiState.isJamming) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("El chip Bluetooth está siendo saturado...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ═══ DISPOSITIVOS DESCUBIERTOS ═══
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DevicesOther, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Dispositivos Bluetooth Cercanos",
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (uiState.discoveredBtDevices.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.clearJamDevices() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = "Limpiar",
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (uiState.discoveredBtDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.BluetoothSearching,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                Spacer(Modifier.height(4.dp))
                                Text("Activa JAM para descubrir dispositivos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    } else {
                        uiState.discoveredBtDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { targetInput = device.substringBefore(" ") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Bluetooth, contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = StatusOnline)
                                Spacer(Modifier.width(8.dp))
                                Text(device,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f))
                                Text("tocar →",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // ═══ ADVERTENCIA ═══
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("⚠️ Solo para pruebas",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("Esta función NO es un jammer de RF real. " +
                                "Satura el chip Bluetooth de tu propio teléfono " +
                                "alternando escaneos a máxima velocidad. Efecto: " +
                                "el Bluetooth se vuelve inestable y las conexiones " +
                                "de audio pueden entrecortarse o caerse.\n\n" +
                                "Usa solo en dispositivos que te pertenezcan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ═════════════════════════════════════════════════════════════════
// TAB 5: LOGS
// ═════════════════════════════════════════════════════════════════
@Composable
fun LogsTab(viewModel: MainViewModel, uiState: com.industrialble.ui.AppUiState) {
    val listState = rememberLazyListState()
    var filterInput by remember { mutableStateOf("") }
    var logLevelFilter by remember { mutableStateOf<LogLevel?>(null) }

    val filteredLogs = uiState.logs.filter { log ->
        (logLevelFilter == null || log.level == logLevelFilter) &&
                (filterInput.isBlank() || log.message.contains(filterInput, ignoreCase = true) ||
                        log.tag.contains(filterInput, ignoreCase = true))
    }

    // Auto scroll
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Filter bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = filterInput,
                    onValueChange = { filterInput = it },
                    label = { Text("Filtrar logs...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filterInput.isNotEmpty()) {
                            IconButton(onClick = { filterInput = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Limpiar")
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogLevel.entries.forEach { level ->
                        FilterChip(
                            selected = logLevelFilter == level,
                            onClick = {
                                logLevelFilter = if (logLevelFilter == level) null else level
                            },
                            label = { Text(level.name, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor(level).copy(alpha = 0.2f),
                                selectedLabelColor = chipColor(level)
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Limpiar", fontSize = 11.sp)
                    }
                }
            }
        }

        // Logs list
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text(if (uiState.logs.isEmpty()) "No hay logs aún" else "Ningún log coincide con el filtro",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredLogs.reversed(), key = { "${it.timestamp}_${it.message}" }) { log ->
                    LogEntryRow(log)
                }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: LogEntry) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val color = chipColor(entry.level)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                dateFormat.format(Date(entry.timestamp)),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.width(72.dp)
            )
            Text(
                entry.level.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.width(48.dp)
            )
            Text(
                entry.tag,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = color.copy(alpha = 0.8f),
                modifier = Modifier.width(72.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun chipColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.primary
    LogLevel.INFO -> StatusOnline
    LogLevel.WARN -> StatusBusy
    LogLevel.ERROR -> StatusError
}

// ═════════════════════════════════════════════════════════════════
// COMPONENTES COMPARTIDOS
// ═════════════════════════════════════════════════════════════════
@Composable
fun StatusIndicator(active: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) StatusOnline else StatusOffline)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (active) StatusOnline else StatusOffline,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium)
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}

@Composable
fun HorizontalDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = 0.5.dp
    )
}
