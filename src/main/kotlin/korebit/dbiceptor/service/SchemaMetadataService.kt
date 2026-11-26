package korebit.dbiceptor.service

import korebit.dbiceptor.dto.ColumnInfo
import korebit.dbiceptor.dto.DatabaseInfo
import korebit.dbiceptor.dto.IndexInfo
import korebit.dbiceptor.dto.SchemaInfo
import korebit.dbiceptor.dto.SchemaSummary
import korebit.dbiceptor.dto.TableDetails
import korebit.dbiceptor.dto.TableInfo
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import java.sql.ResultSet

/**
 * Provides schema metadata queries backed by Oracle system catalog tables.
 *
 * Every method issues a fixed number of catalog queries regardless of how many
 * tables or schemas exist, so the cost does not grow with N round trips.
 */
@Service
class SchemaMetadataService(private val jdbcTemplate: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns tables for a schema owner ordered by table name.
     */
    fun getTables(owner: String): List<TableInfo> =
        jdbcTemplate.query(
            "SELECT table_name, num_rows FROM all_tables WHERE owner = ? ORDER BY table_name",
            { rs, _ -> TableInfo(rs.getString("TABLE_NAME"), rs.getLong("NUM_ROWS")) },
            owner.uppercase()
        )

    /**
     * Returns column and index details for a table, or null when it does not exist.
     */
    fun getTableDetails(owner: String, tableName: String): TableDetails? {
        return try {
            val columns = jdbcTemplate.query(
                """SELECT column_name, data_type, data_length, nullable, data_precision, data_scale
                   |FROM all_tab_columns
                   |WHERE owner = ? AND table_name = ?
                   |ORDER BY column_id""".trimMargin(),
                { rs, _ -> mapColumn(rs) },
                owner.uppercase(), tableName.uppercase()
            )
            if (columns.isEmpty()) return null

            val indexes = jdbcTemplate.query(
                "SELECT index_name, uniqueness FROM all_indexes WHERE table_owner = ? AND table_name = ?",
                { rs, _ -> mapIndex(rs) },
                owner.uppercase(), tableName.uppercase()
            )
            TableDetails(tableName, columns, indexes)
        } catch (e: DataAccessException) {
            log.warn("Error obteniendo detalles de {}.{}: {}", owner, tableName, e.message)
            null
        }
    }

    /**
     * Builds a full schema snapshot with tables, details, and views using four
     * bulk queries (tables, columns, indexes, views) instead of two per table.
     */
    fun getSchema(owner: String): SchemaInfo {
        val schemaOwner = owner.uppercase()
        val tables = getTables(schemaOwner)
        val tableNames = tables.mapTo(HashSet()) { it.name }

        val columnsByTable = LinkedHashMap<String, MutableList<ColumnInfo>>()
        jdbcTemplate.query(
            """SELECT table_name, column_name, data_type, data_length, nullable, data_precision, data_scale
               |FROM all_tab_columns
               |WHERE owner = ?
               |ORDER BY table_name, column_id""".trimMargin(),
            RowCallbackHandler { rs ->
                val table = rs.getString("TABLE_NAME")
                // all_tab_columns also lists view columns; keep only real tables.
                if (table in tableNames) {
                    columnsByTable.getOrPut(table) { mutableListOf() }.add(mapColumn(rs))
                }
            },
            schemaOwner
        )

        val indexesByTable = HashMap<String, MutableList<IndexInfo>>()
        jdbcTemplate.query(
            "SELECT table_name, index_name, uniqueness FROM all_indexes WHERE table_owner = ?",
            RowCallbackHandler { rs -> indexesByTable.getOrPut(rs.getString("TABLE_NAME")) { mutableListOf() }.add(mapIndex(rs)) },
            schemaOwner
        )

        val tableDetails = columnsByTable.mapValuesTo(LinkedHashMap()) { (table, columns) ->
            TableDetails(table, columns, indexesByTable[table].orEmpty())
        }

        val views = jdbcTemplate.queryForList(
            "SELECT view_name FROM all_views WHERE owner = ? ORDER BY view_name",
            String::class.java,
            schemaOwner
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
     *
     * Uses [ConnectionCallback] so the connection always goes back to the pool.
     */
    fun getDatabaseInfo(): DatabaseInfo =
        jdbcTemplate.execute(ConnectionCallback { connection ->
            val metaData = connection.metaData
            DatabaseInfo(
                databaseProductName = metaData.databaseProductName,
                databaseProductVersion = metaData.databaseProductVersion,
                driverName = metaData.driverName,
                driverVersion = metaData.driverVersion,
                url = metaData.url,
                userName = metaData.userName
            )
        })

    /**
     * Returns every visible schema with summary statistics.
     *
     * Counts, sizes and file detection are each computed with a single GROUP BY
     * query over the whole catalog instead of four or five queries per schema.
     */
    fun getAllSchemas(): List<SchemaSummary> {
        val users = loadUsers()
        val tableCounts = countByOwner("SELECT owner, COUNT(*) AS cnt FROM all_tables GROUP BY owner")
        val viewCounts = countByOwner("SELECT owner, COUNT(*) AS cnt FROM all_views GROUP BY owner")
        val sizes = estimateSchemaSizes()
        val ownersWithFiles = countByOwner(
            """SELECT c.owner, COUNT(*) AS cnt
               |FROM all_tab_columns c
               |JOIN all_tables t ON t.owner = c.owner AND t.table_name = c.table_name
               |WHERE c.data_type IN ($FILE_TYPES)
               |GROUP BY c.owner""".trimMargin()
        ).keys

        return users.map { user ->
            user.copy(
                tableCount = tableCounts[user.name]?.toInt() ?: 0,
                viewCount = viewCounts[user.name]?.toInt() ?: 0,
                estimatedSizeMB = sizes[user.name] ?: 0,
                hasFiles = user.name in ownersWithFiles
            )
        }
    }

    /**
     * Lists schema owners. DBA_USERS has status and tablespace but needs extra
     * privileges; ALL_USERS is always readable, so it is the fallback.
     */
    private fun loadUsers(): List<SchemaSummary> {
        val excluded = EXCLUDED_SCHEMAS.joinToString(", ") { "'$it'" }
        return try {
            jdbcTemplate.query(
                """SELECT username, created, account_status, default_tablespace
                   |FROM dba_users
                   |WHERE username NOT IN ($excluded)
                   |ORDER BY username""".trimMargin()
            ) { rs, _ ->
                SchemaSummary(
                    name = rs.getString("USERNAME"),
                    tableCount = 0,
                    viewCount = 0,
                    estimatedSizeMB = 0,
                    created = rs.getTimestamp("CREATED"),
                    accountStatus = rs.getString("ACCOUNT_STATUS") ?: "UNKNOWN",
                    defaultTablespace = rs.getString("DEFAULT_TABLESPACE")
                )
            }
        } catch (e: DataAccessException) {
            log.debug("DBA_USERS no accesible, usando ALL_USERS: {}", e.message)
            jdbcTemplate.query(
                "SELECT username, created FROM all_users WHERE username NOT IN ($excluded) ORDER BY username"
            ) { rs, _ ->
                SchemaSummary(
                    name = rs.getString("USERNAME"),
                    tableCount = 0,
                    viewCount = 0,
                    estimatedSizeMB = 0,
                    created = rs.getTimestamp("CREATED")
                )
            }
        }
    }

    /**
     * Estimates table storage per owner in MB. Real segment sizes come from
     * DBA_SEGMENTS; without that privilege, optimizer statistics are used.
     */
    private fun estimateSchemaSizes(): Map<String, Long> {
        val bytesByOwner = try {
            countByOwner(
                """SELECT owner, SUM(bytes) AS cnt
                   |FROM dba_segments
                   |WHERE segment_type IN ('TABLE', 'TABLE PARTITION', 'TABLE SUBPARTITION')
                   |GROUP BY owner""".trimMargin()
            )
        } catch (e: DataAccessException) {
            log.debug("DBA_SEGMENTS no accesible, usando estadísticas: {}", e.message)
            countByOwner(
                """SELECT owner, SUM(NVL(num_rows, 0) * NVL(avg_row_len, 0)) AS cnt
                   |FROM all_tables
                   |GROUP BY owner""".trimMargin()
            )
        }
        return bytesByOwner.mapValues { it.value / BYTES_PER_MB }
    }

    private fun countByOwner(sql: String): Map<String, Long> {
        val result = HashMap<String, Long>()
        jdbcTemplate.query(sql, RowCallbackHandler { rs -> result[rs.getString(1)] = rs.getLong(2) })
        return result
    }

    private fun mapColumn(rs: ResultSet) = ColumnInfo(
        name = rs.getString("COLUMN_NAME"),
        type = rs.getString("DATA_TYPE"),
        length = rs.getIntOrNull("DATA_LENGTH"),
        precision = rs.getIntOrNull("DATA_PRECISION"),
        scale = rs.getIntOrNull("DATA_SCALE"),
        nullable = rs.getString("NULLABLE") == "Y"
    )

    private fun mapIndex(rs: ResultSet) = IndexInfo(
        name = rs.getString("INDEX_NAME"),
        unique = rs.getString("UNIQUENESS") == "UNIQUE"
    )

    private fun ResultSet.getIntOrNull(column: String): Int? =
        getInt(column).takeUnless { wasNull() }

    companion object {
        const val FILE_TYPES = "'BLOB', 'CLOB', 'NCLOB', 'BFILE', 'RAW', 'LONG RAW'"
        private const val BYTES_PER_MB = 1024L * 1024L
        private val EXCLUDED_SCHEMAS =
            listOf("SYS", "SYSTEM", "DBSNMP", "XDB", "CTXSYS", "OUTLN", "ORACLE_OCM")
    }
}
