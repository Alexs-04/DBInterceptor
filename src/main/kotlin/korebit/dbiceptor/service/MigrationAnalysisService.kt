package korebit.dbiceptor.service

import korebit.dbiceptor.dto.MigrationAnalysis
import korebit.dbiceptor.dto.SchemaInfo
import org.springframework.stereotype.Service

/**
 * Builds Oracle-to-PostgreSQL migration insights for a schema snapshot.
 */
@Service
class MigrationAnalysisService(private val fileStorageAnalysisService: FileStorageAnalysisService) {
    /**
     * Produces compatibility issues, type mappings, and migration recommendations.
     */
    fun analyzeOracleToPostgresMigration(schema: SchemaInfo): MigrationAnalysis {
        val compatibilityIssues = mutableListOf<String>()
        val typeMappings = mutableMapOf<String, String>()
        val recommendations = mutableListOf<String>()

        schema.tableDetails.values.forEach { table ->
            table.columns.forEach { column ->
                val oracleType = column.type.uppercase()
                val postgresType = when {
                    oracleType.contains("VARCHAR2") -> "VARCHAR(${column.length ?: 255})"
                    oracleType.contains("NUMBER") -> when {
                        column.scale != null && column.scale > 0 -> "NUMERIC(${column.precision},${column.scale})"
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

                if (oracleType.contains("LONG")) {
                    compatibilityIssues.add("Tabla ${table.name}.${column.name}: Tipo LONG obsoleto en Oracle, usar CLOB/TEXT")
                }
                if (oracleType.contains("RAW") && column.length != null && column.length > 1000) {
                    compatibilityIssues.add("Tabla ${table.name}.${column.name}: RAW muy largo, considerar BYTEA")
                }

                if (oracleType.contains("BLOB") || oracleType.contains("CLOB")) {
                    compatibilityIssues.add("ALERTA: Tabla ${table.name}.${column.name}: Contiene archivos binarios (${oracleType})")
                    recommendations.add("Evaluar migración de archivos de ${table.name}.${column.name} a sistema externo")
                }
            }
        }

        val fileAnalysis = fileStorageAnalysisService.analyzeTablesWithFiles(schema.owner)
        recommendations.addAll(fileAnalysis.recommendations)

        if (fileAnalysis.totalTablesWithFiles > 0) {
            compatibilityIssues.add(
                "Se detectaron ${fileAnalysis.totalTablesWithFiles} tablas con almacenamiento de archivos (${String.format("%.2f", fileAnalysis.estimatedTotalSizeMB / 1024.0)} GB)"
            )
        }

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
            fileStorageAnalysis = fileAnalysis
        )
    }
}

