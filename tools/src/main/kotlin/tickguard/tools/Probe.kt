package tickguard.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import tickguard.auth.TokenManager
import tickguard.auth.loadCredentials
import tickguard.gateway.ConnectOptions
import tickguard.gateway.ServerFrame
import tickguard.gateway.SupervisorOptions
import tickguard.gateway.connect
import tickguard.gateway.startSupervisor
import tickguard.stream.DecodeResult
import tickguard.stream.decodeTrade
import tickguard.subscribe.SubscriptionCoordinator
import tickguard.subscribe.Topic
import tickguard.toss.auth.TossAuthClient
import tickguard.toss.gateway.OkHttpSocketFactory
import java.time.InstantSource
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Answers one question: is the Toss API reachable and behaving, or is the
 * problem in our code?
 *
 * It assembles the real modules and nothing else — no queue, no rules, no
 * store. When the gateway misbehaves later, this is what separates "the API
 * changed" from "we broke something", in about ten seconds.
 *
 *   ./gradlew :tools:probe                 # KR, quiet outside market hours
 *   ./gradlew :tools:probe --args="us AAPL" # US, has ticks through pre and after market
 *
 * It issues its own token, which revokes the running service's. Stop the
 * service first.
 */
suspend fun main(args: Array<String>) {
    exitProcess(Probe(args.toList()).run())
}

/** Short on purpose: a pong should not take a minute to observe. */
private val PROBE_PING_INTERVAL = 3.seconds

/** Long enough for three ticks on a live US symbol, short enough to wait for. */
private val DEADLINE = 30.seconds

/** Enough ticks to show the stream flows, not a single stray one. */
private const val REQUIRED_TICKS = 3

private class Probe(
    args: List<String>,
    engineDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    background: CoroutineDispatcher = Dispatchers.Default,
) {
    private val market = args.getOrElse(0) { "kr" }
    private val codes = args.drop(1).ifEmpty { if (market == "kr") listOf("005930") else listOf("AAPL") }

    private val clock = InstantSource.system()
    private val http = OkHttpClient()
    private val engine = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val keepalive = CoroutineScope(SupervisorJob() + background)
    private val tokens = TokenManager(TossAuthClient(loadCredentials(environment()), clock, http), clock, engine)
    private val topics = SubscriptionCoordinator(engine, clock)
    private val finished = CompletableDeferred<Int>()

    private var opens = 0
    private var acks = 0
    private var ticks = 0
    private var pongs = 0
    private var dropped = 0

    suspend fun run(): Int {
        println("대상: trade:$market ${codes.joinToString(", ")}")

        val issued = coroutineScope { List(5) { async { tokens.token() } }.awaitAll() }
        report("토큰", issued.all { it == issued.first() }, "동시 5회 요청이 한 번만 발급")

        val supervisor =
            startSupervisor(
                engine,
                SupervisorOptions(
                    getToken = tokens::token,
                    invalidateToken = tokens::invalidate,
                    connect = { token, handlers ->
                        connect(
                            ConnectOptions(
                                accessToken = token,
                                handlers = handlers,
                                sockets = OkHttpSocketFactory(http),
                                clock = clock,
                                keepaliveScope = keepalive,
                                pingInterval = PROBE_PING_INTERVAL,
                            ),
                        )
                    },
                    onOpen = { connection ->
                        opens += 1
                        topics.attach(connection::send)
                    },
                    // Read on the socket's thread; handled on the engine, in order.
                    onFrame = { frame -> engine.launch { handle(frame) } },
                    onGaveUp = { error ->
                        System.err.println("\n✖ ${error.message}")
                        finished.complete(1)
                    },
                ),
            )
        engine.launch { topics.add(codes.map { Topic("trade:$market", it) }) }

        val code =
            withTimeoutOrNull(DEADLINE) { finished.await() } ?: run {
                System.err.println("\n✖ ${DEADLINE.inWholeSeconds}초 안에 조건을 못 채웠습니다.")
                if (ticks == 0 && market == "kr") {
                    System.err.println("  장 시간이 아닐 수 있습니다 — `./gradlew :tools:probe --args=\"us AAPL\"` 로 확인해보세요.")
                }
                1
            }

        report("keepalive", pongs > 0, "pong ${pongs}회 (bare-text PING)")
        println("\nopen $opens · ack $acks · tick $ticks · drop $dropped")
        println(if (code == 0) "✅ API 정상" else "✖ 확인 필요")
        supervisor.stop()
        return code
    }

    private fun handle(frame: ServerFrame) {
        topics.handleFrame(frame)
        when (frame) {
            is ServerFrame.Subscriptions -> {
                acks += 1
                report("구독", frame.subscribed.isNotEmpty(), "subscribed=${frame.subscribed.size}")
                frame.rejected.forEach { println("  rejected ${it.target}: ${it.code}") }
            }

            is ServerFrame.Pong -> {
                pongs += 1
            }

            is ServerFrame.Error -> {
                System.err.println("  error ${frame.code}: ${frame.message}")
            }

            is ServerFrame.Message -> {
                onMessage(frame)
            }

            // Nothing to report: the probe counts only what it understood.
            is ServerFrame.Unknown -> {
                return
            }
        }
    }

    private fun onMessage(frame: ServerFrame.Message) {
        when (val decoded = decodeTrade(frame)) {
            is DecodeResult.Failed -> {
                dropped += 1
                System.err.println("  drop ${decoded.failure.topic}: ${decoded.failure.reason}")
            }

            is DecodeResult.Ok -> {
                ticks += 1
                if (ticks <= REQUIRED_TICKS) {
                    val trade = decoded.trade
                    println("  tick ${trade.code} ${trade.price} ${trade.currency} x${trade.volume}")
                }
                if (ticks >= REQUIRED_TICKS && pongs >= 1) finished.complete(0)
            }
        }
    }

    private fun report(
        label: String,
        ok: Boolean,
        detail: String,
    ) = println("  ${if (ok) "✓" else "✗"} $label  $detail")
}
