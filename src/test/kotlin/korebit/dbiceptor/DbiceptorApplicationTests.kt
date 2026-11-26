package korebit.dbiceptor

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * Verifies the application context starts. The pool connects lazily, so a
 * placeholder Oracle URL is enough and no database is needed.
 */
@SpringBootTest(
    properties = [
        "DB_URL=jdbc:oracle:thin:@//localhost:1521/TEST",
        "DB_USERNAME=test",
        "DB_PASSWORD=test",
    ]
)
class DbiceptorApplicationTests {

    @Test
    fun contextLoads() {
    }
}
