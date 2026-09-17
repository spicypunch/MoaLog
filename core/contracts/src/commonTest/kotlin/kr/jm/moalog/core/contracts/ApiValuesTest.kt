package kr.jm.moalog.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class ApiValuesTest {
    private val json = Json

    @Test
    fun uuid_serializes_as_a_lowercase_string() {
        val id = UuidString("123e4567-e89b-12d3-a456-426614174000")

        assertEquals("\"123e4567-e89b-12d3-a456-426614174000\"", json.encodeToString(id))
        assertEquals(id, json.decodeFromString<UuidString>(json.encodeToString(id)))
    }

    @Test
    fun uuid_rejects_noncanonical_values() {
        assertFailsWith<IllegalArgumentException> {
            UuidString("123E4567-E89B-12D3-A456-426614174000")
        }
        assertFailsWith<IllegalArgumentException> { UuidString("not-a-uuid") }
    }

    @Test
    fun year_month_round_trips_and_validates_month_range() {
        val month = YearMonthString("2026-09")

        assertEquals(month, json.decodeFromString<YearMonthString>(json.encodeToString(month)))
        assertFailsWith<IllegalArgumentException> { YearMonthString("2026-13") }
        assertFailsWith<IllegalArgumentException> { YearMonthString("26-09") }
    }

    @Test
    fun utc_instant_uses_rfc_3339_wire_format() {
        val instant = UtcInstantString("2026-09-14T12:34:56.123Z")

        assertEquals(instant, json.decodeFromString<UtcInstantString>(json.encodeToString(instant)))
        assertFailsWith<IllegalArgumentException> { UtcInstantString("2026-09-14T12:34:56+09:00") }
        assertFailsWith<IllegalArgumentException> { UtcInstantString("2026-02-29T12:34:56Z") }
        assertFailsWith<IllegalArgumentException> { UtcInstantString("2026-09-14T24:00:00Z") }
    }
}
