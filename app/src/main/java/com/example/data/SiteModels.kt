package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "monitored_sites")
data class MonitoredSite(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
    val isNotificationsEnabled: Boolean = true,
    val lastCheckedTime: Long = 0L,
    val lastContentHash: String = "",
    val useAiSmartScan: Boolean = false
) : Serializable

@Entity(tableName = "update_records")
data class UpdateRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val siteId: Int,
    val siteName: String,
    val title: String,
    val type: String, // e.g., "خبر", "تقرير", "منشور", "تحديث"
    val discoveryTime: Long = System.currentTimeMillis(),
    val briefDescription: String,
    val url: String,
    val isRead: Boolean = false,
    val publishDate: String = ""
) : Serializable
