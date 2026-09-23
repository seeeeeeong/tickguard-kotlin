package tickguard.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.auth.TokenManager
import tickguard.gateway.ServerFrame
import tickguard.gateway.SocketFactory
import tickguard.gateway.WS_URL
import tickguard.holdings.HoldingsStore
import tickguard.network.PublicIpLookup
import tickguard.network.reasonOf
import tickguard.notify.Channel
import tickguard.notify.ConsoleChannel
import tickguard.notify.Grouper
import tickguard.notify.Notifier
import tickguard.notify.slack.SlackChannel
import tickguard.notify.toNotification
import tickguard.pipeline.Inbox
import tickguard.pipeline.Pump
import tickguard.reconcile.Reconciler
import tickguard.reconcile.StreamedPrice
import tickguard.rules.RuleEngine
import tickguard.rules.Signal
import tickguard.rules.WindowStore
import tickguard.rules.drawdownFromAverage
import tickguard.rules.rapidMove
import tickguard.sla.Calendar
import tickguard.sla.IncidentReporter
import tickguard.sla.Market
import tickguard.sla.SlaWatcher
import tickguard.store.Store
import tickguard.stream.Decimal
import tickguard.stream.DecodeResult
import tickguard.stream.decodeTrade
import tickguard.subscribe.SubscriptionCoordinator
import tickguard.subscribe.Topic
import tickguard.toss.auth.TossAuthClient
import tickguard.toss.gateway.OkHttpSocketFactory
import tickguard.toss.rest.OkHttpRestClient
import java.time.Instant
import java.time.InstantSource
import kotlin.time.toJavaDuration

/** Where Toss is. Overridden only to point at a local server in tests. */
data class Endpoints(
    val token: String = TossAuthClient.TOKEN_URL,
    val rest: String = OkHttpRestClient.REST_BASE_URL,
    val ws: String = WS_URL,
)

/**
 * The composition root: the one place that knows how the modules fit.
 *
 * Every module takes its dependencies as arguments — clocks, scopes, clients —
 * so this is also the only place a real clock or a real socket enters the
 * program. That is what keeps the rest of it testable without mocks.
 *
 * Spring creates this once and drives its lifecycle, and does nothing else:
 * the modules are wired here, top to bottom, rather than as beans that would
 * need a resolution order for callbacks that point both ways.
 *
 * All domain state lives on [engine], a scope that runs one coroutine at a
 * time. The socket's frames are handed in from its own thread; everything else
 * already runs there.
 */
class Tickguard(
    private val config: Config,
    private val store: Store,
    http: OkHttpClient,
    private val engine: CoroutineScope,
    /** Where each socket's keepalive runs: off the engine, so a busy engine cannot fake a dead link. */
    background: CoroutineScope,
    private val clock: InstantSource = InstantSource.system(),
    endpoints: Endpoints = Endpoints(),
    sockets: SocketFactory = OkHttpSocketFactory(http),
    /** Where the console channel writes. Alerts reach it whether or not Slack is configured. */
    alerts: (String) -> Unit = { alertLog.info(it) },
) {
    val startedAt: Instant = clock.instant()
    internal val counters = Counters()
    internal val link = LinkState()

    @Volatile internal var startup = "starting"

    @Volatile internal var worstDrift = 0.0

    @Volatile internal var lastQuoteAt: Instant = startedAt

    @Volatile private var stopping = false

    internal val tokens =
        TokenManager(TossAuthClient(config.credentials, clock, http, endpoints.token.toHttpUrl()), clock, engine)
    internal val rest =
        OkHttpRestClient(
            http = http,
            getToken = tokens::token,
            invalidateToken = tokens::invalidate,
            accountSeq = config.accountSeq,
            baseUrl = endpoints.rest.toHttpUrl(),
        )
    internal val topics =
        SubscriptionCoordinator(
            engine,
            clock,
            store,
            onStoreError = { log.error("rejection not stored: {}", reasonOf(it)) },
        )
    internal val calendar = Calendar(rest)
    private val windows = WindowStore()
    internal val inbox =
        Inbox<ServerFrame.Message>(
            onOverflow = { _, dropped -> log.warn("inbox dropped {} quotes", dropped) },
            onOrderBacklogFull = { size ->
                log.error("order backlog full at {}; order events are being refused", size)
            },
            clock = clock,
        )
    internal val lastSeen = LinkedHashMap<String, StreamedPrice>()

    // Notification first: everything below can report through it, including
    // the failures that happen before a single tick arrives.
    internal val notifier =
        Notifier(
            channels =
                listOfNotNull<Channel>(
                    ConsoleChannel(alerts),
                    config.slackWebhookUrl?.let { SlackChannel(it, http) },
                ),
            scope = engine,
        )
    private val grouper = Grouper(engine, { notifier.notify(toNotification(it)) }, config.groupWait)

    internal fun report(signal: Signal) {
        counters.signals.incrementAndGet()
        grouper.add(signal)
    }

    internal val holdings: HoldingsStore =
        HoldingsStore(
            rest = rest,
            clock = clock,
            onChange = ::onHoldingsChange,
            onError = { log.error("holdings refresh failed: {}", reasonOf(it)) },
        )

    internal val rules =
        RuleEngine(
            rules =
                listOf(
                    drawdownFromAverage(
                        config.rules.drawdownFraction,
                        config.rules.drawdownFor,
                        config.rules.drawdownCooldown,
                    ),
                    rapidMove(config.rules.rapidMoveFraction, config.rules.rapidMoveWindow),
                ),
            positions = { holdings.current().positions },
            windows = windows,
            clock = clock,
            onSignal = ::report,
            onRuleError = { error, ruleId -> log.error("rule {} threw: {}", ruleId, error.message) },
            onStoreError = { log.error("cooldown not stored: {}", reasonOf(it)) },
            cooldowns = store,
            scope = engine,
        )

    // Operational problems go out on the same path as market alerts: a separate
    // channel is one more thing to remember to watch. Inhibited while blocked,
    // as Alertmanager inhibits a symptom behind its cause: every feed goes
    // silent when the IP is refused, and that was reported once already.
    internal val incidents = IncidentReporter(::report, inhibited = { link.blockedSince != null }, clock)

    internal val sla =
        SlaWatcher(
            isOpen = calendar::isOpen,
            threshold = config.slaThreshold,
            clock = clock,
            onIncident = {
                counters.incidents.incrementAndGet()
                incidents.incident(it)
            },
            onRecovered = { code, silentFor ->
                log.info("sla: {} recovered", code)
                incidents.recovered(code, silentFor)
            },
        )

    internal val reconciler =
        Reconciler(
            rest = rest,
            lastSeen = { lastSeen },
            clock = clock,
            onDrift = { log.warn("drift {}: {}%", it.code, (it.ratio * Decimal.HUNDRED).format(3)) },
            onError = { log.error("reconcile failed: {}", reasonOf(it)) },
        )

    internal val pump =
        Pump(
            inbox,
            ::handle,
            clock,
            onHandlerError = { error, _ -> log.error("pump handler threw: {}", error.message) },
        )

    private val stream =
        StreamLink(this, engine, background, clock, Transport(endpoints, sockets, PublicIpLookup(http)))

    /** The scope the lifecycle drives the app on. */
    internal fun engineScope(): CoroutineScope = engine

    /** Published by the engine for readers on other threads: the status page and the metrics scrape. */
    @Volatile internal var snapshot: Snapshot = Snapshot.EMPTY

    /**
     * Stored state first, then the stream, then what needs the network. Returns
     * once ready, or when stopped while waiting on a network that is not there.
     */
    suspend fun start() {
        // For the status page only. Nothing waits on it.
        engine.launch { link.publicIp = stream.describePublicIp() }

        // Stored state before the stream. A tick that arrived before the cooldowns
        // were loaded would re-fire what was already reported, and a declaration
        // made before rejections were loaded would retry known failures.
        rules.load()
        topics.load()
        if (stopping) return
        pump.start(engine)
        stream.connect()

        // Calendars before anything else: the SLA watcher treats unknown hours as
        // closed, so starting without them would suppress every incident. Only a
        // thrown error counts as failure; a body that parses to no hours may be a
        // holiday, and waiting for it to change would never end.
        val loaded = untilLoaded("calendars") { tasks.loadCalendars() } && untilLoaded("holdings", ::loadHoldings)
        if (!loaded) return

        watchExtraSymbols()
        if (config.secContact == null) log.info("news: SEC filings off — set TICKGUARD_SEC_CONTACT to collect them")
        startup = "ready"
    }

    /**
     * Order matters. Stop taking work, finish what is held, then close the
     * things that own a file handle or a socket.
     */
    suspend fun stop() {
        stopping = true
        stream.stop()
        topics.detach()
        pump.stop()
        grouper.flush()
        notifier.drain()
        store.close()
    }

    /** Exposed so the scheduler can drive them; nothing here owns a timer. */
    val tasks = Tasks()

    inner class Tasks {
        suspend fun refreshHoldings() {
            holdings.refresh()
        }

        fun checkSla() = sla.check()

        suspend fun reconcile() {
            worstDrift = maxOf(worstDrift, reconciler.run().worst)
        }

        /** Both markets, because a US session can be open while KR is shut. */
        suspend fun loadCalendars() {
            coroutineScope { listOf(Market.KR, Market.US).map { async { calendar.load(it) } }.awaitAll() }
        }

        suspend fun prune() {
            val now = clock.instant()
            val history = now.minus(config.tickRetention.toJavaDuration())
            store.pruneFires(now.minus(config.rules.drawdownCooldown.toJavaDuration()))
            store.pruneTicks(history)
            // Same horizon as ticks: both exist to be replayed against later.
            store.pruneNews(history)
            store.pruneVerdicts(history)
        }

        /** Refreshes what the status page and the metrics read from other threads. */
        suspend fun publishSnapshot() {
            snapshot = Snapshot.of(this@Tickguard)
        }
    }

    private fun handle(frame: ServerFrame.Message) {
        val decoded = decodeTrade(frame)
        if (decoded !is DecodeResult.Ok) {
            counters.decodeDropped.incrementAndGet()
            return
        }

        val trade = decoded.trade
        val receivedAt = clock.instant()
        counters.ticks.incrementAndGet()
        lastQuoteAt = receivedAt
        lastSeen[trade.code] = StreamedPrice(trade.price, receivedAt)
        sla.observed(trade.code, marketOf(trade.type))
        rules.evaluate(trade)
    }

    private fun onHoldingsChange(
        added: List<String>,
        removed: List<String>,
    ) {
        val markets = holdings.current().markets
        val toTopic = { code: String -> Topic(markets[code] ?: "trade:us", code) }

        if (added.isNotEmpty()) topics.add(added.map(toTopic))
        if (removed.isNotEmpty()) topics.remove(removed.map(toTopic))
        added.forEach { sla.expect(it, marketOf(markets[it])) }
        removed.forEach { sla.forget(it) }
        log.info(
            "holdings: +{} -{}",
            added.joinToString(",").ifEmpty { "-" },
            removed.joinToString(",").ifEmpty { "-" },
        )
    }

    private fun watchExtraSymbols() {
        if (config.extraSymbols.isEmpty()) return
        topics.add(config.extraSymbols)
        config.extraSymbols.forEach { sla.expect(it.code, marketOf(it.type)) }
    }

    /** Retries one startup step, keeping the status page's startup line current. */
    private suspend fun untilLoaded(
        step: String,
        load: suspend () -> Unit,
    ): Boolean {
        startup = "waiting for $step"
        return retryUntilDone(
            run = load,
            isCancelled = { stopping },
            onFailure = { error, attempt, delay ->
                startup = "waiting for $step · attempt $attempt · ${reasonOf(error)}"
                log.error("startup: {} failed: {}, retrying in {}", step, reasonOf(error), delay)
            },
        )
    }

    /**
     * refresh() reports failure through onError and returns anyway, keeping the
     * last known positions — right for a poll, wrong here, where there are none
     * yet. So success is read from its counter.
     */
    private suspend fun loadHoldings() {
        val before = holdings.stats().refreshes
        holdings.refresh()
        check(holdings.stats().refreshes != before) { "holdings did not load" }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(Tickguard::class.java)

        /** The console channel's output: alert text, one per line. */
        val alertLog: Logger = LoggerFactory.getLogger("tickguard.alerts")
    }
}

internal fun marketOf(type: String?): Market = if (type?.endsWith(":kr") == true) Market.KR else Market.US
