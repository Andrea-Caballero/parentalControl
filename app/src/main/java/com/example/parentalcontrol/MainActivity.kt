package com.example.parentalcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.copy.CopyManager
import com.example.parentalcontrol.data.repository.TimeExtraRepository
import com.example.parentalcontrol.pairing.PairingViewModel
import com.example.parentalcontrol.pairing.ui.PairingScreen
import com.example.parentalcontrol.ui.child.extra.ExtraTimeScreen
import com.example.parentalcontrol.ui.child.status.ChildStatusScreen
import com.example.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.example.parentalcontrol.ui.parent.screens.DashboardScreen
import com.example.parentalcontrol.ui.screen.OnboardingScreen
import com.example.parentalcontrol.ui.theme.ParentalControlTheme
import com.example.parentalcontrol.viewmodel.ParentViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Hoisted state for the deeplink-prefilled pairing code. Updated by
     * [handlePairingDeeplink] from `onCreate` and `onNewIntent`; consumed by
     * the `PairingScreen` composable via the `prefilledCode` parameter.
     */
    private val prefilledPairingCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePairingDeeplink(intent)

        val authManager = DeviceAuthManager.getInstance(this)
        val isPaired = authManager.isPaired()
        val parentId = getSharedPreferences("device_auth_prefs", MODE_PRIVATE)
            .getString("parent_id", null)
        val isChildDevice = isPaired && parentId != null

        setContent {
            ParentalControlTheme {
                when {
                    !isPaired -> {
                        var selectedMode by remember { mutableStateOf<String?>(null) }
                        when (selectedMode) {
                            "parent" -> {
                                val parentViewModel: ParentViewModel = hiltViewModel()
                                DashboardScreen(
                                    viewModel = parentViewModel,
                                    onNavigateToDevice = { },
                                    onNavigateToRequests = { }
                                )
                            }
                            "child" -> {
                                val pairingViewModel: PairingViewModel = hiltViewModel()
                                PairingScreen(
                                    viewModel = pairingViewModel,
                                    onPairingComplete = { restartActivity() },
                                    onCancel = { selectedMode = null },
                                    prefilledCode = prefilledPairingCode.value
                                )
                            }
                            null -> {
                                OnboardingScreen(
                                    onSelectParent = { selectedMode = "parent" },
                                    onSelectChild = { selectedMode = "child" }
                                )
                            }
                        }
                    }
                    isChildDevice -> {
                        var showExtraTime by remember { mutableStateOf(false) }
                        val copyManager = CopyManager.getInstance(this)
                        val childViewModel: ChildStatusViewModel = hiltViewModel()
                        val deviceId = authManager.deviceId.value ?: ""

                        if (showExtraTime) {
                            ExtraTimeScreen(
                                copyManager = copyManager,
                                repository = TimeExtraRepository(this@MainActivity),
                                deviceId = deviceId,
                                onBack = { showExtraTime = false },
                                onRequestSent = { showExtraTime = false },
                                onError = { }
                            )
                        } else {
                            ChildStatusScreen(
                                viewModel = childViewModel,
                                copyManager = copyManager,
                                onRequestExtraTime = { showExtraTime = true }
                            )
                        }
                    }
                    else -> {
                        val parentViewModel: ParentViewModel = hiltViewModel()
                        DashboardScreen(
                            viewModel = parentViewModel,
                            onNavigateToDevice = { },
                            onNavigateToRequests = { }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingDeeplink(intent)
    }

    /**
     * Extracts the `code` query parameter from a `parentalcontrol://pair?code=…`
     * intent and stashes it in [prefilledPairingCode] for [PairingScreen] to
     * pick up. No-op for intents that don't match the deeplink shape.
     */
    private fun handlePairingDeeplink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "parentalcontrol" || data.host != "pair") return
        prefilledPairingCode.value = data.getQueryParameter("code")
    }

    private fun restartActivity() {
        recreate()
    }
}
