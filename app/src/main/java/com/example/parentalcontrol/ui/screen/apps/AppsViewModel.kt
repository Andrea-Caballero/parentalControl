package com.example.parentalcontrol.ui.screen.apps

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentalcontrol.data.local.AppPolicyDao
import com.example.parentalcontrol.data.local.AppPolicyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the parent app-block screen.
 *
 * PR 5 of `openspec/changes/wire-pairing-and-approval-end-to-end` (task #30,
 * `app-block-policy` spec). Lets the parent browse every launchable app
 * installed on the reference device and toggle a per-app `app_policies`
 * row between `ALLOWED` and `BLOCKED`.
 *
 * NOTE on per-device isolation: the spec defines the data model as
 * `(device_id, package_name)` keyed. Today the [AppPolicyDao.upsertAppPolicy]
 * upserts on `package_name` alone with `OnConflictStrategy.REPLACE`, and the
 * design uses `"default"` as a placeholder device id. The per-device
 * enforcement is a follow-up — see task #30 step 3 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end/tasks.md`.
 */
@HiltViewModel
class AppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPolicyDao: AppPolicyDao
) : ViewModel() {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Reads every launchable activity from [Context.getPackageManager] and
     * exposes them as [AppInfo] (package name + display label + icon). Uses
     * [Intent.ACTION_MAIN] + `CATEGORY_LAUNCHER` per the `app-block-policy`
     * spec scenario "Lists every launchable package".
     */
    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = pm.queryIntentActivities(intent, 0)
                _apps.value = resolved.map { ri ->
                    AppInfo(
                        packageName = ri.activityInfo.packageName,
                        displayName = ri.loadLabel(pm).toString(),
                        icon = null
                    )
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Observes [AppPolicyDao.getAllAppPoliciesFlow] and exposes the set of
     * package names whose `state = "BLOCKED"`. Refreshes automatically when
     * the DAO emits.
     */
    fun loadBlockedPackages() {
        viewModelScope.launch(Dispatchers.IO) {
            appPolicyDao.getAllAppPoliciesFlow().collectLatest { policies ->
                _blockedPackages.value = policies
                    .filter { it.state == "BLOCKED" }
                    .map { it.package_name }
                    .toSet()
            }
        }
    }

    /**
     * Toggles the block state for [packageName]. Performs an optimistic
     * update on [_blockedPackages] and persists via [appPolicyDao.upsertAppPolicy].
     * Rolls the optimistic update back if the DAO throws.
     *
     * Uses `"default"` as the device_id placeholder — see class-level kdoc.
     */
    fun toggleBlock(packageName: String, block: Boolean) {
        val previous = _blockedPackages.value
        val optimistic = if (block) previous + packageName else previous - packageName
        _blockedPackages.value = optimistic

        viewModelScope.launch(Dispatchers.IO) {
            try {
                appPolicyDao.upsertAppPolicy(
                    AppPolicyEntity(
                        package_name = packageName,
                        device_id = DEFAULT_DEVICE_ID,
                        state = if (block) "BLOCKED" else "ALLOWED",
                        daily_limit_minutes = null,
                        allowed_windows = emptyList(),
                        category = null
                    )
                )
            } catch (e: Exception) {
                // Roll back optimistic update on DAO failure.
                _blockedPackages.value = previous
            }
        }
    }

    companion object {
        /**
         * Placeholder device id used until the per-device key on
         * [AppPolicyEntity] lands — see class-level kdoc.
         */
        const val DEFAULT_DEVICE_ID: String = "default"
    }
}

/**
 * Lightweight DTO for one row in [AppsViewModel.apps]. `icon` is the
 * `Drawable` resolved from `PackageManager.getApplicationIcon(packageName)`.
 * It is kept nullable because the Compose `Image` painter is built
 * lazily by the screen.
 */
data class AppInfo(
    val packageName: String,
    val displayName: String,
    val icon: Any?
)
