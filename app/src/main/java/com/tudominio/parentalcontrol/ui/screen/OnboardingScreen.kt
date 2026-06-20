package com.tudominio.parentalcontrol.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import kotlinx.coroutines.launch

/**
 * T7 of `hotfix-parent-auth-session` — the parent card on the onboarding
 * screen now wires [ParentViewModel.authenticateAsParent] before invoking
 * [onAuthenticated]. While the auth coroutine is in flight:
 *  - the parent card is disabled,
 *  - a [CircularProgressIndicator] with the `onboarding_auth_loading` test
 *    tag is visible just above the cards.
 *
 * On auth failure the parent card re-enables, the indicator disappears,
 * and the inline error text is shown — navigation does NOT fire. The
 * child path is unchanged.
 *
 * Implementation note: the role cards use [Card] with
 * [Modifier.clickable] (not `OutlinedCard(onClick = ...)`) because the
 * Material3 `onClick` overload is experimental and Compose's stability
 * inference can optimize away a direct `onClick = onSelectChild`
 * parameter reference, causing the click to silently no-op in tests.
 * `Modifier.clickable` is the standard, test-friendly equivalent.
 */
@Composable
fun OnboardingScreen(
    viewModel: ParentViewModel,
    onAuthenticated: () -> Unit,
    onSelectChild: () -> Unit
) {
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "👨\u200d👩\u200d👧\u200d👦",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Control Parental",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Configura este dispositivo",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "¿Quién va a usar este dispositivo?",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isAuthenticating) {
            CircularProgressIndicator(
                modifier = Modifier
                    .testTag("onboarding_auth_loading")
                    .padding(bottom = 16.dp)
            )
        }

        // Padre
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_parent_card")
                .clickable(enabled = !isAuthenticating) {
                    if (isAuthenticating) return@clickable
                    isAuthenticating = true
                    authError = null
                    scope.launch {
                        val result = viewModel.authenticateAsParent()
                        if (result.isSuccess) {
                            isAuthenticating = false
                            onAuthenticated()
                        } else {
                            isAuthenticating = false
                            authError = result.exceptionOrNull()?.message
                                ?: "No se pudo iniciar sesión como padre"
                        }
                    }
                },
            colors = CardDefaults.outlinedCardColors()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "👨",
                    style = MaterialTheme.typography.displayMedium
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Soy el padre",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gestionar dispositivos de mis hijos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        authError?.let { errorMsg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hijo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_child_card")
                .clickable(enabled = !isAuthenticating) {
                    onSelectChild()
                }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "👦",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Soy el hijo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Emparejar con la cuenta de mis padres",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
