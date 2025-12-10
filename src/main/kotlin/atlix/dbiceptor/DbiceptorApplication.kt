package atlix.dbiceptor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DbiceptorApplication

fun main(args: Array<String>) {
    runApplication<DbiceptorApplication>(*args)
}
