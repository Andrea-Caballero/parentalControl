package com.example.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

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
