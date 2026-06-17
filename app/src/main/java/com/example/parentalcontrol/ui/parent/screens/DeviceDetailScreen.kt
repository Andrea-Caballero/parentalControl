package com.example.parentalcontrol.ui.parent.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.parentalcontrol.domain.model.*
import com.example.parentalcontrol.viewmodel.ParentViewModel

/**
 * Pantalla de detalle de dispositivo.
 * Muestra stats, salud y permite configurar política.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    viewModel: ParentViewModel,
    onNavigateBack: () -> Unit
) {
    val devices by viewModel.devices.collectAsState()
    val templates by viewModel.templates.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Mock data para demo
    var selectedTab by remember { mutableIntStateOf(0) }
    var showTemplateDialog by remember { mutableStateOf(false) }

    // Cargar templates
    LaunchedEffect(Unit) {
        viewModel.loadTemplates()
    }

    // Datos mock
    val device = remember {
        ChildDevice(
            id = deviceId,
            name = "Galaxy S21 de Juan",
            model = "SM-G991B",
            appVersion = "1.0.0",
            policyVersion = 5,
            state = DeviceState.ACTIVE,
            lastSeenAt = "2026-06-04T12:00:00Z",
            isOnline = true
        )
    }

    val usageStats = remember {
        listOf(
            UsageStats(deviceId, "com.instagram.android", "Instagram", "2026-06-04", 45, 60, 15),
            UsageStats(deviceId, "com.whatsapp", "WhatsApp", "2026-06-04", 30, null, null),
            UsageStats(deviceId, "com.example.game", "Juego XYZ", "2026-06-04", 20, 30, 10)
        )
    }

    val health = remember {
        DeviceHealth(
            enforcementLevel = "STANDARD",
            suspicionLevel = "NONE",
            lastHeartbeat = "2026-06-04T12:00:00Z",
            batteryLevel = 85,
            isCharging = false
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.lockDevice(deviceId) }) {
                        Icon(Icons.Default.Lock, "Bloquear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Estado rápido
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (device.state) {
                        DeviceState.LOCKED -> MaterialTheme.colorScheme.errorContainer
                        DeviceState.DOWNTIME -> MaterialTheme.colorScheme.tertiaryContainer
                        DeviceState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            when (device.state) {
                                DeviceState.LOCKED -> Icons.Default.Lock
                                DeviceState.DOWNTIME -> Icons.Default.Star
                                DeviceState.ACTIVE -> Icons.Default.Check
                            },
                            null,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                when (device.state) {
                                    DeviceState.LOCKED -> "Dispositivo bloqueado"
                                    DeviceState.DOWNTIME -> "Hora de dormir"
                                    DeviceState.ACTIVE -> "Activo"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "v${device.policyVersion} • ${device.appVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (device.isOnline) {
                        AssistChip(
                            onClick = { },
                            label = { Text("Online") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Notifications,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Uso") },
                    icon = { Icon(Icons.Default.List, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Salud") },
                    icon = { Icon(Icons.Default.Info, null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Política") },
                    icon = { Icon(Icons.Default.Settings, null) }
                )
            }

            when (selectedTab) {
                0 -> UsageTab(usageStats)
                1 -> HealthTab(health)
                2 -> PolicyTab(
                    device = device,
                    templates = templates,
                    onApplyTemplate = { showTemplateDialog = true },
                    onReward = { viewModel.grantReward(deviceId, 15, "Recompensa") }
                )
            }
        }
    }

    // Diálogo de selección de plantilla
    if (showTemplateDialog) {
        TemplateSelectionDialog(
            templates = templates,
            onSelect = { template ->
                viewModel.updateDevicePolicy(deviceId, template.id)
                showTemplateDialog = false
            },
            onDismiss = { showTemplateDialog = false }
        )
    }
}

@Composable
private fun UsageTab(stats: List<UsageStats>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(stats) { stat ->
            UsageStatCard(stat)
        }
    }
}

@Composable
private fun UsageStatCard(stat: UsageStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        stat.appName ?: stat.packageName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    stat.limitMinutes?.let {
                        Text(
                            "Límite: $it min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${stat.minutesUsed} min",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        stat.limitMinutes != null && stat.remainingMinutes == 0 ->
                            MaterialTheme.colorScheme.error
                        stat.limitMinutes != null && (stat.remainingMinutes ?: 0) < 10 ->
                            MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                stat.remainingMinutes?.let {
                    Text(
                        "$it restantes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthTab(health: DeviceHealth) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Nivel de enforcement
        HealthCard(
            title = "Nivel de Enforcement",
            value = health.enforcementLevel,
            icon = Icons.Default.Settings,
            color = when (health.enforcementLevel) {
                "DEVICE_OWNER" -> MaterialTheme.colorScheme.primary
                "STANDARD" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.error
            }
        )

        // Nivel de sospecha
        HealthCard(
            title = "Nivel de Sospecha",
            value = health.suspicionLevel,
            icon = Icons.Default.Warning,
            color = when (health.suspicionLevel) {
                "NONE" -> MaterialTheme.colorScheme.primary
                "LOW" -> MaterialTheme.colorScheme.secondary
                "MEDIUM" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
        )

        // Batería
        health.batteryLevel?.let { battery ->
            HealthCard(
                title = "Batería",
                value = "$battery%${if (health.isCharging) " ⚡" else ""}",
                icon = if (health.isCharging) Icons.Default.Star else Icons.Default.Star,
                color = when {
                    battery > 50 -> MaterialTheme.colorScheme.primary
                    battery > 20 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }

        // Último heartbeat
        health.lastHeartbeat?.let { hb ->
            HealthCard(
                title = "Último Heartbeat",
                value = hb,
                icon = Icons.Default.Info,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // Alertas
        if (health.alerts.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Alertas",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    health.alerts.forEach { alert ->
                        Text("• $alert")
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, null, tint = color)
                Text(title, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PolicyTab(
    device: ChildDevice,
    templates: List<PolicyTemplate>,
    onApplyTemplate: () -> Unit,
    onReward: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Versión actual
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Versión de política")
                Text(
                    "v${device.policyVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Botón de recompensa
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Recompensa",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Dar tiempo extra como recompensa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onReward,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Star, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Dar +15 minutos")
                }
            }
        }

        // Cambiar plantilla
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Plantilla de política",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Aplica una plantilla predefinida o personaliza manualmente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onApplyTemplate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cambiar plantilla")
                }
            }
        }
    }
}

@Composable
private fun TemplateSelectionDialog(
    templates: List<PolicyTemplate>,
    onSelect: (PolicyTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar plantilla") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(templates) { template ->
                    Card(
                        onClick = { onSelect(template) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.isDefault)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        template.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (template.isDefault) {
                                        AssistChip(
                                            onClick = { },
                                            label = { Text("Por defecto") },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                                Text(
                                    template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Icon(
                                Icons.Default.Info,
                                null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
