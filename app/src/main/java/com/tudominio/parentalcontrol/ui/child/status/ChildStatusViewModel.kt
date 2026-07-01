package com.tudominio.parentalcontrol.ui.child.status

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.health.DegradationAlertManager
import com.tudominio.parentalcontrol.health.HealthChecker
import com.tudominio.parentalcontrol.health.Permission
import com.tudominio.parentalcontrol.reward.RewardManager
import com.tudominio.parentalcontrol.time.TimeProvider
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class ChildStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase,
    private val rewardManager: RewardManager,
    private val degradationAlertManager: DegradationAlertManager,
    private val timeProvider: TimeProvider = DefaultTimeProvider(context),
    private val authManager: DeviceAuthManager = DeviceAuthManager.getInstance(context)
) : ViewModel() {

    companion object {
        private const val TAG = "ChildStatusViewModel"

        const val WARNING_THRESHOLD_10 = 10L
        const val WARNING_THRESHOLD_5 = 5L

        private const val DEGRADATION_CHECK_INTERVAL_MS = 30_000L
    }

    private val usageDao = database.usageDao()
    private val timeRequestDao = database.timeRequestDao()
    private val healthChecker = HealthChecker(context)

    private val _uiState = MutableStateFlow<ChildStatusUiState>(ChildStatusUiState.Loading)
    val uiState: StateFlow<ChildStatusUiState> = _uiState.asStateFlow()

    private val _timeRemaining = MutableStateFlow(0L)
    val timeRemaining: StateFlow<Long> = _timeRemaining.asStateFlow()

    private val _timeUsedToday = MutableStateFlow(0L)
    val timeUsedToday: StateFlow<Long> = _timeUsedToday.asStateFlow()

    private val _dailyLimit = MutableStateFlow(120L)
    val dailyLimit: StateFlow<Long> = _dailyLimit.asStateFlow()

    private val _nextBlockTime = MutableStateFlow<Instant?>(null)
    val nextBlockTime: StateFlow<Instant?> = _nextBlockTime.asStateFlow()

    private val _warningLevel = MutableStateFlow(WarningLevel.NONE)
    val warningLevel: StateFlow<WarningLevel> = _warningLevel.asStateFlow()

    private val _pendingTimeRequest = MutableStateFlow<TimeRequestEntity?>(null)
    val pendingTimeRequest: StateFlow<TimeRequestEntity?> = _pendingTimeRequest.asStateFlow()

    private val _allowedAppsNow = MutableStateFlow<List<String>>(emptyList())
    val allowedAppsNow: StateFlow<List<String>> = _allowedAppsNow.asStateFlow()

    private val _rewardBalance = MutableStateFlow(0L)
    val rewardBalance: StateFlow<Long> = _rewardBalance.asStateFlow()

    private var _lastKnownRewardBalance = 0L

    private val _events = MutableSharedFlow<ChildStatusEvent>()
    val events: SharedFlow<ChildStatusEvent> = _events.asSharedFlow()

    private val _degradationCauses = MutableStateFlow<List<DegradationAlertManager.DegradationCause>>(emptyList())
    val degradationCauses: StateFlow<List<DegradationAlertManager.DegradationCause>> = _degradationCauses.asStateFlow()

    private val _showRecoveryDialog = MutableStateFlow(false)
    val showRecoveryDialog: StateFlow<Boolean> = _showRecoveryDialog.asStateFlow()

    init {
        loadInitialState()
        startObserving()
        startDegradationMonitoring()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                val today = timeProvider.currentDate()
                val usages = usageDao.getUsageForDateFlow(today.toString()).first()
                val totalUsed = usages.sumOf { it.usage_minutes }.toLong()
                _timeUsedToday.value = totalUsed

                calculateTimeRemaining()
                loadPendingRequest()
                loadRewardBalance()
                updateUiState()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state: ${e.message}")
                _uiState.value = ChildStatusUiState.Error("Error cargando estado")
            }
        }
    }

    private fun startObserving() {
        viewModelScope.launch {
            // SDD 2026-07-01: include active extra_time grants in the
            // `timeRemaining` calculation so a parent approve via the
            // post-boot pullApprovedRequests immediately extends the
            // child's visible minutes-restantes (no app restart needed).
            // The grant table is observed via Flow so the UI updates the
            // moment processApproval inserts a row.
            val deviceId = authManager.deviceId.value
            val grantsFlow: Flow<Long> = if (deviceId != null) {
                database.grantDao()
                    .getGrantsForDeviceFlow(deviceId)
                    .map { grants -> grants.sumActiveMinutes(timeProvider) }
            } else {
                flowOf(0L)
            }
            combine(
                _timeUsedToday,
                _dailyLimit,
                grantsFlow
            ) { used, limit, grantsMinutes ->
                Triple(used, limit, grantsMinutes)
            }.collect { (used, limit, grantsMinutes) ->
                _timeRemaining.value = maxOf(0, limit - used + grantsMinutes)
                updateWarningLevel()
                updateUiState()
            }
        }
    }

    private fun startDegradationMonitoring() {
        viewModelScope.launch {
            while (true) {
                checkDegradation()
                delay(DEGRADATION_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun checkDegradation() {
        viewModelScope.launch {
            try {
                val result = healthChecker.checkHealth()
                val causes = mutableListOf<DegradationAlertManager.DegradationCause>()

                if (!result.isAccessibilityServiceEnabled) {
                    causes.add(degradationAlertManager.getCauseForPermission(Permission.ACCESSIBILITY_SERVICE))
                }
                if (!result.isOverlayPermissionGranted) {
                    causes.add(degradationAlertManager.getCauseForPermission(Permission.OVERLAY_PERMISSION))
                }
                if (!result.isBatteryOptimizationIgnored) {
                    causes.add(degradationAlertManager.getCauseForPermission(Permission.BATTERY_OPTIMIZATION))
                }
                if (!result.isUsageStatsPermissionGranted) {
                    causes.add(degradationAlertManager.getCauseForPermission(Permission.USAGE_STATS))
                }
                if (!result.isDeviceAdminActive) {
                    causes.add(degradationAlertManager.getCauseForPermission(Permission.DEVICE_ADMIN))
                }

                _degradationCauses.value = causes

                if (causes.isNotEmpty() && degradationAlertManager.shouldShowAlert()) {
                    degradationAlertManager.showAlert(causes)
                    _events.emit(ChildStatusEvent.DegradationDetected(causes))
                }

                if (causes.isEmpty() && _degradationCauses.value.isNotEmpty()) {
                    _showRecoveryDialog.value = true
                    causes.forEach { cause ->
                        degradationAlertManager.onProtectionRestored(cause.issueType)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking degradation: ${e.message}")
            }
        }
    }

    private fun calculateTimeRemaining() {
        val remaining = maxOf(0, _dailyLimit.value - _timeUsedToday.value)
        _timeRemaining.value = remaining

        if (remaining > 0) {
            _nextBlockTime.value = timeProvider.wallInstant().plusSeconds(remaining * 60)
        } else {
            _nextBlockTime.value = timeProvider.wallInstant()
        }

        updateWarningLevel()
    }

    private fun updateWarningLevel() {
        val remaining = _timeRemaining.value

        _warningLevel.value = when {
            remaining <= 0 -> WarningLevel.BLOCKED
            remaining <= WARNING_THRESHOLD_5 -> WarningLevel.CRITICAL
            remaining <= WARNING_THRESHOLD_10 -> WarningLevel.WARNING
            else -> WarningLevel.NONE
        }
    }

    private fun updateUiState() {
        val remaining = _timeRemaining.value
        val used = _timeUsedToday.value
        val limit = _dailyLimit.value
        val nextBlock = _nextBlockTime.value
        val warning = _warningLevel.value
        val pending = _pendingTimeRequest.value

        _uiState.value = ChildStatusUiState.Content(
            timeRemaining = remaining,
            timeUsedToday = used,
            dailyLimit = limit,
            nextBlockTime = nextBlock,
            warningLevel = warning,
            hasPendingRequest = pending != null,
            allowedAppsNow = _allowedAppsNow.value
        )
    }

    private fun loadPendingRequest() {
        viewModelScope.launch {
            try {
                val pending = timeRequestDao.getPendingRequestsFlow()
                    .first()
                    .firstOrNull { it.status == "PENDING" }
                _pendingTimeRequest.value = pending
            } catch (e: Exception) {
                Log.e(TAG, "Error loading pending request: ${e.message}")
            }
        }
    }

    private fun loadRewardBalance() {
        viewModelScope.launch {
            try {
                val balance = rewardManager.getRewardBalance()
                _rewardBalance.value = balance

                if (balance > _lastKnownRewardBalance && _lastKnownRewardBalance > 0) {
                    val newRewardMinutes = (balance - _lastKnownRewardBalance).toInt()
                    _events.emit(ChildStatusEvent.NewRewardReceived(newRewardMinutes, balance))
                }
                _lastKnownRewardBalance = balance
            } catch (e: Exception) {
                Log.e(TAG, "Error loading reward balance: ${e.message}")
            }
        }
    }

    fun refresh() {
        Log.d(TAG, "Refreshing status")
        loadInitialState()
        checkDegradation()
    }

    fun updateFromRealtime(data: RealtimeStatusUpdate) {
        viewModelScope.launch {
            _timeUsedToday.value = data.timeUsedMinutes
            _dailyLimit.value = data.dailyLimitMinutes
            _allowedAppsNow.value = data.allowedApps
            _pendingTimeRequest.value = data.pendingRequest

            calculateTimeRemaining()
            updateUiState()

            if (data.warningTriggered != null) {
                _events.emit(ChildStatusEvent.ShowWarning(data.warningTriggered))
            }
        }
    }

    fun requestExtraTime(minutes: Int, reason: String) {
        viewModelScope.launch {
            try {
                val request = TimeRequestEntity(
                    request_id = java.util.UUID.randomUUID().toString(),
                    device_id = "",
                    package_name = null,
                    minutes_requested = minutes,
                    reason = reason,
                    status = "PENDING",
                    created_at = timeProvider.wallInstant().toString(),
                    responded_at = null,
                    parent_response = null
                )

                timeRequestDao.insertRequest(request)
                _pendingTimeRequest.value = request

                _events.emit(ChildStatusEvent.TimeRequestSent)
                updateUiState()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating time request: ${e.message}")
                _events.emit(ChildStatusEvent.Error("Error enviando solicitud"))
            }
        }
    }

    fun onRepairTapped(cause: DegradationAlertManager.DegradationCause) {
        degradationAlertManager.onRepairTapped(cause.issueType)
    }

    fun dismissRecoveryDialog() {
        _showRecoveryDialog.value = false
    }

    fun formatTimeRemaining(minutes: Long): String {
        return when {
            minutes <= 0 -> "0 min"
            minutes < 60 -> "$minutes min"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins > 0) "${hours}h ${mins}min" else "${hours}h"
            }
        }
    }

    fun getTimeUntilNextBlock(): Duration? {
        val nextBlock = _nextBlockTime.value ?: return null
        val now = timeProvider.wallInstant()
        val diff = Duration.between(now, nextBlock)
        return if (diff.isNegative) null else diff
    }

    fun hasTimeRemaining(): Boolean = _timeRemaining.value > 0

    fun getUsagePercentage(): Float {
        val limit = _dailyLimit.value
        if (limit <= 0) return 0f
        return (_timeUsedToday.value.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    }
}

sealed class ChildStatusUiState {
    data object Loading : ChildStatusUiState()

    data class Content(
        val timeRemaining: Long,
        val timeUsedToday: Long,
        val dailyLimit: Long,
        val nextBlockTime: Instant?,
        val warningLevel: WarningLevel,
        val hasPendingRequest: Boolean,
        val allowedAppsNow: List<String>
    ) : ChildStatusUiState()

    data class Error(val message: String) : ChildStatusUiState()
}

enum class WarningLevel {
    NONE,
    WARNING,
    CRITICAL,
    BLOCKED
}

/**
 * Sum of the `minutes` field for grants whose `expires_at` is still in the
 * future. Anything expired is excluded. Mirrors the filter used by
 * [com.tudominio.parentalcontrol.data.repository.TimeExtraRepository.getActiveExtraTimeGrant]
 * so the home screen `timeRemaining` agrees with the `+15 min extra`
 * chip / countdown rendered by `TimeExtraViewModel`.
 */
private fun List<GrantEntity>.sumActiveMinutes(timeProvider: TimeProvider): Long {
    val now = timeProvider.wallInstant().toString()
    return filter { it.expires_at > now }.sumOf { it.minutes.toLong() }
}

sealed class ChildStatusEvent {
    data class ShowWarning(val level: WarningLevel) : ChildStatusEvent()
    data object TimeRequestSent : ChildStatusEvent()
    data class Error(val message: String) : ChildStatusEvent()
    data class NewRewardReceived(val minutes: Int, val totalBalance: Long) : ChildStatusEvent()
    data class DegradationDetected(val causes: List<DegradationAlertManager.DegradationCause>) : ChildStatusEvent()
}

data class RealtimeStatusUpdate(
    val timeUsedMinutes: Long,
    val dailyLimitMinutes: Long,
    val allowedApps: List<String>,
    val pendingRequest: TimeRequestEntity?,
    val warningTriggered: WarningLevel?
)
