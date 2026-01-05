package com.splitease.data.sync

import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.domain.MoneyFormatter
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explicit adapter for EXPENSE reconciliation.
 * Deterministic field order, no reflection.
 */
object ExpenseSnapshotAdapter {

    private val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    /**
     * Compare local and remote expense data to produce diff fields.
     * Remote can be null if entity not found on server.
     */
    fun compare(
        local: Expense,
        localSplits: List<ExpenseSplit>,
        remote: Expense?,
        remoteSplits: List<ExpenseSplit>?
    ): EntitySnapshot {
        val fields = mutableListOf<SnapshotField>()

        // AMOUNTS section
        fields.add(SnapshotField(
            key = "amount",
            label = "Total Amount",
            section = FieldSection.AMOUNTS,
            localValue = formatAmount(local.amount, local.currency),
            remoteValue = remote?.let { formatAmount(it.amount, it.currency) }
        ))

        fields.add(SnapshotField(
            key = "currency",
            label = "Currency",
            section = FieldSection.AMOUNTS,
            localValue = local.currency,
            remoteValue = remote?.currency
        ))

        // PARTICIPANTS section
        fields.add(SnapshotField(
            key = "payer",
            label = "Paid By",
            section = FieldSection.PARTICIPANTS,
            localValue = local.payerId,
            remoteValue = remote?.payerId
        ))

        fields.add(SnapshotField(
            key = "splits",
            label = "Split Between",
            section = FieldSection.PARTICIPANTS,
            localValue = formatSplits(localSplits),
            remoteValue = remoteSplits?.let { formatSplits(it) }
        ))

        // METADATA section
        fields.add(SnapshotField(
            key = "title",
            label = "Title",
            section = FieldSection.METADATA,
            localValue = local.title,
            remoteValue = remote?.title
        ))

        fields.add(SnapshotField(
            key = "expenseDate",
            label = "Expense Date",
            section = FieldSection.METADATA,
            localValue = formatDate(local.expenseDate),
            remoteValue = remote?.let { formatDate(it.expenseDate) }
        ))

        return EntitySnapshot(
            entityType = SyncEntityType.EXPENSE,
            displayTitle = local.title,
            fields = fields
        )
    }

    // --- Formatting Helpers ---

    private fun formatAmount(amount: BigDecimal, currency: String): String {
        return MoneyFormatter.format(amount)
    }

    private fun formatSplits(splits: List<ExpenseSplit>): String {
        if (splits.isEmpty()) return "None"
        return splits
            .sortedBy { it.userId }
            .joinToString(", ") { "${it.userId}: ${MoneyFormatter.format(it.amount)}" }
    }

    private fun formatDate(timestamp: Long): String {
        return dateFormatter.format(Date(timestamp))
    }

    /**
     * Count stats for telemetry logging.
     */
    fun computeDiffStats(snapshot: EntitySnapshot): DiffStats {
        val changedFields = snapshot.fields.count { it.isDifferent }
        val sectionsChanged = snapshot.fields
            .filter { it.isDifferent }
            .map { it.section }
            .distinct()
        return DiffStats(
            totalFields = snapshot.fields.size,
            changedFields = changedFields,
            sectionsChanged = sectionsChanged
        )
    }
}

data class DiffStats(
    val totalFields: Int,
    val changedFields: Int,
    val sectionsChanged: List<FieldSection>
)
