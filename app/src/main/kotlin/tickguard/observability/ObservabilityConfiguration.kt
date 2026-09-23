package tickguard.observability

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Two pages and nothing else, as in the original: metrics for a scraper at
 * `/metrics` (actuator's Prometheus endpoint, mapped to the path the original
 * served so an existing scrape keeps working) and a status page at `/`.
 *
 * The event-loop delay monitor has no counterpart. Nothing here blocks one
 * shared thread; what can stall every thread at once on the JVM is a GC pause,
 * which actuator already exports as `jvm_gc_pause`, and the time an item waits
 * between the socket and its handler is measured where it happens.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
    /** Stands in until the running app provides its panels; alone, the page says it is starting. */
    @Bean
    @ConditionalOnMissingBean(StatusSource::class)
    fun startingStatus(): StatusSource = StatusSource { listOf(StatusPanel("startup", "starting", ok = false)) }
}

@RestController
class StatusController(
    private val status: StatusSource,
) {
    @GetMapping(path = ["/", "/status"], produces = [MediaType.TEXT_HTML_VALUE + ";charset=UTF-8"])
    fun page(): String = renderStatusPage(status.panels())
}
