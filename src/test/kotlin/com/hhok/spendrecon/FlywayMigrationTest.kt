package com.hhok.spendrecon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@SpringBootTest
class FlywayMigrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var jpaProperties: JpaProperties

    @Autowired
    private lateinit var hibernateProperties: HibernateProperties

    @Test
    fun `spring context starts with flyway validate and open-in-view off`() {
        assertThat(hibernateProperties.ddlAuto).isEqualTo("validate")
        assertThat(jpaProperties.openInView).isFalse()

        val expected = classpathMigrationVersions()
        assertThat(expected).isNotEmpty()

        val failed =
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                Long::class.java,
            )
        assertThat(failed).isZero()

        val applied =
            jdbc.queryForList(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                """.trimIndent(),
                String::class.java,
            ).map { it.toInt() }
        assertThat(applied).containsExactlyInAnyOrderElementsOf(expected)

        val latest =
            jdbc.queryForObject(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank DESC
                LIMIT 1
                """.trimIndent(),
                String::class.java,
            ) ?: error("flyway_schema_history has no successful version")
        assertThat(latest.toInt()).isEqualTo(expected.max())
    }

    @Test
    fun `v1 creates the section 4 tables and no duplicate-check column`() {
        val tables =
            jdbc.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """.trimIndent(),
                String::class.java,
            )
        assertThat(tables).contains(
            "card_transactions",
            "documents",
            "matches",
            "reconciliation_events",
            "idempotency_keys",
            "outbox",
        )

        val documentColumns =
            jdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'documents'
                """.trimIndent(),
                String::class.java,
            )
        assertThat(documentColumns).doesNotContain("content_hash")

        val indexDef =
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'matches_one_live_per_document'",
                String::class.java,
            )
        assertThat(indexDef).contains("UNIQUE").contains("REJECTED")

        val triggers =
            jdbc.queryForList(
                """
                SELECT tgname
                FROM pg_trigger
                WHERE tgrelid = 'reconciliation_events'::regclass AND NOT tgisinternal
                """.trimIndent(),
                String::class.java,
            )
        assertThat(triggers).containsExactly("reconciliation_events_append_only")
    }

    @Test
    fun `only one live match is allowed per document`() {
        val firstTransaction = UUID.randomUUID()
        val secondTransaction = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        insertTransaction(firstTransaction)
        insertTransaction(secondTransaction)
        insertDocument(documentId)

        jdbc.update(
            """
            INSERT INTO matches (id, transaction_id, document_id, status, score)
            VALUES (?, ?, ?, 'MATCHED', 0.910)
            """.trimIndent(),
            UUID.randomUUID(),
            firstTransaction,
            documentId,
        )

        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO matches (id, transaction_id, document_id, status, score)
                VALUES (?, ?, ?, 'MATCHED', 0.900)
                """.trimIndent(),
                UUID.randomUUID(),
                secondTransaction,
                documentId,
            )
        }.hasMessageContaining("matches_one_live_per_document")

        jdbc.update(
            "UPDATE matches SET status = 'REJECTED' WHERE transaction_id = ?",
            firstTransaction,
        )
        jdbc.update(
            """
            INSERT INTO matches (id, transaction_id, document_id, status, score)
            VALUES (?, ?, ?, 'SUGGESTED', 0.700)
            """.trimIndent(),
            UUID.randomUUID(),
            secondTransaction,
            documentId,
        )

        val live =
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM matches
                WHERE document_id = ? AND status <> 'REJECTED'
                """.trimIndent(),
                Long::class.java,
                documentId,
            )
        assertThat(live).isEqualTo(1L)
    }

    private fun classpathMigrationVersions(): List<Int> {
        val root =
            javaClass.classLoader.getResource("db/migration")
                ?: error("db/migration is not on the classpath")
        check(root.protocol == "file") {
            "db/migration must be a directory on the classpath, was ${root.protocol}"
        }
        val pattern = Regex("""V(\d+)__.+\.sql""")
        val versions = mutableListOf<Int>()
        Files.newDirectoryStream(Path.of(root.toURI())).use { stream ->
            for (path in stream) {
                val match = pattern.matchEntire(path.fileName.toString()) ?: continue
                versions.add(match.groupValues[1].toInt())
            }
        }
        return versions.distinct().sorted()
    }

    private fun insertTransaction(id: UUID) {
        jdbc.update(
            """
            INSERT INTO card_transactions (
                id, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, txn_date, status
            ) VALUES (?, 'owner-1', 'Cafe', 'cafe', 1234, 'AUD', DATE '2026-11-21', 'UNMATCHED')
            """.trimIndent(),
            id,
        )
    }

    private fun insertDocument(id: UUID) {
        jdbc.update(
            """
            INSERT INTO documents (
                id, kind, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, doc_date
            ) VALUES (?, 'RECEIPT', 'owner-1', 'Cafe', 'cafe', 1234, 'AUD', DATE '2026-11-21')
            """.trimIndent(),
            id,
        )
    }
}
