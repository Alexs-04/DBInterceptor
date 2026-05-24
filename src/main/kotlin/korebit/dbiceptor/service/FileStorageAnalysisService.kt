package korebit.dbiceptor.service

import korebit.dbiceptor.dto.FileStorageAnalysis
import korebit.dbiceptor.dto.TableWithFiles
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import org.springframework.stereotype.Service

/**
 * Analyzes tables that store files (BLOB/CLOB/RAW) and produces migration hints.
 */
@Service
class FileStorageAnalysisService(private val jdbcTemplate: JdbcTemplate) {
    /**
     * Scans a schema owner for file storage patterns and aggregates risk insights.
     */
    fun analyzeTablesWithFiles(owner: String): FileStorageAnalysis {
        val tablesWithFiles = mutableListOf<TableWithFiles>()
        val totalFileSize = mutableMapOf<String, Long>()
        val recommendations = mutableListOf<String>()

        val blobTables = jdbcTemplate.queryForList(
            """SELECT DISTINCT t.table_name,
               |       LISTAGG(c.column_name || ':' || c.data_type, ', ')
               |         WITHIN GROUP (ORDER BY c.column_id) as blob_columns,
               |       COUNT(c.column_name) as blob_count
               |FROM all_tables t
               |JOIN all_tab_columns c ON t.owner = c.owner AND t.table_name = c.table_name
               |WHERE t.owner = ?
               |  AND c.data_type IN ('BLOB', 'CLOB', 'NCLOB', 'BFILE', 'RAW', 'LONG RAW')
               |GROUP BY t.table_name
               |ORDER BY blob_count DESC""".trimMargin(),
            owner.uppercase()
        )

        for (row in blobTables) {
            val tableName = row["TABLE_NAME"] as String
            val blobColumns = (row["BLOB_COLUMNS"] as String).split(", ")
            val blobCount = (row["BLOB_COUNT"] as? Number)?.toInt() ?: 0

            val estimatedSize = estimateFileSize(owner, tableName, blobColumns)
            val detectedFileTypes = detectFileTypes(blobColumns)
            val rowCount = getTableRowCount(owner, tableName)
            val sampleFilenames = getSampleFilenames(owner, tableName)

            tablesWithFiles.add(
                TableWithFiles(
                    tableName = tableName,
                    blobColumnCount = blobCount,
                    blobColumns = blobColumns,
                    estimatedTotalSizeMB = estimatedSize,
                    detectedFileTypes = detectedFileTypes,
                    rowCount = rowCount,
                    sampleFilenames = sampleFilenames,
                    riskLevel = calculateRiskLevel(blobCount, estimatedSize, detectedFileTypes)
                )
            )

            totalFileSize[tableName] = estimatedSize
        }

        if (tablesWithFiles.isNotEmpty()) {
            val totalSizeMB = totalFileSize.values.sum()

            if (totalSizeMB > 1024) {
                recommendations.add(
                    "ALTO VOLUMEN: Se detectaron ${tablesWithFiles.size} tablas con ${
                        String.format("%.2f", totalSizeMB / 1024.0)
                    } GB de archivos"
                )
                recommendations.add("Considerar migrar archivos a sistema de archivos o servicio de almacenamiento en la nube")
            }

            val highRiskTables = tablesWithFiles.filter { it.riskLevel == "ALTO" }
            if (highRiskTables.isNotEmpty()) {
                recommendations.add("ALERTA: ${highRiskTables.size} tablas con alto riesgo de contener archivos inapropiados")
                highRiskTables.forEach {
                    recommendations.add("Revisar tabla ${it.tableName}: ${it.blobColumns.joinToString(", ")}")
                }
            }

            val allFileTypes = tablesWithFiles.flatMap { it.detectedFileTypes }.toSet()
            if (allFileTypes.contains("PDF") || allFileTypes.contains("DOC")) {
                recommendations.add("Se detectaron documentos de oficina (PDF/DOC). Considerar usar repositorio documental")
            }
            if (allFileTypes.contains("IMG")) {
                recommendations.add("Se detectaron imágenes. Considerar CDN o servicio de imágenes")
            }
        } else {
            recommendations.add("No se detectaron tablas con almacenamiento de archivos binarios")
        }

        return FileStorageAnalysis(
            tablesWithFiles = tablesWithFiles,
            totalTablesWithFiles = tablesWithFiles.size,
            estimatedTotalSizeMB = totalFileSize.values.sum(),
            recommendations = recommendations,
            fileTypeDistribution = calculateFileTypeDistribution(tablesWithFiles)
        )
    }

    private fun estimateFileSize(owner: String, tableName: String, blobColumns: List<String>): Long {
        return try {
            val result = jdbcTemplate.queryForObject<Long>(
                """SELECT SUM(data_length)
                   |FROM all_tab_columns
                   |WHERE owner = ? AND table_name = ?
                   |  AND column_name IN (${blobColumns.map { "'${it.split(':')[0]}'" }.joinToString(", ")})
                   |  AND data_type IN ('BLOB', 'CLOB', 'RAW')""".trimMargin(),
                owner.uppercase(), tableName.uppercase()
            ) ?: 0

            result / (1024 * 1024)
        } catch (e: Exception) {
            println("Error estimando tamaño para $owner.$tableName: ${e.message}")
            0L
        }
    }

    private fun detectFileTypes(blobColumns: List<String>): List<String> {
        val fileTypes = mutableListOf<String>()

        val columnNames = blobColumns.map { it.split(':')[0].uppercase() }

        val patterns = mapOf(
            "PDF" to listOf("PDF", "DOCUMENTO", "ARCHIVO", "FILE"),
            "DOC" to listOf("DOC", "WORD", "DOCX", "ODT"),
            "IMG" to listOf("IMAGEN", "FOTO", "IMAGE", "JPG", "JPEG", "PNG", "GIF"),
            "EXCEL" to listOf("EXCEL", "XLS", "XLSX", "SPREADSHEET"),
            "VIDEO" to listOf("VIDEO", "MP4", "AVI", "MOV"),
            "AUDIO" to listOf("AUDIO", "MP3", "WAV"),
            "ZIP" to listOf("ZIP", "RAR", "COMPRESS", "ARCHIVO_COMPRIMIDO")
        )

        for (column in columnNames) {
            for ((fileType, keywords) in patterns) {
                if (keywords.any { column.contains(it) }) {
                    if (!fileTypes.contains(fileType)) {
                        fileTypes.add(fileType)
                    }
                }
            }
        }

        if (fileTypes.isEmpty()) {
            fileTypes.add("BINARIO")
        }

        return fileTypes
    }

    private fun getTableRowCount(owner: String, tableName: String): Long {
        return try {
            jdbcTemplate.queryForObject<Long>(
                "SELECT num_rows FROM all_tables WHERE owner = ? AND table_name = ?",
                owner.uppercase(), tableName.uppercase()
            ) ?: 0
        } catch (e: Exception) {
            0L
        }
    }

    private fun getSampleFilenames(owner: String, tableName: String): List<String?> {
        return try {
            val filenameColumns = jdbcTemplate.queryForList<String>(
                """SELECT column_name
                   |FROM all_tab_columns
                   |WHERE owner = ? AND table_name = ?
                   |  AND (column_name LIKE '%NOMBRE%'
                   |       OR column_name LIKE '%FILE%'
                   |       OR column_name LIKE '%ARCHIVO%'
                   |       OR data_type LIKE '%CHAR%')""".trimMargin(),
                owner.uppercase(), tableName.uppercase()
            )

            if (filenameColumns.isNotEmpty()) {
                val sampleColumn = filenameColumns.first()
                jdbcTemplate.queryForList<String>(
                    """SELECT DISTINCT ${sampleColumn}
                       |FROM ${owner}.${tableName}
                       |WHERE ${sampleColumn} IS NOT NULL
                       |  AND ROWNUM <= 5""".trimMargin()
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun calculateRiskLevel(blobCount: Int, sizeMB: Long, fileTypes: List<String>): String {
        var riskScore = 0

        riskScore += when {
            blobCount >= 3 -> 3
            blobCount == 2 -> 2
            else -> 1
        }

        riskScore += when {
            sizeMB > 1024 -> 3
            sizeMB > 100 -> 2
            else -> 1
        }

        if (fileTypes.any { it in listOf("PDF", "DOC", "EXCEL") }) {
            riskScore += 2
        }
        if (fileTypes.any { it in listOf("IMG", "VIDEO", "AUDIO") }) {
            riskScore += 3
        }

        return when {
            riskScore >= 6 -> "ALTO"
            riskScore >= 4 -> "MEDIO"
            else -> "BAJO"
        }
    }

    private fun calculateFileTypeDistribution(tables: List<TableWithFiles>): Map<String, Int> {
        val distribution = mutableMapOf<String, Int>()

        tables.flatMap { it.detectedFileTypes }
            .forEach { fileType ->
                distribution[fileType] = distribution.getOrDefault(fileType, 0) + 1
            }

        return distribution
    }
}

