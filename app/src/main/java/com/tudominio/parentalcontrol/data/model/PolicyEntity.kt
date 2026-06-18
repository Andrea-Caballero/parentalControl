package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "policy")
data class PolicyEntity(
    @PrimaryKey val device_id: String,
    val version: Long,
    val category_assignments: Map<String, String>
)
