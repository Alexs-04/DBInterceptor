package korebit.dbiceptor.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real catalog SQL against H2 in Oracle mode with a fake ALL_* catalog.
 * No DBA_* views exist, so the fallbacks for non-privileged users are exercised.
 */
class CatalogQueriesTest {

    private lateinit var metadata: SchemaMetadataService
    private lateinit var files: FileStorageAnalysisService

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", ""
        )
        ResourceDatabasePopulator(ClassPathResource("oracle-catalog.sql")).execute(dataSource)
        val jdbcTemplate = JdbcTemplate(dataSource)
        metadata = SchemaMetadataService(jdbcTemplate)
        files = FileStorageAnalysisService(jdbcTemplate)
    }

    @Test
    fun `getSchema groups columns and indexes per table and ignores view columns`() {
        val schema = metadata.getSchema("app")

        assertEquals(listOf("CUSTOMERS", "T\$DOCS"), schema.tables.map { it.name })
        assertEquals(100, schema.tables.first().rowCount)
        assertEquals(setOf("CUSTOMERS", "T\$DOCS"), schema.tableDetails.keys)
        assertEquals(listOf("V_CUSTOMERS"), schema.views)

        val customers = schema.tableDetails.getValue("CUSTOMERS")
        assertEquals(listOf("ID", "NAME"), customers.columns.map { it.name })
        assertEquals(10, customers.columns[0].precision)
        assertNull(customers.columns[1].precision)
        assertTrue(customers.columns[1].nullable)
        assertEquals(mapOf("PK_CUSTOMERS" to true, "IX_CUSTOMERS_NAME" to false),
            customers.indexes.associate { it.name to it.unique })
        assertTrue(schema.tableDetails.getValue("T\$DOCS").indexes.isEmpty())
    }

    @Test
    fun `getTableDetails returns null for an unknown table`() {
        assertNull(metadata.getTableDetails("APP", "MISSING"))
        assertEquals(4, metadata.getTableDetails("APP", "T\$DOCS")?.columns?.size)
    }

    @Test
    fun `getAllSchemas aggregates per owner using ALL_USERS and statistics fallbacks`() {
        val schemas = metadata.getAllSchemas().associateBy { it.name }

        assertEquals(setOf("APP", "EMPTY"), schemas.keys, "SYS must be excluded")
        val app = schemas.getValue("APP")
        assertEquals(2, app.tableCount)
        assertEquals(1, app.viewCount)
        // 100 rows * 1 MiB + 4 rows * 100 B, from optimizer statistics.
        assertEquals(100, app.estimatedSizeMB)
        assertTrue(app.hasFiles)
        assertEquals(null, app.accountStatus, "ALL_USERS has no account status")

        val empty = schemas.getValue("EMPTY")
        assertEquals(0, empty.tableCount)
        assertFalse(empty.hasFiles)
    }

    @Test
    fun `analyzeTablesWithFiles detects LOB tables and samples quoted file names`() {
        val analysis = files.analyzeTablesWithFiles("app")

        assertEquals(1, analysis.totalTablesWithFiles)
        val docs = analysis.tablesWithFiles.single()
        assertEquals("T\$DOCS", docs.tableName)
        assertEquals(listOf("PDF_DATA:BLOB"), docs.blobColumns)
        assertEquals(4, docs.rowCount)
        assertEquals(listOf("PDF"), docs.detectedFileTypes)
        // The column whose name suggests a file name wins over DESCRIPCION.
        assertEquals(setOf("contrato.pdf", "factura.pdf"), docs.sampleFilenames.toSet())
        assertEquals(mapOf("PDF" to 1), analysis.fileTypeDistribution)
    }

    @Test
    fun `analyzeTablesWithFiles reports nothing for a schema without LOBs`() {
        val analysis = files.analyzeTablesWithFiles("EMPTY")

        assertEquals(0, analysis.totalTablesWithFiles)
        assertEquals(listOf("No se detectaron tablas con almacenamiento de archivos binarios"), analysis.recommendations)
    }
}
