package korebit.dbiceptor.dto

data class TableInfo(
    val name: String,
    val rowCount: Long = 0
)