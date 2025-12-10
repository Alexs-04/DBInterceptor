package atlix.dbiceptor.model.schema

data class TableInfo(
    val name: String,
    val rowCount: Long = 0
)

data class ColumnInfo(
    val name: String,
    val type: String,
    val length: Int?,
    val precision: Int?,
    val scale: Int?,
    val nullable: Boolean
)

data class IndexInfo(
    val name: String,
    val unique: Boolean
)

data class TableDetails(
    val name: String,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>
)

data class SchemaInfo(
    val owner: String,
    val tables: List<TableInfo>,
    val tableDetails: Map<String, TableDetails>,
    val views: List<String>,
    val totalTables: Int,
    val totalViews: Int
)

data class ColumnDiff(
    val columnName: String,
    val difference: String,
    val suggestion: String?
)

data class TableComparison(
    val columnDifferences: List<ColumnDiff>,
    val indexDifferences: List<String>
)

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

data class DatabaseInfo(
    val databaseProductName: String,
    val databaseProductVersion: String,
    val driverName: String,
    val driverVersion: String,
    val url: String,
    val userName: String
)

data class TableWithFiles(
    val tableName: String,
    val blobColumnCount: Int,
    val blobColumns: List<String>,
    val estimatedTotalSizeMB: Long,
    val detectedFileTypes: List<String>,
    val rowCount: Long,
    val sampleFilenames: List<String>,
    val riskLevel: String  // ALTO, MEDIO, BAJO
)

data class FileStorageAnalysis(
    val tablesWithFiles: List<TableWithFiles>,
    val totalTablesWithFiles: Int,
    val estimatedTotalSizeMB: Long,
    val recommendations: List<String>,
    val fileTypeDistribution: Map<String, Int>
)

data class MigrationAnalysis(
    val compatible: Boolean,
    val compatibilityIssues: List<String>,
    val typeMappings: Map<String, String>,
    val recommendations: List<String>,
    val estimatedComplexity: String,
    val fileStorageAnalysis: FileStorageAnalysis? = null  // Nuevo campo opcional
)