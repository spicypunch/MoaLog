package kr.jm.moalog.core.database

import androidx.test.platform.app.InstrumentationRegistry
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLocalStoreInstrumentedTest {
    @Test
    fun outboxAndIdentitySurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "sync-reopen-${System.nanoTime()}.db"
        val path = context.getDatabasePath(databaseName)
        val household = uuid(1)
        val device = uuid(2)
        var sequence = 10
        val ids = SyncUuidFactory { uuid(sequence++) }

        var database = buildMoaLogDatabase(androidx.room.Room.databaseBuilder(context, MoaLogDatabase::class.java, path.absolutePath))
        database.ledgerSetupDao().upsertLedger(LedgerEntity(1, "테스트", 2026, null))
        val expenses = createExpenseLocalDataSource(database)
        expenses.ensureDefaultCategories()
        val recordId = expenses.saveRecord(
            kr.jm.moalog.core.model.ExpenseRecord(
                categoryId = DefaultExpenseCategories.first().id,
                categoryName = DefaultExpenseCategories.first().name,
                attributionMonth = kr.jm.moalog.core.model.YearMonthKey(2026, 9),
                actualDate = null,
                detail = null,
                amountWon = 12_000,
                overspent = false,
            ),
        )
        val store = createSyncLocalStore(database)
        assertEquals(device, store.persistentDeviceId(SyncUuidFactory { device }))
        store.requirePersistentDeviceId(device)
        store.bindHousehold(household)
        assertEquals(household, store.boundHouseholdId())
        store.captureLocalChanges(household, device, 100, ids)
        val before = store.readyMutations(household, 100)
        assertTrue(before.any { it.mutation.entityType == SyncEntityType.EXPENSE_RECORD })
        val recordMutation = before.single { it.mutation.entityType == SyncEntityType.EXPENSE_RECORD }
        assertEquals(recordId.toString(), database.syncDao().identityByUuid(household.value, SyncEntityType.EXPENSE_RECORD.name, recordMutation.mutation.entityId.value)?.localKey)
        database.close()

        database = buildMoaLogDatabase(androidx.room.Room.databaseBuilder(context, MoaLogDatabase::class.java, path.absolutePath))
        val reopened = createSyncLocalStore(database).readyMutations(household, 100)
        assertEquals(before.map { it.mutationId }.toSet(), reopened.map { it.mutationId }.toSet())
        assertEquals(device, reopened.first().deviceId)
        assertNotEquals(reopened.first().mutation.entityId, reopened.first().mutationId)
        database.close()
        assertTrue(context.deleteDatabase(databaseName))
    }
}

private fun uuid(number: Int) = UuidString("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
