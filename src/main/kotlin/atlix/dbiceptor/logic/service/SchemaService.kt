package atlix.dbiceptor.logic.service

import atlix.dbiceptor.model.schema.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SchemaService(val jdbcTemplate: JdbcTemplate) {

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

    fun getTableDetails(owner: String, tableName: String): TableDetails? {
        try {
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

            return TableDetails(tableName, columns, indexes)
        } catch (e: Exception) {
            println("Error getting table details for $owner.$tableName: ${e.message}")
            return null
        }
    }

    fun getSchema(owner: String): SchemaInfo {
        val tables = getTables(owner)
        val tableDetails = mutableMapOf<String, TableDetails>()

        for (table in tables) {
            val details = getTableDetails(owner, table.name)
            if (details != null) {
                tableDetails[table.name] = details
            }
        }

        val views = jdbcTemplate.queryForList(
            "SELECT view_name FROM all_views WHERE owner = ?",
            String::class.java,
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

    fun compareSchemas(schema1: String, schema2: String): SchemaComparison {
        val s1 = getSchema(schema1)
        val s2 = getSchema(schema2)

        val onlyInSchema1 = s1.tables.map { it.name }.minus(s2.tables.map { it.name })
        val onlyInSchema2 = s2.tables.map { it.name }.minus(s1.tables.map { it.name })
        val commonTables = s1.tables.map { it.name }.intersect(s2.tables.map { it.name })

        val tableDifferences = mutableMapOf<String, TableComparison>()

        for (table in commonTables) {
            val table1 = s1.tableDetails[table]!!
            val table2 = s2.tableDetails[table]!!

            val columnDiff = compareColumns(table1.columns, table2.columns)
            val indexDiff = compareIndexes(table1.indexes, table2.indexes)

            if (columnDiff.isNotEmpty() || indexDiff.isNotEmpty()) {
                tableDifferences[table] = TableComparison(columnDiff, indexDiff)
            }
        }

        // Análisis de migración Oracle → PostgreSQL
        val migrationAnalysis = analyzeOracleToPostgresMigration(s1)

        return SchemaComparison(
            schema1Name = schema1,
            schema2Name = schema2,
            schema1 = s1,
            schema2 = s2,
            onlyInSchema1 = onlyInSchema1,
            onlyInSchema2 = onlyInSchema2,
            tableDifferences = tableDifferences,
            migrationAnalysis = migrationAnalysis
        )
    }

    private fun compareColumns(cols1: List<ColumnInfo>, cols2: List<ColumnInfo>): List<ColumnDiff> {
        val diffs = mutableListOf<ColumnDiff>()
        val map1 = cols1.associateBy { it.name }
        val map2 = cols2.associateBy { it.name }

        // Columnas solo en schema1
        map1.keys.minus(map2.keys).forEach {
            diffs.add(ColumnDiff(it, "Presente solo en esquema 1", null))
        }

        // Columnas solo en schema2
        map2.keys.minus(map1.keys).forEach {
            diffs.add(ColumnDiff(it, "Presente solo en esquema 2", null))
        }

        // Columnas comunes con diferencias
        map1.keys.intersect(map2.keys).forEach { colName ->
            val c1 = map1[colName]!!
            val c2 = map2[colName]!!

            val differences = mutableListOf<String>()
            if (c1.type != c2.type) differences.add("Tipo: ${c1.type} → ${c2.type}")
            if (c1.nullable != c2.nullable) differences.add("Nullable: ${c1.nullable} → ${c2.nullable}")
            if (c1.length != c2.length) differences.add("Length: ${c1.length} → ${c2.length}")

            if (differences.isNotEmpty()) {
                diffs.add(ColumnDiff(colName, differences.joinToString(", "), null))
            }
        }

        return diffs
    }

    private fun compareIndexes(idx1: List<IndexInfo>, idx2: List<IndexInfo>): List<String> {
        val diffs = mutableListOf<String>()
        val set1 = idx1.map { "${it.name}:${it.unique}" }.toSet()
        val set2 = idx2.map { "${it.name}:${it.unique}" }.toSet()

        set1.minus(set2).forEach { diffs.add("Índice eliminado: $it") }
        set2.minus(set1).forEach { diffs.add("Índice agregado: $it") }

        return diffs
    }

    fun analyzeOracleToPostgresMigration(schema: SchemaInfo): MigrationAnalysis {
        val compatibilityIssues = mutableListOf<String>()
        val typeMappings = mutableMapOf<String, String>()
        val recommendations = mutableListOf<String>()

        // Análisis de tipos de datos existente...
        schema.tableDetails.values.forEach { table ->
            table.columns.forEach { column ->
                val oracleType = column.type.uppercase()
                val postgresType = when {
                    oracleType.contains("VARCHAR2") -> "VARCHAR(${column.length ?: 255})"
                    oracleType.contains("NUMBER") -> when {
                        column.scale != null && column.scale!! > 0 -> "NUMERIC(${column.precision},${column.scale})"
                        column.precision != null -> "NUMERIC(${column.precision})"
                        else -> "NUMERIC"
                    }
                    oracleType.contains("DATE") -> "TIMESTAMP"
                    oracleType.contains("TIMESTAMP") -> "TIMESTAMP"
                    oracleType.contains("CLOB") -> "TEXT"
                    oracleType.contains("BLOB") -> "BYTEA"
                    oracleType.contains("RAW") -> "BYTEA"
                    else -> "TEXT"
                }

                typeMappings["${table.name}.${column.name}"] = "$oracleType → $postgresType"

                // Detectar problemas de compatibilidad
                if (oracleType.contains("LONG")) {
                    compatibilityIssues.add("Tabla ${table.name}.${column.name}: Tipo LONG obsoleto en Oracle, usar CLOB/TEXT")
                }
                if (oracleType.contains("RAW") && column.length != null && column.length!! > 1000) {
                    compatibilityIssues.add("Tabla ${table.name}.${column.name}: RAW muy largo, considerar BYTEA")
                }

                // Nueva detección: archivos en base de datos
                if (oracleType.contains("BLOB") || oracleType.contains("CLOB")) {
                    compatibilityIssues.add("ALERTA: Tabla ${table.name}.${column.name}: Contiene archivos binarios (${oracleType})")
                    recommendations.add("Evaluar migración de archivos de ${table.name}.${column.name} a sistema externo")
                }
            }
        }

        // Análisis específico de archivos
        val fileAnalysis = analyzeTablesWithFiles(schema.owner)

        // Agregar recomendaciones del análisis de archivos
        recommendations.addAll(fileAnalysis.recommendations)

        if (fileAnalysis.totalTablesWithFiles > 0) {
            compatibilityIssues.add("Se detectaron ${fileAnalysis.totalTablesWithFiles} tablas con almacenamiento de archivos (${String.format("%.2f", fileAnalysis.estimatedTotalSizeMB/1024.0)} GB)")
        }

        // Recomendaciones generales
        recommendations.add("Total tablas a migrar: ${schema.totalTables}")
        recommendations.add("Total vistas a migrar: ${schema.totalViews}")
        recommendations.add("Considerar secuencias para columnas auto-incrementales")
        recommendations.add("Revisar funciones y procedimientos almacenados por separado")

        return MigrationAnalysis(
            compatible = compatibilityIssues.isEmpty(),
            compatibilityIssues = compatibilityIssues,
            typeMappings = typeMappings,
            recommendations = recommendations,
            estimatedComplexity = when {
                schema.totalTables > 100 -> "ALTA"
                schema.totalTables > 50 -> "MEDIA"
                else -> "BAJA"
            },
            fileStorageAnalysis = fileAnalysis  // Nuevo campo
        )
    }

    fun getDatabaseInfo(): DatabaseInfo {
        val metaData = jdbcTemplate.dataSource?.connection?.metaData ?: throw Exception("No se pudo obtener metadata de la base de datos")
        return DatabaseInfo(
            databaseProductName = metaData.databaseProductName,
            databaseProductVersion = metaData.databaseProductVersion,
            driverName = metaData.driverName,
            driverVersion = metaData.driverVersion,
            url = metaData.url,
            userName = metaData.userName
        )
    }


    fun analyzeTablesWithFiles(owner: String): FileStorageAnalysis {
        val tablesWithFiles = mutableListOf<TableWithFiles>()
        val totalFileSize = mutableMapOf<String, Long>()
        val recommendations = mutableListOf<String>()

        // 1. Buscar tablas con columnas BLOB/CLOB/BFILE
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

            // 2. Estimar tamaño de archivos (aproximado)
            val estimatedSize = estimateFileSize(owner, tableName, blobColumns)

            // 3. Analizar nombres de columnas para detectar tipos de archivo
            val detectedFileTypes = detectFileTypes(blobColumns)

            // 4. Obtener estadísticas adicionales
            val rowCount = getTableRowCount(owner, tableName)
            val sampleFilenames = getSampleFilenames(owner, tableName, detectedFileTypes)

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

        // 5. Generar recomendaciones
        if (tablesWithFiles.isNotEmpty()) {
            val totalSizeMB = totalFileSize.values.sum()

            if (totalSizeMB > 1024) { // Más de 1GB
                recommendations.add("ALTO VOLUMEN: Se detectaron ${tablesWithFiles.size} tablas con ${String.format("%.2f", totalSizeMB/1024.0)} GB de archivos")
                recommendations.add("Considerar migrar archivos a sistema de archivos o servicio de almacenamiento en la nube")
            }

            // Detectar tablas problemáticas
            val highRiskTables = tablesWithFiles.filter { it.riskLevel == "ALTO" }
            if (highRiskTables.isNotEmpty()) {
                recommendations.add("ALERTA: ${highRiskTables.size} tablas con alto riesgo de contener archivos inapropiados")
                highRiskTables.forEach {
                    recommendations.add("Revisar tabla ${it.tableName}: ${it.blobColumns.joinToString(", ")}")
                }
            }

            // Recomendaciones específicas por tipo de archivo
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
            // Estimar tamaño basado en estadísticas de Oracle
            val result = jdbcTemplate.queryForObject(
                """SELECT SUM(data_length) 
                   |FROM all_tab_columns 
                   |WHERE owner = ? AND table_name = ? 
                   |  AND column_name IN (${blobColumns.map { "'${it.split(':')[0]}'" }.joinToString(", ")})
                   |  AND data_type IN ('BLOB', 'CLOB', 'RAW')""".trimMargin(),
                Long::class.java,
                owner.uppercase(), tableName.uppercase()
            ) ?: 0

            // Convertir bytes a MB (aproximado)
            result / (1024 * 1024)
        } catch (e: Exception) {
            println("Error estimando tamaño para $owner.$tableName: ${e.message}")
            0L
        }
    }

    private fun detectFileTypes(blobColumns: List<String>): List<String> {
        val fileTypes = mutableListOf<String>()

        val columnNames = blobColumns.map { it.split(':')[0].uppercase() }

        // Patrones para detectar tipos de archivo por nombre de columna
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

        // Si no se detectó ningún tipo específico
        if (fileTypes.isEmpty()) {
            fileTypes.add("BINARIO")
        }

        return fileTypes
    }

    private fun getTableRowCount(owner: String, tableName: String): Long {
        return try {
            jdbcTemplate.queryForObject(
                "SELECT num_rows FROM all_tables WHERE owner = ? AND table_name = ?",
                Long::class.java,
                owner.uppercase(), tableName.uppercase()
            ) ?: 0
        } catch (e: Exception) {
            0L
        }
    }

    private fun getSampleFilenames(owner: String, tableName: String, fileTypes: List<String>): List<String> {
        return try {
            // Buscar columnas que podrían contener nombres de archivo
            val filenameColumns = jdbcTemplate.queryForList(
                """SELECT column_name 
                   |FROM all_tab_columns 
                   |WHERE owner = ? AND table_name = ? 
                   |  AND (column_name LIKE '%NOMBRE%' 
                   |       OR column_name LIKE '%FILE%' 
                   |       OR column_name LIKE '%ARCHIVO%'
                   |       OR data_type LIKE '%CHAR%')""".trimMargin(),
                String::class.java,
                owner.uppercase(), tableName.uppercase()
            )

            if (filenameColumns.isNotEmpty()) {
                val sampleColumn = filenameColumns.first()
                jdbcTemplate.queryForList(
                    """SELECT DISTINCT ${sampleColumn} 
                       |FROM ${owner}.${tableName} 
                       |WHERE ${sampleColumn} IS NOT NULL 
                       |  AND ROWNUM <= 5""".trimMargin(),
                    String::class.java
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

        // Puntos por cantidad de columnas BLOB
        riskScore += when {
            blobCount >= 3 -> 3
            blobCount == 2 -> 2
            else -> 1
        }

        // Puntos por tamaño
        riskScore += when {
            sizeMB > 1024 -> 3  // > 1GB
            sizeMB > 100 -> 2    // > 100MB
            else -> 1
        }

        // Puntos por tipos de archivo
        if (fileTypes.any { it in listOf("PDF", "DOC", "EXCEL") }) {
            riskScore += 2  // Documentos de oficina
        }
        if (fileTypes.any { it in listOf("IMG", "VIDEO", "AUDIO") }) {
            riskScore += 3  // Multimedia
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