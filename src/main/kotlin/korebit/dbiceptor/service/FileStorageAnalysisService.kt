package korebit.dbiceptor.service

import korebit.dbiceptor.dto.FileStorageAnalysis
import korebit.dbiceptor.dto.TableWithFiles
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service

/**
 * Analyzes tables that store files (BLOB/CLOB/RAW) and produces migration hints.
 *
 * Catalog data is loaded with three bulk queries per schema; only the optional
 * file-name samples touch user tables, and those are capped by [MAX_SAMPLED_TABLES].
 */
@Service
class FileStorageAnalysisService(private val jdbcTemplate: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class FileTable(val name: String, val rowCount: Long, val columns: MutableList<String>)

    /**
     * Scans a schema owner for file storage patterns and aggregates risk insights.
     */
    fun analyzeTablesWithFiles(owner: String): FileStorageAnalysis {
        val schemaOwner = owner.uppercase()
        val fileTables = loadFileTables(schemaOwner)

        val sizes = if (fileTables.isEmpty()) emptyMap() else estimateFileSizes(schemaOwner)
        val sampleColumns = if (fileTables.isEmpty()) emptyMap() else findFilenameColumns(schemaOwner)

        val tablesWithFiles = fileTables.mapIndexed { index, table ->
            val sizeMB = sizes[table.name] ?: 0L
            val fileTypes = detectFileTypes(table.columns)
            val samples = sampleColumns[table.name]
                ?.takeIf { index < MAX_SAMPLED_TABLES }
                ?.let { getSampleFilenames(schemaOwner, table.name, it) }
                .orEmpty()

            TableWithFiles(
                tableName = table.name,
                blobColumnCount = table.columns.size,
                blobColumns = table.columns,
                estimatedTotalSizeMB = sizeMB,
                detectedFileTypes = fileTypes,
                rowCount = table.rowCount,
                sampleFilenames = samples,
                riskLevel = calculateRiskLevel(table.columns.size, sizeMB, fileTypes)
            )
        }

        val totalSizeMB = tablesWithFiles.sumOf { it.estimatedTotalSizeMB }
        return FileStorageAnalysis(
            tablesWithFiles = tablesWithFiles,
            totalTablesWithFiles = tablesWithFiles.size,
            estimatedTotalSizeMB = totalSizeMB,
            recommendations = buildRecommendations(tablesWithFiles, totalSizeMB),
            fileTypeDistribution = tablesWithFiles.flatMap { it.detectedFileTypes }.groupingBy { it }.eachCount()
        )
    }

    /**
     * Tables with file-like columns, most columns first. Grouping happens here
     * instead of with LISTAGG, which overflows (ORA-01489) on very wide tables.
     */
    private fun loadFileTables(owner: String): List<FileTable> {
        val tables = LinkedHashMap<String, FileTable>()
        jdbcTemplate.query(
            """SELECT t.table_name, t.num_rows, c.column_name, c.data_type
               |FROM all_tables t
               |JOIN all_tab_columns c ON c.owner = t.owner AND c.table_name = t.table_name
               |WHERE t.owner = ?
               |  AND c.data_type IN (${SchemaMetadataService.FILE_TYPES})
               |ORDER BY t.table_name, c.column_id""".trimMargin(),
            RowCallbackHandler { rs ->
                val name = rs.getString("TABLE_NAME")
                tables.getOrPut(name) { FileTable(name, rs.getLong("NUM_ROWS"), mutableListOf()) }
                    .columns.add("${rs.getString("COLUMN_NAME")}:${rs.getString("DATA_TYPE")}")
            },
            owner
        )
        return tables.values.sortedByDescending { it.columns.size }
    }

    /**
     * Estimated file storage per table in MB. LOB segment sizes need DBA views;
     * without them, the average column length times the row count is used.
     */
    private fun estimateFileSizes(owner: String): Map<String, Long> {
        val bytes = try {
            sumByTable(
                """SELECT l.table_name, SUM(s.bytes)
                   |FROM dba_lobs l
                   |JOIN dba_segments s ON s.owner = l.owner AND s.segment_name = l.segment_name
                   |WHERE l.owner = ?
                   |GROUP BY l.table_name""".trimMargin(),
                owner
            )
        } catch (e: DataAccessException) {
            log.debug("DBA_LOBS/DBA_SEGMENTS no accesibles, usando estadísticas: {}", e.message)
            sumByTable(
                """SELECT c.table_name, SUM(NVL(c.avg_col_len, 0) * NVL(t.num_rows, 0))
                   |FROM all_tab_columns c
                   |JOIN all_tables t ON t.owner = c.owner AND t.table_name = c.table_name
                   |WHERE c.owner = ?
                   |  AND c.data_type IN (${SchemaMetadataService.FILE_TYPES})
                   |GROUP BY c.table_name""".trimMargin(),
                owner
            )
        }
        return bytes.mapValues { it.value / (1024L * 1024L) }
    }

    private fun sumByTable(sql: String, owner: String): Map<String, Long> {
        val result = HashMap<String, Long>()
        jdbcTemplate.query(sql, RowCallbackHandler { rs -> result[rs.getString(1)] = rs.getLong(2) }, owner)
        return result
    }

    /**
     * Best text column per file table to sample file names from: columns whose
     * name suggests a file name win over any other character column.
     */
    private fun findFilenameColumns(owner: String): Map<String, String> {
        val result = HashMap<String, String>()
        jdbcTemplate.query(
            """SELECT c.table_name, c.column_name
               |FROM all_tab_columns c
               |WHERE c.owner = ?
               |  AND c.data_type LIKE '%CHAR%'
               |  AND EXISTS (SELECT 1 FROM all_tab_columns f
               |              WHERE f.owner = c.owner AND f.table_name = c.table_name
               |                AND f.data_type IN (${SchemaMetadataService.FILE_TYPES}))
               |ORDER BY c.table_name,
               |         CASE WHEN c.column_name LIKE '%NOMBRE%'
               |                OR c.column_name LIKE '%FILE%'
               |                OR c.column_name LIKE '%ARCHIVO%' THEN 0 ELSE 1 END,
               |         c.column_id""".trimMargin(),
            RowCallbackHandler { rs -> result.putIfAbsent(rs.getString(1), rs.getString(2)) },
            owner
        )
        return result
    }

    private fun getSampleFilenames(owner: String, tableName: String, column: String): List<String?> =
        try {
            val col = quote(column)
            jdbcTemplate.queryForList(
                "SELECT DISTINCT $col FROM ${quote(owner)}.${quote(tableName)} WHERE $col IS NOT NULL AND ROWNUM <= 5",
                String::class.java
            )
        } catch (e: DataAccessException) {
            log.debug("No se pudieron obtener muestras de {}.{}: {}", owner, tableName, e.message)
            emptyList()
        }

    /** Quotes an identifier taken from the catalog so names with $, # or mixed case work. */
    private fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun buildRecommendations(tables: List<TableWithFiles>, totalSizeMB: Long): List<String> {
        if (tables.isEmpty()) return listOf("No se detectaron tablas con almacenamiento de archivos binarios")

        val recommendations = mutableListOf<String>()
        if (totalSizeMB > 1024) {
            recommendations.add(
                "ALTO VOLUMEN: Se detectaron ${tables.size} tablas con ${"%.2f".format(totalSizeMB / 1024.0)} GB de archivos"
            )
            recommendations.add("Considerar migrar archivos a sistema de archivos o servicio de almacenamiento en la nube")
        }

        val highRiskTables = tables.filter { it.riskLevel == "ALTO" }
        if (highRiskTables.isNotEmpty()) {
            recommendations.add("ALERTA: ${highRiskTables.size} tablas con alto riesgo de contener archivos inapropiados")
            highRiskTables.forEach {
                recommendations.add("Revisar tabla ${it.tableName}: ${it.blobColumns.joinToString(", ")}")
            }
        }

        val allFileTypes = tables.flatMapTo(HashSet()) { it.detectedFileTypes }
        if ("PDF" in allFileTypes || "DOC" in allFileTypes) {
            recommendations.add("Se detectaron documentos de oficina (PDF/DOC). Considerar usar repositorio documental")
        }
        if ("IMG" in allFileTypes) {
            recommendations.add("Se detectaron imágenes. Considerar CDN o servicio de imágenes")
        }
        return recommendations
    }

    private fun detectFileTypes(blobColumns: List<String>): List<String> {
        val columnNames = blobColumns.map { it.substringBefore(':').uppercase() }
        val fileTypes = FILE_TYPE_PATTERNS
            .filter { (_, keywords) -> columnNames.any { column -> keywords.any { column.contains(it) } } }
            .keys.toList()
        return fileTypes.ifEmpty { listOf("BINARIO") }
    }

    private fun calculateRiskLevel(blobCount: Int, sizeMB: Long, fileTypes: List<String>): String {
        var riskScore = when {
            blobCount >= 3 -> 3
            blobCount == 2 -> 2
            else -> 1
        }
        riskScore += when {
            sizeMB > 1024 -> 3
            sizeMB > 100 -> 2
            else -> 1
        }
        if (fileTypes.any { it in listOf("PDF", "DOC", "EXCEL") }) riskScore += 2
        if (fileTypes.any { it in listOf("IMG", "VIDEO", "AUDIO") }) riskScore += 3

        return when {
            riskScore >= 6 -> "ALTO"
            riskScore >= 4 -> "MEDIO"
            else -> "BAJO"
        }
    }

    companion object {
        /** Upper bound on queries against user tables when sampling file names. */
        const val MAX_SAMPLED_TABLES = 50

        private val FILE_TYPE_PATTERNS = linkedMapOf(
            "PDF" to listOf("PDF", "DOCUMENTO", "ARCHIVO", "FILE"),
            "DOC" to listOf("DOC", "WORD", "DOCX", "ODT"),
            "IMG" to listOf("IMAGEN", "FOTO", "IMAGE", "JPG", "JPEG", "PNG", "GIF"),
            "EXCEL" to listOf("EXCEL", "XLS", "XLSX", "SPREADSHEET"),
            "VIDEO" to listOf("VIDEO", "MP4", "AVI", "MOV"),
            "AUDIO" to listOf("AUDIO", "MP3", "WAV"),
            "ZIP" to listOf("ZIP", "RAR", "COMPRESS", "ARCHIVO_COMPRIMIDO")
        )
    }
}
