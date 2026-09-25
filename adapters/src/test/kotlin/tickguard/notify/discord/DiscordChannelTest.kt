package tickguard.notify.discord

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class DiscordChannelTest {
    private val server = MockWebServer()
    private val notification = Notification("drawdown-7pct", "[09:14:00] 005930 평단 대비 -7.2%\n현재 63480")

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun channel(timeoutMs: Long = 5_000) =
        DiscordChannel(server.url("/api/webhooks/1/t").toString(), OkHttpClient(), timeoutMs.milliseconds)

    private fun sent() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun `posts the text as the webhook's content`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(204).build())

            channel().send(notification)

            assertThat(sent()["content"]!!.jsonPrimitive.content).isEqualTo(notification.text)
        }

    @Test
    fun `lets no headline ping anyone`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(204).build())

            channel().send(Notification("news", "AAPL ▲ @everyone 애플 실적 발표"))

            val parse = sent()["allowed_mentions"]!!.jsonObject["parse"] as JsonArray
            assertThat(parse).isEmpty()
        }

    @Test
    fun `cuts a message Discord would refuse whole, and says so`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(204).build())

            channel().send(Notification("group", "가".repeat(DISCORD_CONTENT_LIMIT + 500)))

            val content = sent()["content"]!!.jsonPrimitive.content
            assertThat(content).hasSize(DISCORD_CONTENT_LIMIT).endsWith("(잘림: 전체는 로그)")
        }

    @Test
    fun `reports a deleted webhook as not worth retrying`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"message": "Unknown Webhook", "code": 10015}""")
                    .build(),
            )

            val failure = failureOf { channel().send(notification) }

            assertThat(failure).isInstanceOf(DiscordDeliveryError::class.java).hasMessageContaining("Unknown Webhook")
            assertThat((failure as DiscordDeliveryError).retryable).isFalse()
        }

    @Test
    fun `passes on a fractional rate-limit wait, rounded up`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "0.3504")
                    .build(),
            )

            val failure = failureOf { channel().send(notification) } as DiscordDeliveryError

            assertThat(failure.retryable).isTrue()
            assertThat(failure.retryAfter).isEqualTo(351.milliseconds)
        }

    @Test
    fun `reads whole-second and missing waits too`() {
        assertThat(retryAfter("7")).isEqualTo(7.seconds)
        assertThat(retryAfter(null)).isNull()
        assertThat(retryAfter("soon")).isNull()
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
