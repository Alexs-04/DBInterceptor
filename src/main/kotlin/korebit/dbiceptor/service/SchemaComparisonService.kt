package korebit.dbiceptor.service

import korebit.dbiceptor.dto.ColumnDiff
import korebit.dbiceptor.dto.ColumnInfo
import korebit.dbiceptor.dto.IndexInfo
import korebit.dbiceptor.dto.SchemaComparison
import korebit.dbiceptor.dto.TableComparison
import org.springframework.stereotype.Service

/**
 * Compares schemas and highlights differences in tables, columns, and indexes.
 */
@Service
class SchemaComparisonService(
    private val schemaMetadataService: SchemaMetadataService,
    private val migrationAnalysisService: MigrationAnalysisService
) {
    /**
     * Compares two schemas and returns the differences plus migration analysis.
     */
    fun compareSchemas(schema1: String, schema2: String): SchemaComparison {
        val s1 = schemaMetadataService.getSchema(schema1)
        val s2 = schemaMetadataService.getSchema(schema2)

        val onlyInSchema1 = s1.tables.map { it.name }.minus(s2.tables.map { it.name }.toSet())
        val onlyInSchema2 = s2.tables.map { it.name }.minus(s1.tables.map { it.name }.toSet())
        val commonTables = s1.tables.map { it.name }.intersect(s2.tables.map { it.name }.toSet())

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

        val migrationAnalysis = migrationAnalysisService.analyzeOracleToPostgresMigration(s1)

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

        map1.keys.minus(map2.keys).forEach {
            diffs.add(ColumnDiff(it, "Presente solo en esquema 1", null))
        }

        map2.keys.minus(map1.keys).forEach {
            diffs.add(ColumnDiff(it, "Presente solo en esquema 2", null))
        }

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
}

