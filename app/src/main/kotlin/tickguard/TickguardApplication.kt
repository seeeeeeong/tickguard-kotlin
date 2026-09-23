package tickguard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TickguardApplication

@Suppress("SpreadOperator") // Copies the arguments once, at startup.
fun main(args: Array<String>) {
    runApplication<TickguardApplication>(*args)
}
