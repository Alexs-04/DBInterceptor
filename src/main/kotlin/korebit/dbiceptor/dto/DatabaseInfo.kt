package korebit.dbiceptor.dto

data class DatabaseInfo(
    val databaseProductName: String,
    val databaseProductVersion: String,
    val driverName: String,
    val driverVersion: String,
    val url: String,
    val userName: String
)