package tickguard.observability

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// Without the app itself: loading the context must not connect to Toss.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["tickguard.enabled=false"])
class ObservabilityConfigurationTest
    @Autowired
    constructor(
        @param:LocalServerPort private val port: Int,
        private val registry: MeterRegistry,
    ) {
        private fun get(path: String): HttpResponse<String> =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

        @Test
        fun `serves Prometheus metrics at the path the original served them`() {
            Metrics(registry, owner = this).counter("tickguard.probe", "A counter for this test.") { 5 }

            val response = get("/metrics")

            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/plain")
            assertThat(response.body()).contains("tickguard_probe_total 5.0").contains("jvm_gc_pause")
        }

        @Test
        fun `serves the status page at the root`() {
            val response = get("/")

            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html")
            assertThat(response.body()).contains("startup").contains("starting")
        }

        @Test
        fun `answers anything else with 404`() {
            assertThat(get("/nope").statusCode()).isEqualTo(404)
        }
    }
