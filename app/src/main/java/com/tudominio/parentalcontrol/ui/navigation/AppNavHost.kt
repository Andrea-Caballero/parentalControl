package com.tudominio.parentalcontrol.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManagerMagicLinkVerifier
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepositoryEntryPoint
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import dagger.hilt.android.EntryPointAccessors

/**
 * Compose-side wiring for [NavGraph].
 *
 * Lives in `ui/navigation/` (not next to `MainActivity`) so the activity
 * stays focused on lifecycle + deeplink plumbing. The activity reads its
 * own `MutableState<String?>` for [prefilledPairingCode] and
 * [pendingExtraTimePackage] and forwards the current value here; this
 * composable then resolves the auth state, the Hilt-provided singletons
 * (via [EntryPointAccessors] / `hiltViewModel`), and the device id, and
 * feeds everything to [NavGraph] as parameters.
 *
 * The wiring is hoisted out of `MainActivity.onCreate` because:
 *  - `hiltViewModel<>()` requires a Compose context, and `onCreate` is
 *    a plain `Activity` method.
 *  - `LocalContext.current` requires a composable scope.
 *  - `remember { … }` needs a recomposition-aware scope so the auth
 *    manager / copy manager / outbox entry-point aren't re-fetched on
 *    every recomposition.
 *
 * [onPairingComplete] is invoked by [NavGraph] when `PairingScreen`
 * reports success. The activity wires this to `recreate()` so the next
 * composition picks up the freshly persisted pairing state.
 *
 * [onExtraTimeConsumed] is invoked by [NavGraph] when `ExtraTimeScreen`
 * finishes (back, request sent, or error) so the activity clears the
 * pending-extra-time state — otherwise the next composition would
 * re-route to ExtraTime on every recreation. Without this, an activity
 * `recreate()` (e.g. after pairing) would replay the deeplink forever.
 */
@Composable
fun AppNavHost(
    prefilledPairingCode: String?,
    pendingExtraTimePackage: String?,
    pendingMagicLinkUrl: String? = null,
    onPairingComplete: () -> Unit,
    onExtraTimeConsumed: () -> Unit,
    onMagicLinkConsumed: () -> Unit = {},
    // Slice B1 — forwarded to `MagicLinkSignInScreen` → `restartActivity`.
    onAuthenticated: () -> Unit = {}
) {
    val context = LocalContext.current
    val authManager = remember { DeviceAuthManager.getInstance(context) }
    val copyManager = remember { CopyManager.getInstance(context) }
    val timeExtraRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TimeExtraRepositoryEntryPoint::class.java
        ).timeExtraRepository()
    }
    // Continuation #2: the magic-link deep-link handler is a pure class;
    // wrap the `DeviceAuthManager` singleton in the verifier adapter so
    // the handler stays Context-free and NavGraph can run it off the
    // pending URL without a Hilt dependency.
    val magicLinkVerifier = remember { DeviceAuthManagerMagicLinkVerifier(authManager) }
    val prefs = remember {
        context.getSharedPreferences(DEVICE_AUTH_PREFS, Context.MODE_PRIVATE)
    }
    val isPaired = authManager.isPaired()
    val isChildDevice = isPaired && prefs.getString(PARENT_ID_KEY, null) != null
    val deviceId = authManager.deviceId.value.orEmpty()

    NavGraph(
        isPaired = isPaired,
        isChildDevice = isChildDevice,
        prefilledPairingCode = prefilledPairingCode,
        pendingExtraTimePackage = pendingExtraTimePackage,
        pendingMagicLinkUrl = pendingMagicLinkUrl,
        parentViewModel = hiltViewModel<ParentViewModel>(),
        appsViewModel = hiltViewModel<AppsViewModel>(),
        pairingViewModel = hiltViewModel<PairingViewModel>(),
        childStatusViewModel = hiltViewModel<ChildStatusViewModel>(),
        copyManager = copyManager,
        timeExtraRepository = timeExtraRepository,
        deviceId = deviceId,
        magicLinkVerifier = magicLinkVerifier,
        onPairingComplete = onPairingComplete,
        onExtraTimeConsumed = onExtraTimeConsumed,
        onMagicLinkConsumed = onMagicLinkConsumed,
        onAuthenticated = onAuthenticated
    )
}

private const val DEVICE_AUTH_PREFS = "device_auth_prefs"
private const val PARENT_ID_KEY = "parent_id"
