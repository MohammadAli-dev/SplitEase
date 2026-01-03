package com.splitease.data.local.converters

import androidx.room.TypeConverter
import com.splitease.data.local.entities.SyncStatus
import com.splitease.data.local.entities.SyncEntityType
import java.math.BigDecimal
import java.util.Date

class Converters {
    @TypeConverter
    fun fromBigDecimal(value: BigDecimal?): String? {
        return value?.toPlainString()
    }

    @TypeConverter
    fun toBigDecimal(value: String?): BigDecimal? {
        return value?.let { BigDecimal(it) }
    }

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String {
        return status.name
    }

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus {
        return try {
            SyncStatus.valueOf(value)
        } catch (e: Exception) {
            SyncStatus.PENDING
        }
    }
    @TypeConverter
    fun fromSyncEntityType(type: SyncEntityType): String {
        return type.name
    }

    @TypeConverter
    fun toSyncEntityType(value: String): SyncEntityType {
        return try {
            SyncEntityType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Unknown SyncEntityType: $value")
        }
    }
}
