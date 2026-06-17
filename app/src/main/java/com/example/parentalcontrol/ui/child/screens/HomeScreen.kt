package com.example.parentalcontrol.ui.child.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parentalcontrol.ui.child.components.*
import com.example.parentalcontrol.viewmodel.ChildViewModel
import com.example.parentalcontrol.viewmodel.ExtraTimeRequestState
import com.example.parentalcontrol.viewmodel.TimeExtraViewModel

/**
 * Pantalla principal del niño con flujo de tiempo extra integrado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ChildViewModel,
    timeExtraViewModel: TimeExtraViewModel,
    modifier: Modifier = Modifier
) {
    val remainingMinutes by viewModel.remainingMinutes.collectAsState()
    val dailyLimit by viewModel.dailyLimitMinutes.collectAsState()
    val dailyUsage by viewModel.dailyUsageMinutes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val policy by viewModel.policy.collectAsState()

    // Tiempo extra
    val extraMinutes by timeExtraViewModel.extraTimeAvailable.collectAsState()
    val countdown by timeExtraViewModel.nextExpireCountdown.collectAsState()
    val activeGrants by timeExtraViewModel.activeGrants.collectAsState()
    val myRequests by timeExtraViewModel.myRequests.collectAsState()
    val requestSent by timeExtraViewModel.requestSent.collectAsState()
    val error by timeExtraViewModel.error.collectAsState()

    var showRequestDialog by remember { mutableStateOf(false) }
    var showUsageSheet by remember { mutableStateOf(false) }
    var showUseTimeDialog by remember { mutableStateOf(false) }

    // Snackbar para resultado de solicitud
    val snackbarHostState = remember { SnackbarHostState() }

    // Manejar resultado de envío de solicitud
    LaunchedEffect(requestSent) {
        requestSent?.let { result ->
            when (result) {
                is ExtraTimeRequestState.Success -> {
                    snackbarHostState.showSnackbar(
                        "Solicitud enviada",
                        duration = SnackbarDuration.Short
                    )
                }
                is ExtraTimeRequestState.Throttled -> {
                    snackbarHostState.showSnackbar(
                        "Espera ${result.waitMinutes} minutos antes de pedir otra vez",
                        duration = SnackbarDuration.Short
                    )
                }
                is ExtraTimeRequestState.InvalidMinutes -> {
                    snackbarHostState.showSnackbar(
                        "Los minutos deben estar entre 5 y 120",
                        duration = SnackbarDuration.Short
                    )
                }
                is ExtraTimeRequestState.Error -> {
                    snackbarHostState.showSnackbar(
                        "Error: ${result.message}",
                        duration = SnackbarDuration.Short
                    )
                }
            }
            timeExtraViewModel.clearRequestSent()
        }
    }

    // Manejar errores
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            timeExtraViewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Mi Tiempo") },
                actions = {
                    // Sincronizar
                    IconButton(onClick = { 
                        viewModel.syncPolicy()
                        timeExtraViewModel.refreshData()
                    }) {
                        Icon(Icons.Default.Refresh, "Sincronizar")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ========== Banner de tiempo extra ==========
            if (extraMinutes > 0) {
                item {
                    ExtraTimeBanner(
                        minutesAvailable = extraMinutes,
                        countdownSeconds = countdown,
                        grants = activeGrants
                    )
                }
            }

            // ========== Tiempo principal ==========
            item {
                TimeCard(
                    remainingMinutes = (remainingMinutes + extraMinutes).toInt(),
                    totalMinutes = dailyLimit,
                    usedMinutes = dailyUsage,
                    extraMinutes = extraMinutes.toInt()
                )
            }

            // ========== Acciones principales ==========
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Botón de pedir tiempo
                    Button(
                        onClick = { showRequestDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pedir más")
                    }

                    // Botón de usar extra
                    if (extraMinutes > 0) {
                        OutlinedButton(
                            onClick = { showUseTimeDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Info, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Usar extra")
                        }
                    }
                }
            }

            // ========== Grants activos ==========
            if (activeGrants.isNotEmpty()) {
                item {
                    Text(
                        "Tiempo extra activo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                items(activeGrants) { grant ->
                    GrantCard(grant = grant)
                }
            }

            // ========== Mis solicitudes ==========
            // Solicitudes simplificadas - se muestran solo count
            if (myRequests.isNotEmpty()) {
                item {
                    Text(
                        "Solicitudes: ${myRequests.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ========== Ver estadísticas ==========
            item {
                OutlinedButton(
                    onClick = { showUsageSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.List, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver estadísticas")
                }
            }
        }
    }

    // Diálogos
    if (showRequestDialog) {
        RequestTimeDialog(
            onRequest = { minutes, reason ->
                timeExtraViewModel.sendRequest(minutes, reason)
                showRequestDialog = false
            },
            onDismiss = { showRequestDialog = false }
        )
    }

    if (showUsageSheet) {
        UsageStatsSheet(
            appsUsage = viewModel.getAppsUsage(),
            onDismiss = { showUsageSheet = false }
        )
    }

    if (showUseTimeDialog) {
        UseExtraTimeDialog(
            minutesAvailable = extraMinutes,
            onConfirm = { minutes ->
                // consumeExtraTime removed for simplicity
                showUseTimeDialog = false
            },
            onDismiss = { showUseTimeDialog = false }
        )
    }
}

@Composable
private fun TimeCard(
    remainingMinutes: Int,
    totalMinutes: Int,
    usedMinutes: Int,
    extraMinutes: Int = 0
) {
    val progress = if (totalMinutes > 0) {
        (totalMinutes - remainingMinutes).toFloat() / totalMinutes.toFloat()
    } else 0f

    val progressColor = when {
        progress > 0.9f -> MaterialTheme.colorScheme.error
        progress > 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Tiempo restante hoy",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier.size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    strokeWidth = 12.dp
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$remainingMinutes",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "minutos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            if (extraMinutes > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { },
                    label = { Text("+$extraMinutes min extra") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Info,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "de $totalMinutes minutos totales",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            if (usedMinutes > 0) {
                Text(
                    "$usedMinutes minutos usados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}
