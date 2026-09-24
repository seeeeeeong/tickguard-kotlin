package tickguard.notify.slack

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tickguard.notify.Notification
import tickguard.testing.failureOf
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SlackChannelTest {
    private val server = MockWebServer()
    private val notification = Notification("drawdown-7pct", "[09:14:00] 005930 평단 대비 -7.2%\n현재 63480")

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun channel(timeoutMs: Long = 5_000) =
        SlackChannel(server.url("/hook").toString(), OkHttpClient(), timeoutMs.milliseconds)

    @Test
    fun `posts the text as the webhook's JSON`() =
        runTest {
            server.enqueue(MockResponse.Builder().body("ok").build())

            channel().send(notification)

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers["Content-Type"]).startsWith("application/json")
            val sent =
                Json
                    .parseToJsonElement(request.body!!.utf8())
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            assertThat(sent).isEqualTo(notification.text)
        }

    @Test
    fun `reports a refusal with its status, and a gone webhook as not worth retrying`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("no_service")
                    .build(),
            )

            val failure = failureOf { channel().send(notification) }

            assertThat(
                failure,
            ).isInstanceOf(
                SlackDeliveryError::class.java,
            ).hasMessageContaining("404")
                .hasMessageContaining("no_service")
            assertThat((failure as SlackDeliveryError).retryable).isFalse()
        }

    @Test
    fun `passes on how long a rate limit asks to wait`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "7")
                    .build(),
            )

            val failure = failureOf { channel().send(notification) } as SlackDeliveryError

            assertThat(failure.retryable).isTrue()
            assertThat(failure.retryAfter).isEqualTo(7.seconds)
        }

    @Test
    fun `gives up on a webhook that never answers`() =
        runTest {
            server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).build())

            assertThat(
                failureOf { channel(timeoutMs = 100).send(notification) },
            ).isInstanceOf(InterruptedIOException::class.java)
        }
}
