package com.tudominio.parentalcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.tudominio.parentalcontrol.ui.navigation.AppNavHost
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the ParentalControl app.
 *
 * Per the `app-entry-routing` spec (PR 4 of `align-with-guia-fedora44`),
 * this activity only owns lifecycle + deeplink plumbing. The actual
 * route selection lives in `ui.navigation.NavGraph`, invoked via
 * [AppNavHost]. [prefilledPairingCode] is hoisted here so the
 * `parentalcontrol://pair?code=...` deeplink prefill survives both
 * `onCreate` (cold start) and `onNewIntent` (warm start) without an
 * activity restart.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val prefilledPairingCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePairingDeeplink(intent)
        setContent {
            ParentalControlTheme {
                AppNavHost(prefilledPairingCode = prefilledPairingCode.value, onPairingComplete = ::restartActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingDeeplink(intent)
    }

    /**
     * Extracts the `code` query parameter from a `parentalcontrol://pair?code=…`
     * intent and stashes it in [prefilledPairingCode] for `PairingScreen` to
     * pick up. No-op for intents that don't match the deeplink shape.
     */
    private fun handlePairingDeeplink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "parentalcontrol" || data.host != "pair") return
        prefilledPairingCode.value = data.getQueryParameter("code")
    }

    /**
     * Triggers an activity `recreate()` so the next composition picks up
     * the freshly persisted pairing state (parent vs child device).
     * Invoked by `NavGraph` when `PairingScreen` reports success via
     * its `onPairingComplete` callback.
     */
    private fun restartActivity() {
        recreate()
    }
}