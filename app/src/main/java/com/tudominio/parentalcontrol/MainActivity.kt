package com.tudominio.parentalcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepositoryEntryPoint
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.pairing.ui.PairingScreen
import com.tudominio.parentalcontrol.ui.child.extra.ExtraTimeScreen
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusScreen
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreen
import com.tudominio.parentalcontrol.ui.screen.OnboardingScreen
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors

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
        val timeExtraRepository = EntryPointAccessors.fromApplication(
            applicationContext,
            TimeExtraRepositoryEntryPoint::class.java
        ).timeExtraRepository()

        setContent {
            ParentalControlTheme {
                when {
                    !isPaired -> {
                        var selectedMode by remember { mutableStateOf<String?>(null) }
                        when (selectedMode) {
                            "parent" -> {
                                val parentViewModel: ParentViewModel = hiltViewModel()
                                val appsViewModel: AppsViewModel = hiltViewModel()
                                DashboardScreen(
                                    viewModel = parentViewModel,
                                    appsViewModel = appsViewModel,
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
                                repository = timeExtraRepository,
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
                        val appsViewModel: AppsViewModel = hiltViewModel()
                        DashboardScreen(
                            viewModel = parentViewModel,
                            appsViewModel = appsViewModel,
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
