package korebit.dbiceptor.dto

data class TableComparison(
    val columnDifferences: List<ColumnDiff>,
    val indexDifferences: List<String>
)