package korebit.dbiceptor.dto

data class SchemaInfo(
    val owner: String,
    val tables: List<TableInfo>,
    val tableDetails: Map<String, TableDetails>,
    val views: List<String?>,
    val totalTables: Int,
    val totalViews: Int
)