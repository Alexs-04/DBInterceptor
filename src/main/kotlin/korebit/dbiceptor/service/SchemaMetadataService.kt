package korebit.dbiceptor.service

import korebit.dbiceptor.dto.DatabaseInfo
import korebit.dbiceptor.dto.IndexInfo
import korebit.dbiceptor.dto.SchemaInfo
import korebit.dbiceptor.dto.SchemaSummary
import korebit.dbiceptor.dto.TableDetails
import korebit.dbiceptor.dto.TableInfo
import korebit.dbiceptor.dto.ColumnInfo
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import org.springframework.stereotype.Service
import java.sql.Timestamp

/**
 * Provides schema metadata queries backed by Oracle system catalog tables.
 */
@Service
class SchemaMetadataService(private val jdbcTemplate: JdbcTemplate) {
    /**
     * Returns tables for a schema owner ordered by table name.
     */
    fun getTables(owner: String): List<TableInfo> {
        return jdbcTemplate.queryForList(
            "SELECT table_name, num_rows FROM all_tables WHERE owner = ? ORDER BY table_name",
            owner.uppercase()
        ).map {
            TableInfo(
                name = it["TABLE_NAME"] as String,
                rowCount = (it["NUM_ROWS"] as? Number)?.toLong() ?: 0L
            )
        }
    }

    /**
     * Returns column and index details for a table, or null when it does not exist.
     */
    fun getTableDetails(owner: String, tableName: String): TableDetails? {
        return try {
            val columns = jdbcTemplate.queryForList(
                """SELECT column_name, data_type, data_length, nullable,
                   |       data_precision, data_scale, column_id
                   |FROM all_tab_columns
                   |WHERE owner = ? AND table_name = ?
                   |ORDER BY column_id""".trimMargin(),
                owner.uppercase(), tableName.uppercase()
            ).map {
                ColumnInfo(
                    name = it["COLUMN_NAME"] as String,
                    type = it["DATA_TYPE"] as String,
                    length = (it["DATA_LENGTH"] as? Number)?.toInt(),
                    precision = (it["DATA_PRECISION"] as? Number)?.toInt(),
                    scale = (it["DATA_SCALE"] as? Number)?.toInt(),
                    nullable = (it["NULLABLE"] as String) == "Y"
                )
            }

            if (columns.isEmpty()) {
                return null
            }

            val indexes = jdbcTemplate.queryForList(
                """SELECT index_name, uniqueness
                   |FROM all_indexes
                   |WHERE table_owner = ? AND table_name = ?""".trimMargin(),
                owner.uppercase(), tableName.uppercase()
            ).map {
                IndexInfo(
                    name = it["INDEX_NAME"] as String,
                    unique = (it["UNIQUENESS"] as String) == "UNIQUE"
                )
            }

            TableDetails(tableName, columns, indexes)
        } catch (e: Exception) {
            println("Error getting table details for $owner.$tableName: ${e.message}")
            null
        }
    }

    /**
     * Builds a full schema snapshot with tables, details, and views.
     */
    fun getSchema(owner: String): SchemaInfo {
        val tables = getTables(owner)
        val tableDetails = mutableMapOf<String, TableDetails>()

        for (table in tables) {
            val details = getTableDetails(owner, table.name)
            if (details != null) {
                tableDetails[table.name] = details
            }
        }

        val views = jdbcTemplate.queryForList<String>(
            "SELECT view_name FROM all_views WHERE owner = ?",
            owner.uppercase()
        )

        return SchemaInfo(
            owner = owner,
            tables = tables,
            tableDetails = tableDetails,
            views = views,
            totalTables = tables.size,
            totalViews = views.size
        )
    }

    /**
     * Returns general database metadata from the JDBC connection.
     */
    fun getDatabaseInfo(): DatabaseInfo {
        val metaData = jdbcTemplate.dataSource?.connection?.metaData
            ?: throw Exception("No se pudo obtener metadata de la base de datos")
        return DatabaseInfo(
            databaseProductName = metaData.databaseProductName,
            databaseProductVersion = metaData.databaseProductVersion,
            driverName = metaData.driverName,
            driverVersion = metaData.driverVersion,
            url = metaData.url,
            userName = metaData.userName
        )
    }

    /**
     * Returns a list of schemas with summary statistics.
     */
    fun getAllSchemas(): List<SchemaSummary> {
        return try {
            val schemas = jdbcTemplate.queryForList(
                """SELECT username as schema_name,
                   |       created,
                   |       account_status,
                   |       default_tablespace,
                   |       temporary_tablespace
                   |FROM all_users
                   |WHERE username NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'XDB', 'CTXSYS', 'OUTLN', 'ORACLE_OCM')
                   |ORDER BY username""".trimMargin()
            ).map { row ->
                val schemaName = row["SCHEMA_NAME"] as String

                val tableCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM all_tables WHERE owner = ?",
                    Int::class.java,
                    schemaName
                ) ?: 0

                val viewCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM all_views WHERE owner = ?",
                    Int::class.java,
                    schemaName
                ) ?: 0

                val estimatedSize = estimateSchemaSize(schemaName)

                SchemaSummary(
                    name = schemaName,
                    tableCount = tableCount,
                    viewCount = viewCount,
                    estimatedSizeMB = estimatedSize,
                    created = row["CREATED"] as? Timestamp,
                    accountStatus = row["ACCOUNT_STATUS"] as? String ?: "UNKNOWN",
                    defaultTablespace = row["DEFAULT_TABLESPACE"] as? String,
                    hasFiles = hasFilesInSchema(schemaName)
                )
            }

            schemas
        } catch (e: Exception) {
            println("Error obteniendo schemas: ${e.message}")

            try {
                jdbcTemplate.queryForList<String>(
                    "SELECT DISTINCT owner FROM all_tables WHERE owner NOT IN ('SYS', 'SYSTEM') ORDER BY owner"
                ).map { schemaName ->
                    SchemaSummary(
                        name = schemaName,
                        tableCount = 0,
                        viewCount = 0,
                        estimatedSizeMB = 0,
                        hasFiles = false
                    )
                }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Returns the most relevant schemas sorted by table count.
     */
    fun getImportantSchemas(): List<SchemaSummary> {
        return getAllSchemas()
            .filter { it.tableCount > 0 }
            .sortedByDescending { it.tableCount }
            .take(20)
    }

    private fun estimateSchemaSize(schemaName: String): Long {
        return try {
            val sizeBytes = jdbcTemplate.queryForObject<Long>(
                """SELECT SUM(bytes)
                   |FROM (
                   |    SELECT segment_name, SUM(bytes) as bytes
                   |    FROM user_segments
                   |    WHERE segment_type IN ('TABLE', 'TABLE PARTITION', 'TABLE SUBPARTITION')
                   |      AND owner = ?
                   |    GROUP BY segment_name
                   |)""".trimMargin(),
                schemaName
            ) ?: 0

            sizeBytes / (1024 * 1024)
        } catch (e: Exception) {
            val tableCount = jdbcTemplate.queryForObject<Int>(
                "SELECT COUNT(*) FROM all_tables WHERE owner = ?",
                schemaName
            ) ?: 0

            (tableCount * 10).toLong()
        }
    }

    private fun hasFilesInSchema(schemaName: String): Boolean {
        return try {
            val count = jdbcTemplate.queryForObject<Int>(
                """SELECT COUNT(*)
                   |FROM all_tab_columns c
                   |JOIN all_tables t ON c.owner = t.owner AND c.table_name = t.table_name
                   |WHERE c.owner = ?
                   |  AND c.data_type IN ('BLOB', 'CLOB', 'NCLOB', 'BFILE', 'RAW', 'LONG RAW')""".trimMargin(),
                schemaName
            ) ?: 0

            count > 0
        } catch (e: Exception) {
            false
        }
    }
}

