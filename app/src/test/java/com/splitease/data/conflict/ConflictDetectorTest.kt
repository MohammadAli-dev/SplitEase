package com.splitease.data.conflict

import com.splitease.data.local.entities.LedgerOperation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConflictDetector].
 *
 * Verifies Sprint 20 invariants:
 * - Fingerprint stability and determinism
 * - Classification rules (closed table)
 * - Output ordering (lexicographical by Type, ID, Hash)
 * - Single-device operations do not produce conflicts
 */
class ConflictDetectorTest {

    private lateinit var detector: ConflictDetector

    @Before
    fun setup() {
        detector = ConflictDetector()
    }

    // Helper to create a test ledger operation
    private fun createOp(
        entityId: String,
        entityType: String,
        operationType: String,
        deviceId: String,
        logicalClock: Long
    ) = LedgerOperation(
        operationId = "$entityId-$deviceId-$logicalClock",
        entityType = entityType,
        entityId = entityId,
        operationType = operationType,
        payload = "{}",
        authorLocalUserId = "user1",
        deviceId = deviceId,
        logicalClock = logicalClock,
        createdAt = System.currentTimeMillis()
    )

    @Test
    fun `single device operations do not produce conflicts`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "CREATE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 2),
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 3)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertTrue("Single device should not produce conflicts", conflicts.isEmpty())
    }

    @Test
    fun `multiple devices produce MULTIPLE_WRITERS conflict`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "CREATE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals("Should detect one conflict", 1, conflicts.size)
        assertEquals(ConflictType.MULTIPLE_WRITERS, conflicts[0].conflictType)
        assertEquals("exp1", conflicts[0].entityId)
        assertEquals(EntityType.EXPENSE, conflicts[0].entityType)
        assertEquals(2, conflicts[0].opRefs.size)
    }

    @Test
    fun `DELETE plus UPDATE produces POST_DELETE_MUTATION conflict`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "DELETE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals("Should detect one conflict", 1, conflicts.size)
        assertEquals(ConflictType.POST_DELETE_MUTATION, conflicts[0].conflictType)
    }

    @Test
    fun `DELETE plus CREATE produces POST_DELETE_MUTATION conflict`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "DELETE", "device-A", 1),
            createOp("exp1", "EXPENSE", "CREATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals("Should detect one conflict", 1, conflicts.size)
        assertEquals(ConflictType.POST_DELETE_MUTATION, conflicts[0].conflictType)
    }

    @Test
    fun `multiple DELETEs produce HARD_DELETE_CLASH conflict`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "DELETE", "device-A", 1),
            createOp("exp1", "EXPENSE", "DELETE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals("Should detect one conflict", 1, conflicts.size)
        assertEquals(ConflictType.HARD_DELETE_CLASH, conflicts[0].conflictType)
    }

    @Test
    fun `conflictId is deterministic across different input orders`() {
        val opsOrder1 = listOf(
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1)
        )

        val opsOrder2 = listOf(
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 1)
        )

        val conflicts1 = detector.detect(LedgerPrefix.fromConvergedReplay(opsOrder1))
        val conflicts2 = detector.detect(LedgerPrefix.fromConvergedReplay(opsOrder2))

        assertEquals("Same operations should produce identical conflictId", 
            conflicts1[0].conflictId, conflicts2[0].conflictId)
    }

    @Test
    fun `opRefs are sorted by deviceId then logicalClock`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 2),
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals(1, conflicts.size)
        val opRefs = conflicts[0].opRefs
        
        // Should be sorted: device-A:1, device-B:1, device-B:2
        assertEquals("device-A", opRefs[0].deviceId)
        assertEquals(1L, opRefs[0].logicalClock)
        assertEquals("device-B", opRefs[1].deviceId)
        assertEquals(1L, opRefs[1].logicalClock)
        assertEquals("device-B", opRefs[2].deviceId)
        assertEquals(2L, opRefs[2].logicalClock)
    }

    @Test
    fun `output is sorted lexicographically by entityType entityId conflictId`() {
        val ops = listOf(
            // Group conflict
            createOp("grp1", "GROUP", "UPDATE", "device-A", 1),
            createOp("grp1", "GROUP", "UPDATE", "device-B", 1),
            // Expense conflict (should come after GROUP in output)
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals(2, conflicts.size)
        // EXPENSE comes before GROUP lexicographically
        assertEquals(EntityType.EXPENSE, conflicts[0].entityType)
        assertEquals(EntityType.GROUP, conflicts[1].entityType)
    }

    @Test
    fun `duplicate operations are deduplicated`() {
        val ops = listOf(
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 1),
            createOp("exp1", "EXPENSE", "UPDATE", "device-A", 1), // Duplicate
            createOp("exp1", "EXPENSE", "UPDATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertEquals(1, conflicts.size)
        assertEquals(2, conflicts[0].opRefs.size) // Only 2 unique opRefs
    }

    @Test
    fun `unknown entity types are skipped`() {
        val ops = listOf(
            createOp("unknown1", "UNKNOWN_TYPE", "UPDATE", "device-A", 1),
            createOp("unknown1", "UNKNOWN_TYPE", "UPDATE", "device-B", 1)
        )

        val prefix = LedgerPrefix.fromConvergedReplay(ops)
        val conflicts = detector.detect(prefix)

        assertTrue("Unknown entity types should not produce conflicts", conflicts.isEmpty())
    }

    @Test
    fun `empty operations produce no conflicts`() {
        val prefix = LedgerPrefix.fromConvergedReplay(emptyList())
        val conflicts = detector.detect(prefix)

        assertTrue(conflicts.isEmpty())
    }
}
