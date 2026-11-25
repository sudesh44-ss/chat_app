package com.nexa.app

import com.nexa.app.core.sms.SmsSchemaDdl
import com.nexa.app.data.local.entity.SmsInboundPartEntity
import com.nexa.app.data.local.entity.SmsKeyMaterialEntity
import com.nexa.app.data.local.entity.SmsPacketEntity
import com.nexa.app.data.local.entity.SmsSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Module 3 — migration/schema consistency (JVM).
 *
 * The v27→v28 migration is hand-written SQL while Room validates the *entities* at runtime, so
 * the classic failure is drift between the two. This test derives the expected columns from the
 * Room entities themselves (Java reflection over the data-class fields) and compares them with
 * the exact DDL the migration executes.
 *
 * This is not a substitute for running the migration on a device against a populated v27
 * database; that remains NOT TESTED (see the instrumentation test and the report).
 */
class Module3SmsSchemaTests {

    private data class TableExpectation(
        val table: String,
        val entity: Class<*>,
        val primaryKey: String
    )

    private val tables = listOf(
        TableExpectation(SmsSchemaDdl.TABLE_SESSIONS, SmsSessionEntity::class.java, "peerNxId"),
        TableExpectation(SmsSchemaDdl.TABLE_KEY_MATERIAL, SmsKeyMaterialEntity::class.java, "keyId"),
        TableExpectation(SmsSchemaDdl.TABLE_PACKETS, SmsPacketEntity::class.java, "messageId"),
        TableExpectation(SmsSchemaDdl.TABLE_INBOUND_PARTS, SmsInboundPartEntity::class.java, "id")
    )

    private val columnPattern =
        Regex("^\\s*`([A-Za-z0-9_]+)`\\s+(TEXT|INTEGER|REAL|BLOB)", RegexOption.MULTILINE)

    private fun ddlFor(table: String): String =
        SmsSchemaDdl.statements.first { it.contains("CREATE TABLE IF NOT EXISTS `$table`") }

    private fun sqlType(type: Class<*>): String = when (type) {
        String::class.java -> "TEXT"
        java.lang.Long::class.java, java.lang.Long.TYPE,
        java.lang.Integer::class.java, java.lang.Integer.TYPE,
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> "INTEGER"
        else -> error("Unmapped column type: $type")
    }

    private fun entityColumns(entity: Class<*>): Map<String, String> =
        entity.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .associate { it.name to sqlType(it.type) }

    @Test
    fun migrationCreatesEverySmsTable() {
        tables.forEach { expectation ->
            val ddl = ddlFor(expectation.table)
            assertTrue(
                "migration must create ${expectation.table}",
                ddl.contains("CREATE TABLE IF NOT EXISTS `${expectation.table}`")
            )
        }
    }

    @Test
    fun migrationDdlColumnsMatchRoomEntitiesExactly() {
        tables.forEach { expectation ->
            val ddlColumns = columnPattern.findAll(ddlFor(expectation.table))
                .associate { it.groupValues[1] to it.groupValues[2] }
            val entityColumns = entityColumns(expectation.entity)

            assertEquals(
                "column set drifted for ${expectation.table}",
                entityColumns.keys.sorted(),
                ddlColumns.keys.sorted()
            )
            entityColumns.forEach { (column, expectedType) ->
                assertEquals(
                    "type drifted for ${expectation.table}.$column",
                    expectedType,
                    ddlColumns[column]
                )
            }
        }
    }

    @Test
    fun everyTableDeclaresItsPrimaryKey() {
        tables.forEach { expectation ->
            val ddl = ddlFor(expectation.table)
            val declaredAsTableConstraint = ddl.contains("PRIMARY KEY(`${expectation.primaryKey}`)")
            val declaredInline = ddl.contains("`${expectation.primaryKey}` INTEGER PRIMARY KEY")
            assertTrue(
                "${expectation.table} must declare ${expectation.primaryKey} as its primary key",
                declaredAsTableConstraint || declaredInline
            )
        }
    }

    @Test
    fun inboundPartsEnforceUniquePartIndex() {
        val ddl = ddlFor(SmsSchemaDdl.TABLE_INBOUND_PARTS)
        assertTrue("id is autoincrement", ddl.contains("AUTOINCREMENT"))
        val unique = SmsSchemaDdl.statements.first {
            it.contains("UNIQUE INDEX") && it.contains(SmsSchemaDdl.TABLE_INBOUND_PARTS)
        }
        assertTrue(unique.contains("`messageId`, `senderNxId`, `partIndex`"))
    }

    @Test
    fun packetTableDeclaresItsQueryIndexes() {
        val indexes = SmsSchemaDdl.statements.filter {
            it.contains("INDEX") && it.contains(SmsSchemaDdl.TABLE_PACKETS)
        }
        listOf("packetId", "peerNxId", "handoffState", "direction").forEach { column ->
            assertTrue("missing index on sms_packets.$column", indexes.any { it.contains("(`$column`)") })
        }
    }

    @Test
    fun migrationIsAdditiveAndIdempotent() {
        val statements = SmsSchemaDdl.statements
        assertEquals("expected 4 tables + indexes", 4, statements.count { it.contains("CREATE TABLE") })
        statements.forEach { statement ->
            assertTrue("all DDL must be idempotent: $statement", statement.contains("IF NOT EXISTS"))
            val upper = statement.uppercase()
            assertFalse("destructive statement in additive migration: $statement", upper.contains("DROP "))
            assertFalse("data mutation in schema migration: $statement", upper.contains("DELETE FROM"))
            assertFalse("column alteration in additive migration: $statement", upper.contains("ALTER TABLE"))
        }
    }

    @Test
    fun allSmsColumnsAreNotNullExceptOptionalFailureKind() {
        // failureKind is the only nullable column in the SMS schema; everything else is required
        // so a partially written row cannot silently lose its key version or counters.
        val columnLine = Regex("^\\s*`([A-Za-z0-9_]+)`\\s+(TEXT|INTEGER|REAL|BLOB)(.*)$", RegexOption.MULTILINE)
        val nullable = SmsSchemaDdl.statements.joinToString("\n")
            .let { columnLine.findAll(it) }
            .filter { !it.groupValues[3].uppercase().contains("NOT NULL") }
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(setOf("failureKind"), nullable)
    }
}
