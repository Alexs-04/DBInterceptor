package korebit.dbiceptor.dto

data class ColumnDiff(
    val columnName: String,
    val difference: String,
    val suggestion: String?
)