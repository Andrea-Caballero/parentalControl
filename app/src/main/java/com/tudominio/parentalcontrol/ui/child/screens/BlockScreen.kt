package com.tudominio.parentalcontrol.ui.child.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.domain.model.BlockedState
import com.tudominio.parentalcontrol.ui.child.components.RequestTimeDialog
import com.tudominio.parentalcontrol.ui.child.components.UsageStatsSheet
import com.tudominio.parentalcontrol.viewmodel.ChildViewModel

/**
 * Pantalla de bloqueo para el niño.
 * Se muestra cuando una app está bloqueada o el tiempo se agotó.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockScreen(
    blockedState: BlockedState,
    remainingMinutes: Int,
    onRequestTime: (Int, String?) -> Unit,
    onViewUsage: () -> Unit,
    parentMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var showRequestDialog by remember { mutableStateOf(false) }
    var currentPackageName by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icono de bloqueo
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (blockedState) {
                        BlockedState.APP_BLOCKED -> Icons.Default.Warning
                        BlockedState.SCHEDULE_BLOCK -> Icons.Default.Warning
                        BlockedState.TIME_EXCEEDED -> Icons.Default.Warning
                        BlockedState.LIMIT_EXCEEDED -> Icons.Default.Warning
                        BlockedState.NOT_BLOCKED -> Icons.Default.Check
                    },
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Título
            Text(
                text = getBlockedTitle(blockedState),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Descripción
            Text(
                text = getBlockedDescription(blockedState),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            // Tiempo restante (si aplica)
            if (blockedState == BlockedState.TIME_EXCEEDED || 
                blockedState == BlockedState.LIMIT_EXCEEDED) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Tiempo restante hoy",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "$remainingMinutes min",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Mensaje del padre (si existe)
            parentMessage?.let { message ->
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Botones de acción
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Botón principal: Pedir tiempo
                if (blockedState != BlockedState.SCHEDULE_BLOCK) {
                    Button(
                        onClick = { showRequestDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pedir tiempo extra")
                    }
                }

                // Botón secundario: Ver uso
                OutlinedButton(
                    onClick = onViewUsage,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.List, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver cómo usé mi tiempo")
                }
            }
        }
    }

    // Diálogo de solicitar tiempo
    if (showRequestDialog) {
        RequestTimeDialog(
            onRequest = { minutes, reason ->
                onRequestTime(minutes, reason)
                showRequestDialog = false
            },
            onDismiss = { showRequestDialog = false }
        )
    }
}

private fun getBlockedTitle(state: BlockedState): String {
    return when (state) {
        BlockedState.APP_BLOCKED -> "App bloqueada"
        BlockedState.SCHEDULE_BLOCK -> "Hora de dormir"
        BlockedState.TIME_EXCEEDED -> "Tiempo agotado"
        BlockedState.LIMIT_EXCEEDED -> "Límite alcanzado"
        BlockedState.NOT_BLOCKED -> "Todo bien"
    }
}

private fun getBlockedDescription(state: BlockedState): String {
    return when (state) {
        BlockedState.APP_BLOCKED -> 
            "Esta app está bloqueada por tus padres.\nPregúntales si puedes usarla."
        BlockedState.SCHEDULE_BLOCK -> 
            "Es hora de dormir o de hacer otras actividades.\nEl dispositivo se desbloqueará automáticamente."
        BlockedState.TIME_EXCEEDED -> 
            "Ya usaste todo tu tiempo de pantalla hoy.\n¡Buen trabajo equilibrando tu tiempo!"
        BlockedState.LIMIT_EXCEEDED -> 
            "Llegaste al límite de uso de esta app.\nPuedes pedir más tiempo a tus padres."
        BlockedState.NOT_BLOCKED -> 
            "Puedes usar esta app sin problemas."
    }
}
