package com.example.parentalcontrol.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// =============================================================================
// T03 — Persistencia local con Room (fuente de verdad offline)
// =============================================================================

@Entity(tableName = "policy")
data class PolicyEntity(
    @PrimaryKey val device_id: String,
    val version: Long,
    val category_assignments: Map<String, String>
)

@Entity(tableName = "app_policies", primaryKeys = ["device_id", "package_name"])
data class AppPolicyEntity(
    val device_id: String,
    val package_name: String,
    val state: String,
    val daily_limit_minutes: Int?,
    val allowed_windows: List<WindowEntity>,
    val category: String?
)

data class WindowEntity(
    val days: List<String>,
    val from: String,
    val to: String
)

@Entity(tableName = "grants")
data class GrantEntity(
    @PrimaryKey val id: String,
    val device_id: String,
    val request_id: String?,
    val scope: String,
    val minutes: Int,
    val source: String,
    val granted_at: String,
    val expires_at: String
)

@Entity(tableName = "usage_today", primaryKeys = ["package_name", "server_date"])
data class UsageTodayEntity(
    val package_name: String,
    val server_date: String,
    val usage_minutes: Int
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val tipo: String,
    val payload_json: String,
    val dedup_key: String?,
    val retries: Int = 0,
    val created_at: String,
    val server_date: String,
    val processed: Boolean = false,
    val processed_at: String? = null
)

@Entity(tableName = "time_requests")
data class TimeRequestEntity(
    @PrimaryKey val request_id: String,
    val device_id: String,
    val package_name: String?,
    val minutes_requested: Int,
    val reason: String?,
    val status: String,
    val created_at: String,
    val responded_at: String?,
    val parent_response: String?
)

@Entity(tableName = "behavioral_events")
data class BehavioralEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event_type: String,
    val event_version: Int = 1,
    val device_id: String,
    val client_ts: String,
    val props: String,
    val synced: Boolean = false,
    val created_at: String
)

// =============================================================================
// DAOs
// =============================================================================

@Dao
interface PolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: PolicyEntity)

    @Query("SELECT * FROM policy WHERE device_id = :deviceId")
    fun getPolicyFlow(deviceId: String): Flow<PolicyEntity?>

    @Query("SELECT version FROM policy WHERE device_id = :deviceId")
    suspend fun getLocalVersion(deviceId: String): Long?

    @Transaction
    suspend fun upsertPolicyIfNewer(policy: PolicyEntity): Boolean {
        val localVersion = getLocalVersion(policy.device_id)
        return if (localVersion == null || policy.version > localVersion) {
            insertPolicy(policy)
            true
        } else {
            false
        }
    }

    @Query("DELETE FROM policy WHERE device_id = :deviceId")
    suspend fun deletePolicy(deviceId: String)
}

@Dao
interface AppPolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppPolicy(appPolicy: AppPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppPolicies(appPolicies: List<AppPolicyEntity>)

    @Query("SELECT * FROM app_policies WHERE package_name = :packageName")
    suspend fun getAppPolicy(packageName: String): AppPolicyEntity?

    @Query("SELECT * FROM app_policies WHERE device_id = :deviceId")
    fun getAppPoliciesForDeviceFlow(deviceId: String): Flow<List<AppPolicyEntity>>

    @Query("SELECT * FROM app_policies")
    fun getAllAppPoliciesFlow(): Flow<List<AppPolicyEntity>>

    @Query("DELETE FROM app_policies WHERE device_id = :deviceId")
    suspend fun deleteAppPoliciesForDevice(deviceId: String)
}

@Dao
interface GrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrant(grant: GrantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrants(grants: List<GrantEntity>)

    @Query("SELECT * FROM grants WHERE device_id = :deviceId")
    fun getGrantsForDeviceFlow(deviceId: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND scope = :scope")
    fun getGrantsForScopeFlow(deviceId: String, scope: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE scope = :scope")
    fun getGrantsForScope(scope: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND expires_at > :now")
    fun getActiveGrantsFlow(deviceId: String, now: String): Flow<List<GrantEntity>>

    @Query("DELETE FROM grants WHERE device_id = :deviceId")
    suspend fun deleteGrantsForDevice(deviceId: String)

    @Query("DELETE FROM grants WHERE expires_at < :now")
    suspend fun deleteExpiredGrants(now: String)
}

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: UsageTodayEntity)

    @Query("SELECT * FROM usage_today WHERE package_name = :packageName AND server_date = :serverDate")
    suspend fun getUsage(packageName: String, serverDate: String): UsageTodayEntity?

    @Query("SELECT * FROM usage_today WHERE server_date = :serverDate")
    fun getUsageForDateFlow(serverDate: String): Flow<List<UsageTodayEntity>>

    @Query("SELECT SUM(usage_minutes) FROM usage_today WHERE server_date = :serverDate")
    fun getGlobalUsageFlow(serverDate: String): Flow<Int?>

    @Query(
        """
        SELECT SUM(u.usage_minutes)
        FROM usage_today u
        INNER JOIN app_policies a ON u.package_name = a.package_name
        WHERE u.server_date = :serverDate
          AND a.category = :category
          AND a.state != 'ALWAYS_ALLOWED'
        """
    )
    fun getCategoryUsageFlow(serverDate: String, category: String): Flow<Int?>

    @Query("SELECT usage_minutes FROM usage_today WHERE package_name = :packageName AND server_date = :serverDate")
    fun getUsageForPackageFlow(packageName: String, serverDate: String): Flow<Int?>

    @Transaction
    suspend fun incrementUsage(packageName: String, serverDate: String, deltaMinutes: Int) {
        val existing = getUsage(packageName, serverDate)
        if (existing != null) {
            upsertUsage(existing.copy(usage_minutes = existing.usage_minutes + deltaMinutes))
        } else {
            upsertUsage(
                UsageTodayEntity(
                    package_name = packageName,
                    server_date = serverDate,
                    usage_minutes = deltaMinutes
                )
            )
        }
    }

    @Query("DELETE FROM usage_today WHERE server_date < :cutoffDate")
    suspend fun deleteOldUsage(cutoffDate: String)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxItem(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE dedup_key = :dedupKey AND dedup_key IS NOT NULL LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE processed = 0 AND retries < :maxAttempts ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingItems(maxAttempts: Int, limit: Int): List<OutboxEntity>

    @Query("UPDATE outbox SET retries = retries + 1 WHERE id = :id")
    suspend fun incrementRetries(id: UUID)

    @Query("UPDATE outbox SET processed = 1, processed_at = :processedAt WHERE id = :id")
    suspend fun markProcessed(id: UUID, processedAt: String)

    @Query("DELETE FROM outbox WHERE processed = 1 AND processed_at < :cutoff")
    suspend fun deleteProcessedOlderThan(cutoff: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteItem(id: UUID)

    @Query("DELETE FROM outbox WHERE retries >= :maxAttempts")
    suspend fun deleteFailedItems(maxAttempts: Int)

    @Query("SELECT COUNT(*) FROM outbox")
    fun getPendingCountFlow(): Flow<Int>
}

@Dao
interface TimeRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: TimeRequestEntity)

    @Query("SELECT * FROM time_requests WHERE request_id = :requestId")
    suspend fun getRequestById(requestId: String): TimeRequestEntity?

    @Query("SELECT * FROM time_requests WHERE device_id = :deviceId ORDER BY created_at DESC")
    fun getRequestsForDeviceFlow(deviceId: String): Flow<List<TimeRequestEntity>>

    @Query("SELECT * FROM time_requests WHERE status = 'PENDING' ORDER BY created_at DESC")
    fun getPendingRequestsFlow(): Flow<List<TimeRequestEntity>>

    @Query("UPDATE time_requests SET status = :status, responded_at = :respondedAt WHERE request_id = :requestId")
    suspend fun updateRequestStatus(requestId: String, status: String, respondedAt: String)

    @Query("DELETE FROM time_requests WHERE created_at < :cutoff")
    suspend fun deleteOldRequests(cutoff: String)
}

// =============================================================================
// Database
// =============================================================================

@Database(
    entities = [
        PolicyEntity::class,
        AppPolicyEntity::class,
        GrantEntity::class,
        UsageTodayEntity::class,
        OutboxEntity::class,
        TimeRequestEntity::class,
        BehavioralEventEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun policyDao(): PolicyDao
    abstract fun appPolicyDao(): AppPolicyDao
    abstract fun grantDao(): GrantDao
    abstract fun usageDao(): UsageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun timeRequestDao(): TimeRequestDao
    abstract fun behavioralEventDao(): BehavioralEventDao

    companion object {
        const val DATABASE_NAME = "parental_control.db"

        /**
         * Migration v4 -> v5 on the `outbox` table.
         *
         * - Adds `processed INTEGER NOT NULL DEFAULT 0`
         * - Adds `processed_at TEXT` (nullable)
         * - Renames `intentos` -> `retries` (value carried forward)
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN processed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outbox ADD COLUMN processed_at TEXT")
                db.execSQL("ALTER TABLE outbox RENAME COLUMN intentos TO retries")
            }
        }

        /**
         * Migration v5 -> v6 on the `app_policies` table.
         *
         * - Changes primary key from `package_name` alone to composite
         *   `(device_id, package_name)` so the same package may have distinct
         *   policy rows per child device.
         * - SQLite does not support `ALTER TABLE ... DROP PRIMARY KEY`, so
         *   the table is recreated and its rows are copied across.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE app_policies_new (" +
                        "device_id TEXT NOT NULL, " +
                        "package_name TEXT NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "daily_limit_minutes INTEGER, " +
                        "allowed_windows TEXT NOT NULL, " +
                        "category TEXT, " +
                        "PRIMARY KEY (device_id, package_name))"
                )
                db.execSQL(
                    "INSERT INTO app_policies_new (device_id, package_name, state, " +
                        "daily_limit_minutes, allowed_windows, category) " +
                        "SELECT device_id, package_name, state, daily_limit_minutes, " +
                        "allowed_windows, category " +
                        "FROM app_policies"
                )
                db.execSQL("DROP TABLE app_policies")
                db.execSQL("ALTER TABLE app_policies_new RENAME TO app_policies")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
