package kr.jm.moalog.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

class MoaLogDatabaseMigrationTest {
    @Test
    fun migration_1_to_2IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }

    @Test
    fun migration_2_to_3IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun migration_3_to_4IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test
    fun migration_4_to_5IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }

    @Test
    fun migration_5_to_6IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(5, MIGRATION_5_6.startVersion)
        assertEquals(6, MIGRATION_5_6.endVersion)
    }

    @Test
    fun migration_6_to_7IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(6, MIGRATION_6_7.startVersion)
        assertEquals(7, MIGRATION_6_7.endVersion)
    }

    @Test
    fun migration_7_to_8IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(7, MIGRATION_7_8.startVersion)
        assertEquals(8, MIGRATION_7_8.endVersion)
    }

    @Test
    fun migration_8_to_9IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(8, MIGRATION_8_9.startVersion)
        assertEquals(9, MIGRATION_8_9.endVersion)
    }

    @Test
    fun migration_9_to_10IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(9, MIGRATION_9_10.startVersion)
        assertEquals(10, MIGRATION_9_10.endVersion)
    }

    @Test
    fun migration_10_to_11IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(10, MIGRATION_10_11.startVersion)
        assertEquals(11, MIGRATION_10_11.endVersion)
    }

    @Test
    fun migration_11_to_12IsRegisteredAndTargetsExpectedVersions() {
        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)
    }
}
