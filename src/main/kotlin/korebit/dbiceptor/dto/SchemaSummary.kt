package korebit.dbiceptor.dto

import java.sql.Timestamp

data class SchemaSummary(
    val name: String?,
    val tableCount: Int,
    val viewCount: Int,
    val estimatedSizeMB: Long,
    val created: Timestamp? = null,
    val accountStatus: String? = null,
    val defaultTablespace: String? = null,
    val hasFiles: Boolean = false
)