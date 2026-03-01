package korebit.dbiceptor.dto

data class ColumnInfo(
    val name: String,
    val type: String,
    val length: Int?,
    val precision: Int?,
    val scale: Int?,
    val nullable: Boolean
)