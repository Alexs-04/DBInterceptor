package korebit.dbiceptor.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Regression tests for the connection leak in [SchemaMetadataService.getDatabaseInfo].
 */
class SchemaMetadataServiceTest {

    private val dataSource = mock(DataSource::class.java)
    private val connection = mock(Connection::class.java)
    private val service = SchemaMetadataService(JdbcTemplate(dataSource))

    @Test
    fun `getDatabaseInfo returns the connection to the pool`() {
        val metaData = mock(DatabaseMetaData::class.java)
        `when`(dataSource.connection).thenReturn(connection)
        `when`(connection.metaData).thenReturn(metaData)
        `when`(metaData.databaseProductName).thenReturn("Oracle")
        `when`(metaData.databaseProductVersion).thenReturn("19c")
        `when`(metaData.driverName).thenReturn("Oracle JDBC driver")
        `when`(metaData.driverVersion).thenReturn("23.0")
        `when`(metaData.url).thenReturn("jdbc:oracle:thin:@//db:1521/ORCL")
        `when`(metaData.userName).thenReturn("APP")

        val info = service.getDatabaseInfo()

        assertEquals("Oracle", info.databaseProductName)
        assertEquals("APP", info.userName)
        verify(connection).close()
    }

    @Test
    fun `getDatabaseInfo releases the connection when reading metadata fails`() {
        `when`(dataSource.connection).thenReturn(connection)
        `when`(connection.metaData).thenThrow(SQLException("boom"))

        assertThrows<DataAccessException> { service.getDatabaseInfo() }
        verify(connection).close()
    }
}
