package atlix.dbiceptor.controller.web

import atlix.dbiceptor.logic.service.SchemaService
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

        // Log para debug
        println("Schema loaded: ${schema.owner}")
        println("Total tables: ${schema.totalTables}")
        println("Total views: ${schema.totalViews}")
        println("Table details size: ${schema.tableDetails.size}")

        return "schema"
    }

    @GetMapping("/compare")
    fun compareForm(model: Model): String {
        return "compare"
    }

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
        val analysis = schemaService.analyzeTablesWithFiles(owner)
        val schema = schemaService.getSchema(owner)

        model.addAttribute("analysis", analysis)
        model.addAttribute("schema", schema)
        model.addAttribute("owner", owner)

        return "file-analysis"
    }
}