package korebit.dbiceptor.controller

import korebit.dbiceptor.dto.*
import korebit.dbiceptor.service.SchemaService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.sql.Timestamp

/**
 * Renders every Thymeleaf view with a mocked [SchemaService], covering edge cases
 * that used to break the templates (names with `$`, many issues, null values...).
 */
@WebMvcTest(SchemaController::class)
class SchemaControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var schemaService: SchemaService

    private val details = TableDetails(
        "T\$DOCS",
        listOf(
            ColumnInfo("ID", "NUMBER", 22, 10, 0, false),
            ColumnInfo("NAME", "VARCHAR2", 100, null, null, true),
            ColumnInfo("DOC", "BLOB", 4000, null, null, true),
        ),
        listOf(IndexInfo("PK_DOCS", true))
    )
    private val schema = SchemaInfo(
        owner = "APP",
        tables = listOf(TableInfo("T\$DOCS", 12345), TableInfo("T#EMPTY", 0)),
        tableDetails = mapOf("T\$DOCS" to details),
        views = listOf("V_ONE", null),
        totalTables = 2,
        totalViews = 2
    )
    private val analysis = MigrationAnalysis(
        compatible = false,
        compatibilityIssues = (1..13).map { "Problema $it" },
        typeMappings = mapOf("T.ID" to "NUMBER → NUMERIC(10)"),
        recommendations = listOf("Revisar LOBs"),
        estimatedComplexity = "ALTA"
    )

    @BeforeEach
    fun stubs() {
        `when`(schemaService.getDatabaseInfo())
            .thenReturn(DatabaseInfo("Oracle", "19c", "ojdbc", "23", "jdbc:oracle:thin:@//db/ORCL", "APP"))
        `when`(schemaService.getAllSchemas()).thenReturn(
            listOf(
                SchemaSummary("APP", 12, 3, 2048, Timestamp(0), "OPEN", "USERS", hasFiles = true),
                SchemaSummary("LEGACY", 0, 0, 0, null, "LOCKED", null, hasFiles = false),
            )
        )
        `when`(schemaService.getSchema("APP")).thenReturn(schema)
        `when`(schemaService.getTableDetails("APP", "T\$DOCS")).thenReturn(details)
        `when`(schemaService.getTableDetails("APP", "MISSING")).thenReturn(null)
        `when`(schemaService.analyzeOracleToPostgresMigration(schema)).thenReturn(analysis)
        `when`(schemaService.analyzeTablesWithFiles("APP")).thenReturn(
            FileStorageAnalysis(
                tablesWithFiles = listOf(
                    TableWithFiles("T\$DOCS", 1, listOf("DOC:BLOB"), 2048, listOf("PDF"), 10, listOf("a.pdf", null), "ALTO")
                ),
                totalTablesWithFiles = 1,
                estimatedTotalSizeMB = 2048,
                recommendations = listOf("Mover a S3"),
                fileTypeDistribution = mapOf("PDF" to 1)
            )
        )
    }

    @Test
    fun `index renders database info`() {
        mvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(view().name("index"))
            .andExpect(content().string(containsString("jdbc:oracle:thin:@//db/ORCL")))
    }

    @Test
    fun `schemas keeps card classes and filter in sort links`() {
        mvc.perform(get("/schemas").param("filter", "AP").param("sortBy", "size"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("totalSchemas", 1))
            .andExpect(content().string(containsString("class=\"card schema-card h-100 schema-with-files\"")))
            .andExpect(content().string(containsString("/schemas?sortBy=name&amp;filter=AP")))
    }

    @Test
    fun `schemas treats a blank filter as no filter`() {
        mvc.perform(get("/schemas").param("filter", "  ").param("sortBy", ""))
            .andExpect(status().isOk)
            .andExpect(model().attribute("totalSchemas", 2))
            .andExpect(model().attributeDoesNotExist("filter"))
            .andExpect(content().string(containsString("schema-inactive")))
    }

    @Test
    fun `schema shows column and row totals`() {
        mvc.perform(get("/schema").param("owner", "APP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("totalColumns", 3))
            .andExpect(model().attribute("totalRows", 12345L))
            .andExpect(content().string(containsString("12,345")))
    }

    @Test
    fun `table details renders known and missing tables`() {
        mvc.perform(get("/table-details").param("owner", "APP").param("table", "T\$DOCS"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("NUMERIC(<span>10</span>)")))
            .andExpect(content().string(not(containsString("<script src=\"\">"))))

        mvc.perform(get("/table-details").param("owner", "APP").param("table", "MISSING"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No se pudieron obtener los detalles")))
    }

    @Test
    fun `migration clamps the compatibility bar at zero`() {
        mvc.perform(get("/migration").param("owner", "APP"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("width: 0%")))
            .andExpect(content().string(not(containsString("width: -"))))
    }

    @Test
    fun `file analysis uses index based modal ids and skips null file names`() {
        mvc.perform(get("/file-analysis").param("owner", "APP"))
            .andExpect(status().isOk)
            .andExpect(model().attributeDoesNotExist("schema"))
            .andExpect(content().string(containsString("id=\"migrationModal0\"")))
            .andExpect(content().string(not(containsString("migrationModalT\$DOCS"))))
            .andExpect(content().string(containsString("a.pdf")))
    }

    @Test
    fun `compare form and comparison result render`() {
        mvc.perform(get("/compare"))
            .andExpect(status().isOk)
            .andExpect(view().name("compare"))

        `when`(schemaService.compareSchemas("APP", "LEGACY")).thenReturn(
            SchemaComparison(
                "APP", "LEGACY", schema, schema, listOf("ONLY_APP"), emptyList(),
                mapOf("T" to TableComparison(listOf(ColumnDiff("C", "Tipo: A → B", null)), listOf("Índice eliminado"))),
                analysis
            )
        )
        mvc.perform(post("/compare").param("schema1", "APP").param("schema2", "LEGACY"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("ONLY_APP")))
            .andExpect(content().string(containsString("✗ PROBLEMAS")))
    }

    @Test
    fun `errors render the error view with the message`() {
        `when`(schemaService.getSchema("BAD")).thenThrow(IllegalArgumentException("Esquema inválido"))

        mvc.perform(get("/schema").param("owner", "BAD"))
            .andExpect(status().isBadRequest)
            .andExpect(view().name("error"))
            .andExpect(content().string(containsString("Esquema inválido")))
    }
}
