package com.example.parentalcontrol.ui.parent.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.parentalcontrol.domain.model.ChildDevice
import com.example.parentalcontrol.domain.model.TimeRequest
import com.example.parentalcontrol.ui.parent.components.DeviceCard
import com.example.parentalcontrol.ui.parent.components.PairingBottomSheet
import com.example.parentalcontrol.ui.parent.components.RequestCard
import com.example.parentalcontrol.viewmodel.ParentViewModel

/**
 * Pantalla principal del padre.
 * Muestra dispositivos y solicitudes pendientes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ParentViewModel,
    onNavigateToDevice: (String) -> Unit = {},
    onNavigateToRequests: () -> Unit = {}
) {
    val devices by viewModel.devices.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showPairingSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control Parental") },
                actions = {
                    // Notificación de solicitudes pendientes
                    BadgedBox(
                        badge = {
                            if (pendingRequests.isNotEmpty()) {
                                Badge { Text(pendingRequests.size.toString()) }
                            }
                        }
                    ) {
                        IconButton(onClick = onNavigateToRequests) {
                            Icon(Icons.Default.Notifications, "Solicitudes")
                        }
                    }
                    IconButton(onClick = { showPairingSheet = true }) {
                        Icon(Icons.Default.Add, "Agregar dispositivo")
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
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Dispositivos") },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Solicitudes") },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingRequests.isNotEmpty()) {
                                    Badge { Text(pendingRequests.size.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Info, null)
                        }
                    }
                )
            }

            when (selectedTab) {
                0 -> DevicesTab(
                    devices = devices,
                    isLoading = isLoading,
                    onDeviceClick = onNavigateToDevice,
                    onLockDevice = { viewModel.lockDevice(it) },
                    onUnlockDevice = { viewModel.unlockDevice(it) }
                )
                1 -> RequestsTab(
                    requests = pendingRequests,
                    isLoading = isLoading,
                    onApprove = { id, minutes -> viewModel.approveRequest(id, minutes) },
                    onDeny = { id -> viewModel.denyRequest(id) }
                )
            }
        }

        // Bottom sheet de emparejamiento
        if (showPairingSheet) {
            PairingBottomSheet(
                viewModel = viewModel,
                onDismiss = { showPairingSheet = false }
            )
        }

        // Snackbar de error
        error?.let {
            LaunchedEffect(it) {
                viewModel.clearError()
            }
        }
    }
}

@Composable
private fun DevicesTab(
    devices: List<ChildDevice>,
    isLoading: Boolean,
    onDeviceClick: (String) -> Unit,
    onLockDevice: (String) -> Unit,
    onUnlockDevice: (String) -> Unit
) {
    if (isLoading && devices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (devices.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Person,
            title = "Sin dispositivos",
            subtitle = "Empareja un dispositivo para comenzar"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(devices) { device ->
                DeviceCard(
                    device = device,
                    onClick = { onDeviceClick(device.id) },
                    onLock = { onLockDevice(device.id) },
                    onUnlock = { onUnlockDevice(device.id) }
                )
            }
        }
    }
}

@Composable
private fun RequestsTab(
    requests: List<TimeRequest>,
    isLoading: Boolean,
    onApprove: (String, Int) -> Unit,
    onDeny: (String) -> Unit
) {
    if (isLoading && requests.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (requests.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Check,
            title = "Sin solicitudes",
            subtitle = "Las solicitudes de tiempo aparecerán aquí"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(requests) { request ->
                RequestCard(
                    request = request,
                    onApprove = { minutes -> onApprove(request.id, minutes) },
                    onDeny = { onDeny(request.id) }
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
