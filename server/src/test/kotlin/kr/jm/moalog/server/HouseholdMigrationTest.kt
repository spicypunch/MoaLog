package kr.jm.moalog.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class HouseholdMigrationTest(
    @param:Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `household migration creates every household table`() {
        val names = jdbcTemplate.queryForList(
            """
            select table_name from information_schema.tables
            where table_schema = 'MOALOG' and table_name in (
                'HOUSEHOLDS', 'LEDGER_MEMBERS', 'HOUSEHOLD_MEMBERSHIPS',
                'ANNUAL_SAVINGS_TARGETS', 'HOUSEHOLD_INVITATIONS'
            ) order by table_name
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(
            listOf(
                "ANNUAL_SAVINGS_TARGETS",
                "HOUSEHOLDS",
                "HOUSEHOLD_INVITATIONS",
                "HOUSEHOLD_MEMBERSHIPS",
                "LEDGER_MEMBERS",
            ),
            names,
        )
    }

    @Test
    fun `user foreign keys prevent hard deletion from breaking household ownership and invitation history`() {
        val deleteRules = jdbcTemplate.query(
            """
            select constraint_name, delete_rule from information_schema.referential_constraints
            where constraint_schema = 'MOALOG' and constraint_name in (
                'FK_HOUSEHOLD_MEMBERSHIPS_USER',
                'FK_HOUSEHOLD_INVITATIONS_CREATED_BY',
                'FK_HOUSEHOLD_INVITATIONS_ACCEPTED_BY'
            )
            """.trimIndent(),
        ) { resultSet, _ -> resultSet.getString("CONSTRAINT_NAME") to resultSet.getString("DELETE_RULE") }
            .toMap()

        assertEquals(
            mapOf(
                "FK_HOUSEHOLD_MEMBERSHIPS_USER" to "RESTRICT",
                "FK_HOUSEHOLD_INVITATIONS_CREATED_BY" to "RESTRICT",
                "FK_HOUSEHOLD_INVITATIONS_ACCEPTED_BY" to "RESTRICT",
            ),
            deleteRules,
        )
    }
}
