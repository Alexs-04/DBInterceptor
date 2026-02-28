package korebit.dbiceptor.dto

data class TableDetails(
    val name: String,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>
)