package kr.jm.moalog.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class SyncMigrationTest(
    @param:Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `offline sync migration creates snapshot change and idempotency tables`() {
        val names = jdbcTemplate.queryForList(
            """
            select table_name from information_schema.tables
            where table_schema = 'MOALOG' and table_name like 'SYNC_%'
            order by table_name
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(
            listOf("SYNC_CHANGE_LOG", "SYNC_ENTITY_SNAPSHOTS", "SYNC_PROCESSED_MUTATIONS"),
            names,
        )
    }
}
