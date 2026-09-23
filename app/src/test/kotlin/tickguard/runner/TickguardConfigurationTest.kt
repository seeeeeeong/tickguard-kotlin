package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * The Spring wiring with the whole app built but not started: nothing here may
 * reach Toss, whose one valid token per client a test would take from the
 * running service.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["TOSS_CLIENT_ID=id", "TOSS_CLIENT_SECRET=secret", "TOSS_ACCOUNT_SEQ=1", "tickguard.autostart=false"],
)
class TickguardConfigurationTest
    @Autowired
    constructor(
        @param:LocalServerPort private val port: Int,
        private val lifecycle: TickguardLifecycle,
    ) {
        @Test
        fun `builds the app from the environment and serves its own status page`() {
            val page =
                HttpClient
                    .newHttpClient()
                    .send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).body()

            // The app's panels, not the placeholder the observability configuration serves alone.
            assertThat(page).contains("connections").contains("sla watching").contains("starting")
            assertThat(lifecycle.isRunning).isFalse()
        }

        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun store(registry: DynamicPropertyRegistry) {
                registry.add("TICKGUARD_DB") { Files.createTempDirectory("tickguard-").resolve("app.db").toString() }
            }
        }
    }
