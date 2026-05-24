package korebit.dbiceptor.service

import korebit.dbiceptor.dto.*
import org.springframework.stereotype.Service

/**
 * Facade that keeps the public API stable while delegating to focused services.
 */
@Service
class SchemaService(
    private val schemaMetadataService: SchemaMetadataService,
    private val schemaComparisonService: SchemaComparisonService,
    private val migrationAnalysisService: MigrationAnalysisService,
    private val fileStorageAnalysisService: FileStorageAnalysisService
) {
    /**
     * Returns tables for a schema owner.
     */
    fun getTables(owner: String): List<TableInfo> = schemaMetadataService.getTables(owner)

    /**
     * Returns column and index details for a table.
     */
    fun getTableDetails(owner: String, tableName: String): TableDetails? =
        schemaMetadataService.getTableDetails(owner, tableName)

    /**
     * Builds a full schema snapshot with tables, details, and views.
     */
    fun getSchema(owner: String): SchemaInfo = schemaMetadataService.getSchema(owner)

    /**
     * Compares two schemas and returns differences plus migration analysis.
     */
    fun compareSchemas(schema1: String, schema2: String): SchemaComparison =
        schemaComparisonService.compareSchemas(schema1, schema2)

    /**
     * Produces migration insights for a schema snapshot.
     */
    fun analyzeOracleToPostgresMigration(schema: SchemaInfo): MigrationAnalysis =
        migrationAnalysisService.analyzeOracleToPostgresMigration(schema)

    /**
     * Returns general database metadata from the JDBC connection.
     */
    fun getDatabaseInfo(): DatabaseInfo = schemaMetadataService.getDatabaseInfo()

    /**
     * Scans a schema owner for file storage patterns and aggregates risk insights.
     */
    fun analyzeTablesWithFiles(owner: String): FileStorageAnalysis =
        fileStorageAnalysisService.analyzeTablesWithFiles(owner)

    /**
     * Returns a list of schemas with summary statistics.
     */
    fun getAllSchemas(): List<SchemaSummary> = schemaMetadataService.getAllSchemas()

    /**
     * Returns the most relevant schemas sorted by table count.
     */
    fun getImportantSchemas(): List<SchemaSummary> = schemaMetadataService.getImportantSchemas()
}