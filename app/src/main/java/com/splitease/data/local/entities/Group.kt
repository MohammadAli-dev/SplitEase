package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expense_groups")
data class Group(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val coverUrl: String? = null,
    val createdBy: String,
    /** Whether trip date range is enabled (TRIP type only) */
    val hasTripDates: Boolean = false,
    /** Trip start date (epoch millis), null if hasTripDates = false */
    val tripStartDate: Long? = null,
    /** Trip end date (epoch millis), null if hasTripDates = false */
    val tripEndDate: Long? = null
)
