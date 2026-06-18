package com.example.parentalcontrol.data.model

/**
 * Represents a recurring time window (days + time range) when an app is allowed.
 * Persisted as JSON by [com.example.parentalcontrol.data.db.Converters].
 */
data class WindowEntity(
    val days: List<String>,
    val from: String,
    val to: String
)
