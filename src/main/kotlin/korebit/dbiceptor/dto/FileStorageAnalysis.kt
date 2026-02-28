package korebit.dbiceptor.dto

data class FileStorageAnalysis(
    val tablesWithFiles: List<TableWithFiles>,
    val totalTablesWithFiles: Int,
    val estimatedTotalSizeMB: Long,
    val recommendations: List<String>,
    val fileTypeDistribution: Map<String, Int>
)