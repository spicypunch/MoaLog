package kr.jm.moalog.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class AuthMigrationTest(
    @param:Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `authentication migration creates all constrained tables`() {
        val names = jdbcTemplate.queryForList(
            """
            select table_name from information_schema.tables
            where table_schema = 'MOALOG' and table_name like 'AUTH_%'
            order by table_name
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(
            listOf(
                "AUTH_IDENTITIES",
                "AUTH_LOGIN_CHALLENGES",
                "AUTH_LOGIN_CHALLENGE_CAPACITY",
                "AUTH_REFRESH_TOKENS",
                "AUTH_SESSIONS",
            ),
            names,
        )
        val deletedAtColumn = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns where table_schema = 'MOALOG' and table_name = 'USERS' and column_name = 'DELETED_AT'",
            Int::class.java,
        )
        assertEquals(1, deletedAtColumn ?: 0)
    }
}
