package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_operations")
data class SyncOperation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val operationType: String, // CREATE, UPDATE, DELETE
    val entityType: String, // EXPENSE, GROUP, MEMBER
    val entityId: String,
    val payload: String, // JSON payload
    val timestamp: Long = System.currentTimeMillis()
)
