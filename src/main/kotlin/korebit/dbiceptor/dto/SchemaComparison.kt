package korebit.dbiceptor.dto

data class SchemaComparison(
    val schema1Name: String,
    val schema2Name: String,
    val schema1: SchemaInfo,
    val schema2: SchemaInfo,
    val onlyInSchema1: List<String>,
    val onlyInSchema2: List<String>,
    val tableDifferences: Map<String, TableComparison>,
    val migrationAnalysis: MigrationAnalysis
)