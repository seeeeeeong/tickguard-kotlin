package tickguard.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tickguard.store.sqlite.SqliteStore
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole app against a fake Toss on localhost: token, calendars, holdings,
 * the socket, a declaration and its ack, ticks, a rule firing, the alert text.
 * Every module is the real one; only the far end of each wire is local.
 */
class TickguardTest {
    private val server = MockWebServer()
    private val declarations = CopyOnWriteArrayList<String>()
    private val later = Executors.newSingleThreadScheduledExecutor()
    private val engine = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        engine.cancel()
        background.cancel()
        later.shutdownNow()
        server.close()
    }

    private fun seoul(hoursFromNow: Long) = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(hoursFromNow).toString()

    /** Open for an hour either side of now, so the SLA watcher counts the market open. */
    private fun calendar() =
        """{"result":{"today":{"date":"x","regularMarket":{"startTime":"${seoul(-1)}","endTime":"${seoul(1)}"}}}}"""

    private fun trade(price: String) =
        """{"type":"message","topic":"trade:us:AAPL","data":{"price":"$price","volume":"1",""" +
            """"timestamp":"${OffsetDateTime.now(ZoneOffset.ofHours(9))}","currency":"USD"}}"""

    private inner class FakeToss : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path == "/oauth2/token" -> {
                    json("""{"access_token":"t","expires_in":3600}""")
                }

                path.startsWith("/api/v1/market-calendar/") -> {
                    json(calendar())
                }

                path == "/api/v1/holdings" -> {
                    json(
                        """{"result":{"items":[""" +
                            """{"symbol":"AAPL","marketCountry":"US","quantity":"1","averagePurchasePrice":"100"}]}}""",
                    )
                }

                path == "/api/v1/prices" -> {
                    json("""{"result":[{"symbol":"AAPL","lastPrice":"90"}]}""")
                }

                path == "/ws" -> {
                    MockResponse.Builder().webSocketUpgrade(Socket()).build()
                }

                else -> {
                    MockResponse.Builder().code(404).build()
                }
            }
        }

        private fun json(body: String) = MockResponse.Builder().body(body).build()
    }

    /** Acks each declaration and, once AAPL is in one, trades it well below its average price. */
    private inner class Socket : WebSocketListener() {
        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, null)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (text == "PING") {
                webSocket.send("""{"type":"pong"}""")
                return
            }
            declarations += text
            val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            webSocket.send("""{"type":"subscriptions","id":"$id","subscribed":[],"rejected":[]}""")
            if ("AAPL" in text) {
                webSocket.send(trade("90"))
                // A second print a moment later, so the one-millisecond hold has held.
                later.schedule({ webSocket.send(trade("90")) }, 100, TimeUnit.MILLISECONDS)
            }
        }
    }

    private suspend fun eventually(
        within: Duration = 10.seconds,
        condition: () -> Boolean,
    ) = withTimeout(within) { while (!condition()) delay(20) }

    @Test
    fun `fires a drawdown from a fake Toss end to end, and alerts in the original's words`() =
        runTest {
            withContext(Dispatchers.Default) {
                server.dispatcher = FakeToss()
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val env =
                    mapOf(
                        "TOSS_CLIENT_ID" to "id",
                        "TOSS_CLIENT_SECRET" to "secret",
                        "TOSS_ACCOUNT_SEQ" to "1",
                        "TICKGUARD_DRAWDOWN_FOR_MS" to "1",
                        "TICKGUARD_GROUP_WAIT_MS" to "1",
                    )
                val alerts = CopyOnWriteArrayList<String>()
                val app =
                    Tickguard(
                        config = loadConfig { env[it] },
                        store = SqliteStore.open(Files.createTempDirectory("tickguard-").resolve("app.db").toString()),
                        http = OkHttpClient(),
                        engine = engine,
                        background = background,
                        endpoints =
                            Endpoints(
                                token = "$base/oauth2/token",
                                rest = base,
                                ws =
                                    base.replaceFirst("http", "ws") + "/ws",
                            ),
                        alerts = { alerts += it },
                    )

                engine.launch { app.start() }

                eventually { app.startup == "ready" }
                eventually { alerts.any { "AAPL 평단 대비 -10.0%" in it } }

                // Whether the holdings landed inside the first coalescing window
                // decides the id, so only the set is asserted.
                assertThat(declarations.last()).endsWith(""",{"type":"trade:us","codes":["AAPL"]}]""")
                assertThat(alerts.first { "AAPL" in it }).contains("현재 90 · 평단 100 · 보유 1").contains("· drawdown-7pct")
                assertThat(app.counters.ticks.get()).isGreaterThanOrEqualTo(2)

                app.tasks.publishSnapshot()
                val panels = statusPanels(app, java.time.Instant.now()).associate { it.label to it.value }
                assertThat(panels).containsEntry("startup", "ready").containsEntry("subscribed", "1 topics")

                withContext(engine.coroutineContext) { app.stop() }
            }
        }
}
