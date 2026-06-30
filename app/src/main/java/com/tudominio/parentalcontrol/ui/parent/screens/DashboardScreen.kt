package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.ui.parent.components.DeviceCard
import com.tudominio.parentalcontrol.ui.parent.components.PairingBottomSheet
import com.tudominio.parentalcontrol.ui.parent.components.RequestCard
import com.tudominio.parentalcontrol.ui.screen.apps.AppsScreen
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel

/**
 * Pantalla principal del padre.
 * Muestra dispositivos y solicitudes pendientes.
 *
 * PR 2 of `openspec/changes/wire-pairing-and-approval-end-to-end` wires
 * the Devices tab to the new `DeviceListUiState` sealed UI state
 * (`Loading` / `Success` / `Empty` / `Error`) so the dashboard can render
 * a retry banner when the `get-devices-for-parent` call fails and an
 * empty-state CTA when no devices are paired.
 *
 * PR 5 adds an inline navigation state machine: tapping a device opens
 * [DeviceDetailScreen]; the Policy tab's "Add to block list" button opens
 * [AppsScreen] pre-scoped to the originating `deviceId`. The state is a
 * simple stack so back-navigation returns to the previous screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ParentViewModel,
    appsViewModel: AppsViewModel? = null,
    onNavigateToDevice: (String) -> Unit = {},
    onNavigateToRequests: () -> Unit = {}
) {
    val devices by viewModel.devices.collectAsState()
    val deviceListState by viewModel.deviceListState.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showPairingSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // PR 5 navigation state machine. The three screens are the Devices/Requests
    // tabs, [DeviceDetailScreen], and [AppsScreen].
    var navTarget by remember { mutableStateOf<NavTarget>(NavTarget.Dashboard) }

    val appsVm: AppsViewModel = appsViewModel
        ?: error("DashboardScreen requires `appsViewModel` to be provided by the caller (Hilt @HiltViewModel).")

    when (val target = navTarget) {
        is NavTarget.Dashboard -> {
            DashboardScaffold(
                viewModel = viewModel,
                devices = devices,
                deviceListState = deviceListState,
                pendingRequests = pendingRequests,
                isLoading = isLoading,
                error = error,
                selectedTab = selectedTab,
                showPairingSheet = showPairingSheet,
                onSelectTab = { selectedTab = it },
                onShowPairingSheet = { showPairingSheet = true },
                onDismissPairingSheet = { showPairingSheet = false },
                onNavigateToDevice = { id -> navTarget = NavTarget.DeviceDetail(id) },
                onNavigateToRequests = onNavigateToRequests,
                onClearError = { viewModel.clearError() }
            )
        }
        is NavTarget.DeviceDetail -> {
            DeviceDetailScreen(
                deviceId = target.deviceId,
                viewModel = viewModel,
                onNavigateBack = { navTarget = NavTarget.Dashboard },
                onNavigateToApps = { id -> navTarget = NavTarget.Apps(id) }
            )
        }
        is NavTarget.Apps -> {
            AppsScreen(
                deviceId = target.deviceId,
                viewModel = appsVm,
                onBack = { navTarget = NavTarget.DeviceDetail(target.deviceId) }
            )
        }
    }
}

/**
 * Internal navigation targets for the parent screens — replaces the
 * standalone `NavController`/`NavHost` until a proper Navigation graph
 * lands. Stays trivial: each target knows how to render itself.
 */
private sealed class NavTarget {
    data object Dashboard : NavTarget()
    data class DeviceDetail(val deviceId: String) : NavTarget()
    data class Apps(val deviceId: String) : NavTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScaffold(
    viewModel: ParentViewModel,
    devices: List<ChildDevice>,
    deviceListState: DeviceListUiState,
    pendingRequests: List<TimeRequest>,
    isLoading: Boolean,
    error: String?,
    selectedTab: Int,
    showPairingSheet: Boolean,
    onSelectTab: (Int) -> Unit,
    onShowPairingSheet: () -> Unit,
    onDismissPairingSheet: () -> Unit,
    onNavigateToDevice: (String) -> Unit,
    onNavigateToRequests: () -> Unit,
    onClearError: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control Parental") },
                actions = {
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
                    IconButton(onClick = onShowPairingSheet) {
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
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectTab(0) },
                    text = { Text("Dispositivos") },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectTab(1) },
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

            // D1 of `fix-parent-solicitudes-auto-poll` — re-fetch pending
            // requests whenever the parent switches to the Solicitudes tab
            // (index 1). `LaunchedEffect(selectedTab)` cancels and re-launches
            // on every tab change; the body is gated to `selectedTab == 1`
            // so other tabs (Devices, future tabs) do NOT trigger a fetch.
            // Spec scenarios covered:
            //   - First tap on the Solicitudes tab triggers a fetch
            //   - Re-tapping the Solicitudes tab triggers a fresh fetch
            //   - Other tabs do not trigger a Solicitudes fetch
            LaunchedEffect(selectedTab) {
                if (selectedTab == 1) {
                    viewModel.loadPendingRequests()
                }
            }

            when (selectedTab) {
                0 -> DevicesTab(
                    viewModel = viewModel,
                    devices = devices,
                    listState = deviceListState,
                    onDeviceClick = onNavigateToDevice,
                    onLockDevice = { viewModel.lockDevice(it) },
                    onUnlockDevice = { viewModel.unlockDevice(it) },
                    onRetry = { viewModel.loadDevices() },
                    onClearError = onClearError
                )
                1 -> RequestsTab(
                    requests = pendingRequests,
                    isLoading = isLoading,
                    onApprove = { id, minutes -> viewModel.approveRequest(id, minutes) },
                    onDeny = { id -> viewModel.denyRequest(id) }
                )
            }
        }

        if (showPairingSheet) {
            PairingBottomSheet(
                viewModel = viewModel,
                onDismiss = onDismissPairingSheet
            )
        }

        // Snackbar de error
        error?.let {
            LaunchedEffect(it) {
                onClearError()
            }
        }
    }
}

@Composable
private fun DevicesTab(
    viewModel: ParentViewModel,
    devices: List<ChildDevice>,
    listState: DeviceListUiState,
    onDeviceClick: (String) -> Unit,
    onLockDevice: (String) -> Unit,
    onUnlockDevice: (String) -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    when (listState) {
        DeviceListUiState.Loading -> {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Keep previously-loaded items on screen during a refresh
                // rather than blanking them out.
                DeviceList(
                    list = devices,
                    onDeviceClick = onDeviceClick,
                    onLockDevice = onLockDevice,
                    onUnlockDevice = onUnlockDevice
                )
            }
        }
        DeviceListUiState.Empty -> {
            EmptyState(
                icon = Icons.Default.Person,
                title = "Sin dispositivos",
                subtitle = "Empareja un dispositivo para comenzar"
            )
        }
        is DeviceListUiState.Error -> {
            // T8 of `hotfix-parent-auth-session` — pattern-match on the
            // typed reason. Auth-missing swaps retry/back for a single
            // sign-in CTA; transient keeps the retry/back CTAs.
            when (val reason = listState.reason) {
                DeviceListError.AuthMissing -> AuthMissingErrorBanner(
                    onSignIn = {
                        scope.launch { viewModel.authenticateAsParent() }
                    }
                )
                is DeviceListError.Transient -> TransientErrorBanner(
                    message = reason.reason,
                    onRetry = onRetry,
                    onDismiss = onClearError
                )
            }
        }
        is DeviceListUiState.Success -> {
            DeviceList(
                list = listState.items,
                onDeviceClick = onDeviceClick,
                onLockDevice = onLockDevice,
                onUnlockDevice = onUnlockDevice
            )
        }
    }
}

@Composable
private fun DeviceList(
    list: List<ChildDevice>,
    onDeviceClick: (String) -> Unit,
    onLockDevice: (String) -> Unit,
    onUnlockDevice: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(list) { device ->
            DeviceCard(
                device = device,
                onClick = { onDeviceClick(device.id) },
                onLock = { onLockDevice(device.id) },
                onUnlock = { onUnlockDevice(device.id) }
            )
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
    icon: ImageVector,
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

/**
 * Auth-missing variant of the dashboard error banner. Per the spec delta
 * at
 * `openspec/changes/hotfix-parent-auth-session/specs/parent-device-list/spec.md`:
 * when the parent has no session, the banner shows a single
 * "Iniciar sesión como padre" CTA. Retry/back are intentionally absent —
 * retrying a missing-auth call just produces the same error, and there
 * is no previous screen to go back to from this state.
 *
 * Tapping the CTA calls [ParentViewModel.authenticateAsParent] which
 * issues a synthetic anonymous session and (on success) triggers a
 * device-list reload via the same channel the onboarding flow uses.
 */
@Composable
private fun AuthMissingErrorBanner(
    onSignIn: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Error cargando dispositivos",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    "not authenticated",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.testTag("auth_missing_sign_in_cta")
                ) {
                    Text("Iniciar sesión como padre")
                }
            }
        }
    }
}

/**
 * Transient-error variant of the dashboard error banner. Same shape as
 * the pre-hotfix `ErrorBanner` — retry + dismiss CTAs — but kept as a
 * separate composable so the call site pattern-matches on
 * [DeviceListError] instead of string-matching the error message.
 */
@Composable
private fun TransientErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Error cargando dispositivos",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Reintentar")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}
