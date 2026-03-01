package korebit.dbiceptor.dto

data class TableWithFiles(
    val tableName: String,
    val blobColumnCount: Int,
    val blobColumns: List<String>,
    val estimatedTotalSizeMB: Long,
    val detectedFileTypes: List<String>,
    val rowCount: Long,
    val sampleFilenames: List<String?>,
    val riskLevel: String  // ALTO, MEDIO, BAJO
)