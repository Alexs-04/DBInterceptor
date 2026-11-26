package korebit.dbiceptor.controller

import korebit.dbiceptor.service.SchemaService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class SchemaController(private val schemaService: SchemaService) {

    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("dbInfo", schemaService.getDatabaseInfo())
        return "index"
    }

    @GetMapping("/schemas")
    fun listAllSchemas(
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false) filter: String?,
        model: Model
    ): String {
        val filter = filter?.trim()?.takeIf { it.isNotEmpty() }
        val sortBy = sortBy?.takeIf { it.isNotBlank() }
        var schemas = schemaService.getAllSchemas()

        // Aplicar filtro si existe
        filter?.let {
            schemas = schemas.filter { schema ->
                schema.name?.contains(filter, ignoreCase = true) == true ||
                        (schema.defaultTablespace?.contains(filter, ignoreCase = true) ?: false)
            }
        }

        // Aplicar ordenamiento
        schemas = when (sortBy) {
            "name" -> schemas.sortedBy { it.name }
            "tables" -> schemas.sortedByDescending { it.tableCount }
            "size" -> schemas.sortedByDescending { it.estimatedSizeMB }
            "created" -> schemas.sortedByDescending { it.created }
            else -> schemas.sortedBy { it.name }  // Orden por defecto
        }

        val totalTables = schemas.sumOf { it.tableCount }
        val totalViews = schemas.sumOf { it.viewCount }
        val totalSizeMB = schemas.sumOf { it.estimatedSizeMB }
        val schemasWithFiles = schemas.count { it.hasFiles }

        model.addAttribute("schemas", schemas)
        model.addAttribute("totalSchemas", schemas.size)
        model.addAttribute("totalTables", totalTables)
        model.addAttribute("totalViews", totalViews)
        model.addAttribute("totalSizeMB", totalSizeMB)
        model.addAttribute("schemasWithFiles", schemasWithFiles)
        model.addAttribute("sortBy", sortBy)
        model.addAttribute("filter", filter)

        return "schemas"
    }

    @GetMapping("/schema")
    fun viewSchema(
        @RequestParam owner: String,
        @RequestParam(required = false) debug: Boolean?,
        model: Model
    ): String {
        val schema = schemaService.getSchema(owner)
        model.addAttribute("schema", schema)
        model.addAttribute("owner", owner)
        model.addAttribute("debug", debug ?: false)
        model.addAttribute("totalColumns", schema.tableDetails.values.sumOf { it.columns.size })
        model.addAttribute("totalRows", schema.tables.sumOf { it.rowCount })

        return "schema"
    }

    @GetMapping("/compare")
    fun compareForm(): String = "compare"

    @PostMapping("/compare")
    fun compareSchemas(
        @RequestParam schema1: String,
        @RequestParam schema2: String,
        model: Model
    ): String {
        val comparison = schemaService.compareSchemas(schema1, schema2)
        model.addAttribute("comparison", comparison)
        return "comparison"
    }

    @GetMapping("/migration")
    fun migrationAnalysis(
        @RequestParam owner: String,
        model: Model
    ): String {
        val schema = schemaService.getSchema(owner)
        val analysis = schemaService.analyzeOracleToPostgresMigration(schema)
        model.addAttribute("schema", schema)
        model.addAttribute("analysis", analysis)
        model.addAttribute("owner", owner)
        return "migration"
    }

    @GetMapping("/table-details")
    fun tableDetails(
        @RequestParam owner: String,
        @RequestParam table: String,
        model: Model
    ): String {
        val details = schemaService.getTableDetails(owner, table)
        model.addAttribute("details", details)
        model.addAttribute("owner", owner)
        model.addAttribute("tableName", table)
        return "table-details"
    }

    @GetMapping("/file-analysis")
    fun fileAnalysis(
        @RequestParam owner: String,
        model: Model
    ): String {
        model.addAttribute("analysis", schemaService.analyzeTablesWithFiles(owner))
        model.addAttribute("owner", owner)

        return "file-analysis"
    }
}