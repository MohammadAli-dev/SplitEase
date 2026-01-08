package com.splitease.data.local.converters

import androidx.room.TypeConverter
import com.splitease.data.local.entities.ConnectionStatus
import com.splitease.data.local.entities.SyncStatus
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncFailureType
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
            throw IllegalStateException("Unknown SyncEntityType: $value", e)
        }
    }

    @TypeConverter
    fun fromSyncFailureType(type: SyncFailureType?): String? {
        return type?.name
    }

    /**
     * Converts a nullable string into the corresponding SyncFailureType enum.
     *
     * @param value The stored enum name, or null.
     * @return The matching `SyncFailureType`, or null if `value` is null.
     * @throws IllegalStateException if `value` is non-null but does not match any `SyncFailureType`.
     */
    @TypeConverter
    fun toSyncFailureType(value: String?): SyncFailureType? {
        return value?.let {
            try {
                SyncFailureType.valueOf(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Unknown SyncFailureType: $it", e)
            }
        }
    }

    /**
     * Convert a ConnectionStatus enum to its name string.
     *
     * @param status The ConnectionStatus value to convert.
     * @return The enum's `name` string.
     */
    @TypeConverter
    fun fromConnectionStatus(status: ConnectionStatus): String {
        return status.name
    }

    /**
     * Converts the provided enum name string to the corresponding [ConnectionStatus].
     *
     * @param value The enum name of the ConnectionStatus (e.g., the result of `ConnectionStatus.name`).
     * @return The matching [ConnectionStatus].
     * @throws IllegalStateException if `value` does not match any ConnectionStatus constant.
     */
    @TypeConverter
    fun toConnectionStatus(value: String): ConnectionStatus {
        return try {
            ConnectionStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Unknown ConnectionStatus: $value", e)
        }
    }
}