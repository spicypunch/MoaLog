package kr.jm.moalog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoaLogDatabase::class.java)

    @Test
    fun migration12To13CreatesDurableSyncStateWithRequiredUniqueness() {
        val name = "sync-migration-${System.nanoTime()}"
        helper.createDatabase(name, 12).close()

        helper.runMigrationsAndValidate(name, 13, true, MIGRATION_12_13).use { db ->
            db.execSQL("INSERT INTO sync_installation(slot, deviceId) VALUES(0, '00000000-0000-4000-8000-000000000002')")
            db.execSQL("INSERT INTO sync_household_bindings(localLedgerId, householdId) VALUES(1, '00000000-0000-4000-8000-000000000001')")
            db.execSQL("INSERT INTO sync_cursors(householdId, cursor) VALUES('00000000-0000-4000-8000-000000000001', 41)")
            db.execSQL("INSERT INTO sync_identities(householdId, entityType, localKey, entityUuid, serverVersion, syncedPayload, deleted) VALUES('00000000-0000-4000-8000-000000000001', 'EXPENSE_RECORD', '7', '00000000-0000-4000-8000-000000000007', 3, '{}', 0)")
            db.execSQL("INSERT INTO sync_outbox(mutationId, householdId, deviceId, entityType, entityUuid, operation, baseVersion, payloadJson, attemptCount, nextAttemptAtEpochMillis, lastError, createdAtEpochMillis) VALUES('00000000-0000-4000-8000-000000000008', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', 'EXPENSE_RECORD', '00000000-0000-4000-8000-000000000007', 'UPSERT', 3, '{}', 2, 1000, 'offline', 10)")

            db.query("SELECT cursor FROM sync_cursors").use { cursor ->
                cursor.moveToFirst()
                assertEquals(41L, cursor.getLong(0))
            }
            db.query("SELECT attemptCount, lastError FROM sync_outbox").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
                assertEquals("offline", cursor.getString(1))
            }
        }
    }
}
