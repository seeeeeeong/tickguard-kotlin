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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import tickguard.stream.Decimal
import tickguard.time.SEOUL
import tickguard.trading.Bar
import java.nio.file.Files
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

    /** Bodies of every order the app placed against the fake. */
    private val orderPosts = CopyOnWriteArrayList<String>()
    private val later = Executors.newSingleThreadScheduledExecutor()
    private val engine = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Off to model a socket that stays open and delivers nothing. */
    @Volatile private var streamTrades = true

    @AfterEach
    fun tearDown() {
        engine.cancel()
        background.cancel()
        later.shutdownNow()
        server.close()
    }

    private fun seoul(hoursFromNow: Long) = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(hoursFromNow).toString()

    /**
     * Open from an hour ago to three hours from now: the SLA watcher counts the
     * market open, and the order window (ten minutes after the open to an hour
     * before the close) is open too.
     */
    private fun calendar() =
        """{"result":{"today":{"date":"x","regularMarket":{"startTime":"${seoul(-1)}","endTime":"${seoul(3)}"}}}}"""

    /** One fresh headline about AAPL, published ten minutes ago. */
    private fun feed() =
        "<rss><channel><item><title>Apple sued over App Store fees - CNBC</title><link>https://x</link>" +
            "<pubDate>${DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(10),
            )}</pubDate>" +
            "<source url=\"https://c\">CNBC</source></item></channel></rss>"

    /**
     * A weighty, relevant verdict. 0.725 is stored as 0.72499…, so the
     * original's toFixed(2) wrote 0.72 where Java's %.2f would write 0.73.
     */
    private fun completion() =
        buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    putJsonObject("message") {
                        put("content", """{"relevant":true,"direction":"down","impact":0.725,"summary":"애플이 제소됐다."}""")
                    }
                }
            }
        }.toString()

    private fun trade(price: String) =
        """{"type":"message","topic":"trade:us:AAPL","data":{"price":"$price","volume":"1",""" +
            """"timestamp":"${OffsetDateTime.now(ZoneOffset.ofHours(9))}","currency":"USD"}}"""

    /** The AsyncAPI's example fill, on the test account. */
    private fun orderFilled() =
        """{"type":"message","topic":"personal:order:1","data":{"event":"FILL","accountSeq":"1","order":{""" +
            """"orderId":"o1","symbol":"AAPL","side":"BUY","orderType":"LIMIT","timeInForce":"DAY",""" +
            """"status":"FILLED",""" +
            """"price":"100.5","quantity":"1","orderAmount":null,"currency":"USD",""" +
            """"orderedAt":"2026-06-23T09:30:00.000+09:00","canceledAt":null,"execution":{"filledQuantity":"1",""" +
            """"averageFilledPrice":"100","filledAmount":"100","commission":"0.1","tax":"0",""" +
            """"settlementDate":null}}}}"""

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

                path == "/api/v1/orders" && request.method == "POST" -> {
                    orderPosts += request.body!!.utf8()
                    json("""{"result":{"orderId":"placed-${orderPosts.size}"}}""")
                }

                path == "/api/v1/orders" -> {
                    json("""{"result":{"orders":[],"nextCursor":null,"hasNext":false}}""")
                }

                path == "/api/v1/exchange-rate" -> {
                    json("""{"result":{"baseCurrency":"USD","quoteCurrency":"KRW","rate":"1368.6"}}""")
                }

                path == "/api/v1/prices" -> {
                    json("""{"result":[{"symbol":"AAPL","lastPrice":"90"}]}""")
                }

                path == "/rss/search" -> {
                    json(feed())
                }

                path == "/chat/completions" -> {
                    json(completion())
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
            if ("personal:order" in text) webSocket.send(orderFilled())
            if ("AAPL" in text && streamTrades) {
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

    private fun app(
        base: String,
        alerts: MutableList<String>,
        extra: Map<String, String> = emptyMap(),
        store: SqliteStore = SqliteStore.open(Files.createTempDirectory("tickguard-").resolve("app.db").toString()),
    ): Tickguard {
        val env =
            mapOf(
                "TOSS_CLIENT_ID" to "id",
                "TOSS_CLIENT_SECRET" to "secret",
                "TOSS_ACCOUNT_SEQ" to "1",
                "TICKGUARD_DRAWDOWN_FOR_MS" to "1",
                "TICKGUARD_GROUP_WAIT_MS" to "1",
            ) + extra
        return Tickguard(
            config = loadConfig { env[it] },
            store = store,
            http = OkHttpClient(),
            engine = engine,
            background = background,
            endpoints =
                Endpoints(
                    token = "$base/oauth2/token",
                    rest = base,
                    ws = base.replaceFirst("http", "ws") + "/ws",
                    googleNews = base,
                    deepSeek = base,
                ),
            alerts = { alerts += it },
        )
    }

    @Test
    fun `fires a drawdown from a fake Toss end to end, and alerts in the original's words`() =
        runTest {
            withContext(Dispatchers.Default) {
                server.dispatcher = FakeToss()
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val alerts = CopyOnWriteArrayList<String>()
                val app = app(base, alerts)

                engine.launch { app.start() }

                eventually { app.startup == "ready" }
                eventually { alerts.any { "AAPL 평단 대비 -10.0%" in it } }

                // Whether the holdings landed inside the first coalescing window
                // decides the id, so only the set is asserted.
                assertThat(declarations.last()).endsWith(""",{"type":"trade:us","codes":["AAPL"]}]""")
                // The account's order events are declared too, read-only, and read.
                assertThat(declarations.last()).contains("""{"type":"personal:order","codes":["1"]}""")
                eventually { app.counters.orderEvents.get() >= 1 }
                assertThat(app.counters.orderUnreadable.get()).isZero()
                // Recorded in the ledger and alerted once, however many times the fill is redeclared.
                eventually { alerts.any { "AAPL 매수 체결 1/1주 @ 100" in it } }
                eventually { app.counters.orderResyncs.get() >= 1 }
                assertThat(alerts.count { "AAPL 매수 체결" in it }).isEqualTo(1)
                assertThat(alerts.first { "평단 대비" in it }).contains("현재 90 · 평단 100 · 보유 1").contains("· drawdown-7pct")
                assertThat(app.counters.ticks.get()).isGreaterThanOrEqualTo(2)

                withContext(engine.coroutineContext) { app.ticks.flush() }
                app.tasks.publishSnapshot()
                val panels = statusPanels(app, java.time.Instant.now()).associate { it.label to it.value }
                assertThat(panels).containsEntry("startup", "ready").containsEntry("subscribed", "2 topics")
                assertThat(panels["recorded"]).matches("[2-9]\\d* · 0 failed · 0 dropped")
                assertThat(panels["stream"]).startsWith("connected ")
                assertThat(panels["process"]).startsWith("up ")
                assertThat(panels["sla watching"]).matches("1 · [01] in session")

                withContext(engine.coroutineContext) { app.stop() }
                val stopped = statusPanels(app, java.time.Instant.now()).associate { it.label to it.value }
                assertThat(stopped["stream"]).isEqualTo("down · reconnecting")
            }
        }

    @Test
    fun `alerts on a fresh, weighty headline for a held US symbol, in the original's words`() =
        runTest {
            withContext(Dispatchers.Default) {
                server.dispatcher = FakeToss()
                server.start()
                val alerts = CopyOnWriteArrayList<String>()
                val app = app(server.url("/").toString().trimEnd('/'), alerts, mapOf("TICKGUARD_LLM_API_KEY" to "k"))

                engine.launch { app.start() }
                eventually { app.startup == "ready" }
                withContext(engine.coroutineContext) { app.tasks.collectNews() }
                eventually { alerts.any { "애플이 제소됐다" in it } }

                assertThat(alerts.first { "애플이 제소됐다" in it })
                    .contains("AAPL ▼ 애플이 제소됐다.\nCNBC · 영향 0.72\nApple sued over App Store fees\n· news")
                app.tasks.publishSnapshot()
                val panels = statusPanels(app, java.time.Instant.now()).associate { it.label to it.value }
                assertThat(panels["news"]).startsWith("google 1 · sec off · 0m ago")
                assertThat(panels["verdicts"]).isEqualTo("judged 1 · failed 0 · today 1/300")

                withContext(engine.coroutineContext) { app.stop() }
            }
        }

    @Test
    fun `judges over REST while the stream is silent, and says so in the alert`() =
        runTest {
            withContext(Dispatchers.Default) {
                streamTrades = false
                server.dispatcher = FakeToss()
                server.start()
                val alerts = CopyOnWriteArrayList<String>()
                val app =
                    app(
                        server.url("/").toString().trimEnd('/'),
                        alerts,
                        mapOf("TICKGUARD_FALLBACK_SILENCE_MS" to "1"),
                    )

                engine.launch { app.start() }
                eventually { app.startup == "ready" }
                // Two polls, so the one-millisecond hold has held.
                withContext(engine.coroutineContext) { app.tasks.pollFallbackQuotes() }
                delay(20)
                withContext(engine.coroutineContext) { app.tasks.pollFallbackQuotes() }
                eventually { alerts.any { "AAPL 평단 대비" in it } }

                assertThat(alerts.first { "AAPL 평단 대비" in it }).contains("\n(스트림 끊김 · REST 시세로 판단)\n· drawdown-7pct")
                // A REST price is not a trade: nothing was recorded for a backtest to replay.
                withContext(engine.coroutineContext) { app.ticks.flush() }
                assertThat(app.ticks.stats().written).isZero()
                app.tasks.publishSnapshot()
                val panels = statusPanels(app, java.time.Instant.now()).associate { it.label to it.value }
                assertThat(panels["fallback"]).startsWith("REST polling 1 symbols")

                withContext(engine.coroutineContext) { app.stop() }
            }
        }

    /** Sleeve A's five ETFs, flat at 100 for the last three days: its opening proposal buys each for a fifth. */
    private suspend fun seedCoreBars(store: SqliteStore) {
        val yesterday = LocalDate.now(SEOUL).minusDays(1)
        store.recordBars(
            listOf("SPY", "QQQ", "TLT", "GLD", "EFA").flatMap { code ->
                (0L..2L).map { back ->
                    val hundred = Decimal.HUNDRED
                    Bar(code, yesterday.minusDays(back), hundred, hundred, hundred, hundred, Decimal.ONE)
                }
            },
        )
    }

    @Test
    fun `places a live sleeve's orders in the window, tags them to the sleeve, and says what it did`() =
        runTest {
            withContext(Dispatchers.Default) {
                server.dispatcher = FakeToss()
                server.start()
                val store = SqliteStore.open(Files.createTempDirectory("tickguard-").resolve("app.db").toString())
                seedCoreBars(store)
                val alerts = CopyOnWriteArrayList<String>()
                val app =
                    app(
                        server.url("/").toString().trimEnd('/'),
                        alerts,
                        mapOf("TICKGUARD_TRADING" to "on", "TICKGUARD_SLEEVE_A" to "LIVE"),
                        store,
                    )

                engine.launch { app.start() }
                eventually { app.startup == "ready" }
                // As the /sleeves/rebalance route does: propose now, then place in the open window.
                app.sleeves.requestNow()
                // The summary goes out once the whole run has finished, tags included;
                // the fifth POST arriving says only that the fifth order was sent.
                eventually { alerts.any { "리밸런싱 실행" in it && "✔ A BUY SPY" in it } }
                // A second look in the same window places nothing more.
                withContext(engine.coroutineContext) { app.sleeves.execute() }

                assertThat(orderPosts).hasSize(5)
                assertThat(
                    orderPosts.first(),
                ).contains("\"orderType\":\"MARKET\"").contains("\"orderAmount\":\"30.68\"")
                assertThat(orderPosts.map { Regex("tg-\\d{8}-A-B-[A-Z]+").find(it)?.value }).doesNotContainNull()
                assertThat(store.orderTags()).hasSize(5).allSatisfy { _, sleeve -> assertThat(sleeve).isEqualTo("A") }

                withContext(engine.coroutineContext) { app.stop() }
            }
        }

    @Test
    fun `runs a dry-run sleeve live only when a person presses LIVE, and not at all once stopped`() =
        runTest {
            withContext(Dispatchers.Default) {
                server.dispatcher = FakeToss()
                server.start()
                val store = SqliteStore.open(Files.createTempDirectory("tickguard-").resolve("app.db").toString())
                seedCoreBars(store)
                val alerts = CopyOnWriteArrayList<String>()
                val app =
                    app(
                        server.url("/").toString().trimEnd('/'),
                        alerts,
                        mapOf("TICKGUARD_TRADING" to "on", "TICKGUARD_SLEEVE_A" to "DRY_RUN"),
                        store,
                    )

                engine.launch { app.start() }
                eventually { app.startup == "ready" }
                app.sleeves.requestNow()
                eventually { alerts.any { "리밸런싱 실행" in it && "· DRY_RUN A BUY SPY" in it } }
                assertThat(orderPosts).isEmpty()

                assertThat(app.sleeves.goLive()).isNull()
                eventually { alerts.any { "리밸런싱 실행" in it && "✔ A BUY SPY" in it } }
                assertThat(orderPosts).hasSize(5)
                assertThat(app.sleeves.describe()).contains("A:LIVE").contains("LIVE until")

                app.sleeves.stop()
                assertThat(app.sleeves.goLive()).isEqualTo("trading is off")
                withContext(engine.coroutineContext) {
                    app.sleeves.propose(force = true)
                    app.sleeves.execute()
                }
                assertThat(orderPosts).hasSize(5)
                assertThat(app.sleeves.describe()).startsWith("STOPPED")

                withContext(engine.coroutineContext) { app.stop() }
            }
        }

    @Test
    fun `sends no order at all while the kill switch is off, whatever the sleeves' modes`() =
        runTest {
            withContext(Dispatchers.Default) {
                server.dispatcher = FakeToss()
                server.start()
                val store = SqliteStore.open(Files.createTempDirectory("tickguard-").resolve("app.db").toString())
                seedCoreBars(store)
                val app =
                    app(
                        server.url("/").toString().trimEnd('/'),
                        CopyOnWriteArrayList(),
                        mapOf("TICKGUARD_SLEEVE_A" to "LIVE"),
                        store,
                    )

                engine.launch { app.start() }
                eventually { app.startup == "ready" }
                withContext(engine.coroutineContext) {
                    app.sleeves.propose(force = true)
                    app.sleeves.execute()
                }

                assertThat(orderPosts).isEmpty()
                withContext(engine.coroutineContext) { app.stop() }
            }
        }
}
