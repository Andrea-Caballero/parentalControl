package com.example.parentalcontrol

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                                    onCancel = { selectedMode = null }
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

    private fun restartActivity() {
        recreate()
    }
}
