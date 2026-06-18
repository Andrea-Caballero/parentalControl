package com.example.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.parentalcontrol.data.model.BehavioralEventEntity

/**
 * DAO para eventos conductuales.
 */
@Dao
interface BehavioralEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: BehavioralEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<BehavioralEventEntity>)

    @Query("SELECT * FROM behavioral_events WHERE synced = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnsyncedEvents(limit: Int = 100): List<BehavioralEventEntity>

    @Query("UPDATE behavioral_events SET synced = 1 WHERE id IN (:eventIds)")
    suspend fun markSynced(eventIds: List<Long>)

    @Query("DELETE FROM behavioral_events WHERE synced = 1 AND created_at < datetime('now', '-' || :days || ' days')")
    suspend fun deleteOldSyncedEvents(days: Int = 7)

    @Query("SELECT COUNT(*) FROM behavioral_events WHERE synced = 0")
    suspend fun getUnsyncedCount(): Int

    @Query("DELETE FROM behavioral_events")
    suspend fun deleteAll()
}
