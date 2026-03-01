package korebit.dbiceptor.dto

data class MigrationAnalysis(
    val compatible: Boolean,
    val compatibilityIssues: List<String>,
    val typeMappings: Map<String, String>,
    val recommendations: List<String>,
    val estimatedComplexity: String,
    val fileStorageAnalysis: FileStorageAnalysis? = null  // Nuevo campo opcional
)