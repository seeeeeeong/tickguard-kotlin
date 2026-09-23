package tickguard.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.gateway.ConnectOptions
import tickguard.gateway.ServerFrame
import tickguard.gateway.SocketFactory
import tickguard.gateway.Supervisor
import tickguard.gateway.SupervisorOptions
import tickguard.gateway.connect
import tickguard.gateway.startSupervisor
import tickguard.network.PublicIpLookup
import tickguard.network.describePublicIp
import tickguard.network.describeRecovery
import tickguard.rules.Signal
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.time.Duration

/** Counters bumped on the engine and read by the status page and the scrape. */
internal class Counters {
    val opens = AtomicLong()
    val ticks = AtomicLong()
    val decodeDropped = AtomicLong()
    val signals = AtomicLong()
    val incidents = AtomicLong()
}

/** Whether Toss is refusing our address, and what that address is. */
internal class LinkState {
    /** Set while Toss refuses our source IP. Drives inhibition and the status page. */
    @Volatile var blockedSince: Instant? = null

    /** The address Toss refused, when the lookup agreed on one. */
    @Volatile var refusedIp: String? = null

    @Volatile var publicIp: String = "확인 전"
}

/** How the stream reaches Toss, and how it learns our address when refused. */
internal class Transport(
    val endpoints: Endpoints,
    val sockets: SocketFactory,
    val publicIps: PublicIpLookup,
)

/**
 * The stream: the supervisor, and what a refusal of our address means for the
 * rest of the app. Split from the composition root only to keep each readable.
 */
internal class StreamLink(
    private val app: Tickguard,
    private val engine: CoroutineScope,
    private val background: CoroutineScope,
    private val clock: InstantSource,
    transport: Transport,
) {
    private val endpoints = transport.endpoints
    private val sockets = transport.sockets
    private val publicIps = transport.publicIps

    private var supervisor: Supervisor? = null

    suspend fun describePublicIp(): String = describePublicIp(publicIps.lookup())

    fun connect() {
        supervisor =
            startSupervisor(
                engine,
                SupervisorOptions(
                    getToken = app.tokens::token,
                    invalidateToken = app.tokens::invalidate,
                    connect = { token, handlers ->
                        connect(ConnectOptions(token, handlers, sockets, clock, background, url = endpoints.ws))
                    },
                    onOpen = { connection ->
                        app.counters.opens.incrementAndGet()
                        app.topics.attach(connection::send)
                    },
                    onFrame = ::onFrame,
                    onBlocked = { onBlocked() },
                    onUnblocked = ::onUnblocked,
                    onGaveUp = ::onGaveUp,
                    clock = clock,
                ),
            )
    }

    fun stop() {
        supervisor?.stop()
    }

    /**
     * Called on the socket's reader thread. A message is offered and nothing
     * more; anything heavier here starves the socket read. The rest are few and
     * go to the engine, where the coordinator lives.
     */
    private fun onFrame(frame: ServerFrame) {
        if (frame is ServerFrame.Message) {
            app.inbox.offer(frame.topic, frame)
            return
        }
        engine.launch {
            app.topics.handleFrame(frame)
            if (frame is ServerFrame.Error) log.error("ws error {}: {}", frame.code, frame.message)
        }
    }

    private fun onBlocked() {
        app.link.blockedSince = clock.instant()
        // Looked up now rather than kept from startup: the refusal usually means
        // the address just changed, and the old one is exactly the wrong answer.
        engine.launch {
            val result = publicIps.lookup()
            app.link.refusedIp = result.ip
            app.link.publicIp = describePublicIp(result)
            app.report(
                Signal(
                    ruleId = "ip-blocked",
                    code = "-",
                    title = "토스가 접속 IP를 거부합니다: ${app.link.publicIp}",
                    detail = "WTS > 설정 > Open API > 허용 IP 관리에 등록하세요. 등록되면 1분 안에 자동으로 복구됩니다.",
                    firedAt = clock.instant(),
                ),
            )
        }
    }

    private fun onUnblocked(blockedFor: Duration) {
        app.link.blockedSince = null
        val refused = app.link.refusedIp
        app.link.refusedIp = null
        // Looked up again: the address may be the refused one, now registered, or
        // a different one the network moved back to.
        engine.launch {
            val result = publicIps.lookup()
            app.link.publicIp = describePublicIp(result)
            val minutes = maxOf(1, (blockedFor.inWholeMilliseconds / MILLIS_PER_MINUTE).roundToLong())
            app.report(
                Signal(
                    ruleId = "ip-unblocked",
                    code = "-",
                    title = "토스 연결 복구 · 차단 ${minutes}분",
                    detail = describeRecovery(refused, result.ip),
                    firedAt = clock.instant(),
                ),
            )
        }
    }

    /** A 401 that survived a fresh token: the credentials themselves are wrong. */
    private fun onGaveUp(error: Throwable) {
        app.report(Signal("gateway-gave-up", "-", "게이트웨이가 연결을 포기했습니다", error.message.orEmpty(), clock.instant()))
        log.error("gateway gave up: {}", error.message)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(StreamLink::class.java)

        /** A block's length in an alert is in whole minutes. */
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}
