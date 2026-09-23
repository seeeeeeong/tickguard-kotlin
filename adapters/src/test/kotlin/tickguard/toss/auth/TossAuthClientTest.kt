package tickguard.toss.auth

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tickguard.auth.MalformedTokenResponseError
import tickguard.auth.TokenIssueError
import tickguard.auth.TossCredentials
import tickguard.network.isSourceIpRejected
import tickguard.testing.failureOf
import java.io.InterruptedIOException
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class TossAuthClientTest {
    private val server = MockWebServer()
    private val credentials = TossCredentials("id", "secret")
    private val fixedClock = InstantSource { Instant.ofEpochMilli(1_000) }

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun client() = TossAuthClient(credentials, fixedClock, OkHttpClient(), server.url("/oauth2/token"))

    private fun answer(
        code: Int,
        body: String,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    private suspend fun failure(client: TossAuthClient = client()) = failureOf { client.issue() }

    @Test
    fun `gives up on a token request that never answers`() =
        runTest {
            server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).build())
            val impatient =
                TossAuthClient(credentials, fixedClock, OkHttpClient(), server.url("/oauth2/token"), 100.milliseconds)

            assertThat(failure(impatient)).isInstanceOf(InterruptedIOException::class.java)
        }

    @Test
    fun `anchors expiry on the clock before the request`() =
        runTest {
            answer(200, """{"access_token":"a","expires_in":60}""")

            val token = client().issue()

            assertThat(token.accessToken).isEqualTo("a")
            assertThat(token.expiresAt).isEqualTo(Instant.ofEpochMilli(61_000))
        }

    @Test
    fun `sends the client credentials grant as a form`() =
        runTest {
            answer(200, """{"access_token":"a","expires_in":60}""")

            client().issue()

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers["Content-Type"]).startsWith("application/x-www-form-urlencoded")
            assertThat(request.body?.utf8())
                .isEqualTo("grant_type=client_credentials&client_id=id&client_secret=secret")
        }

    @Test
    fun `names the fix for rejected credentials`() =
        runTest {
            answer(401, "unauthorized")

            val failure = failure()

            assertThat(failure).isInstanceOf(TokenIssueError::class.java).hasMessageContaining("TOSS_CLIENT_ID")
            assertThat((failure as TokenIssueError).status).isEqualTo(401)
        }

    @Test
    fun `reports an unregistered IP as the source IP rejection it is`() =
        runTest {
            answer(403, "forbidden")

            val failure = failure()

            assertThat(failure).hasMessageContaining("allowed IPs")
            assertThat(isSourceIpRejected(failure!!)).isTrue()
        }

    @Test
    fun `refuses a body that is not the token shape`() =
        runTest {
            answer(200, "<html>")
            answer(200, """{"expires_in":60}""")
            answer(200, """{"access_token":"a","expires_in":"60"}""")

            assertThat(
                failure(),
            ).isInstanceOf(MalformedTokenResponseError::class.java).hasMessageContaining("not JSON")
            assertThat(failure()).hasMessageContaining("access_token missing")
            assertThat(failure()).hasMessageContaining("expires_in")
        }
}
