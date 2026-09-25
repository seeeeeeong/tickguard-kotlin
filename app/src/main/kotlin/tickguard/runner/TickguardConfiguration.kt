package tickguard.runner

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import tickguard.network.reasonOf
import tickguard.observability.Metrics
import tickguard.observability.StatusSource
import tickguard.orders.Order
import tickguard.store.postgres.PostgresStore
import tickguard.store.sqlite.SqliteStore
import java.time.Instant
import java.time.InstantSource

/**
 * Spring's part: read the environment, build the app once, give it a lifecycle.
 *
 * Off when `tickguard.enabled` is false, so a test that loads the context does
 * not connect to a live brokerage account and issue a token that would revoke
 * the running service's.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tickguard", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class TickguardConfiguration {
    /**
     * Read through Spring's environment, which holds the process environment
     * and a `.env` file if there is one, the environment winning — the order
     * `node --env-file` applied for the original.
     */
    @Bean
    fun tickguardConfig(environment: Environment): Config = loadConfig { environment.getProperty(it) }

    @Bean
    fun okHttpClient(): OkHttpClient = OkHttpClient()

    /** The composition root is the one place dispatchers are chosen; everything below takes them injected. */
    @Bean
    @Suppress("InjectDispatcher")
    fun tickguard(
        config: Config,
        http: OkHttpClient,
        registry: MeterRegistry,
    ): Tickguard {
        val clock = InstantSource.system()
        val failures =
            CoroutineExceptionHandler {
                _,
                error,
                ->
                log.error("unhandled on the engine: {}", reasonOf(error), error)
            }
        val app =
            Tickguard(
                config = config,
                store =
                    when (val store = config.store) {
                        is StoreConfig.Sqlite -> SqliteStore.open(store.path)
                        is StoreConfig.Postgres -> PostgresStore.open(store.url, store.user, store.password)
                    },
                http = http,
                engine = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + failures),
                background = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures),
                clock = clock,
            )
        registerMetrics(app, Metrics(registry, owner = app), clock)
        return app
    }

    /** Takes over from the placeholder the observability configuration serves until now. */
    @Bean
    @Primary
    fun tickguardStatus(app: Tickguard): StatusSource = StatusSource { statusPanels(app, Instant.now()) }

    /**
     * The control page and its three buttons, reachable only where the status
     * page is, the tailnet. `POST /sleeves/rebalance` proposes now and places if
     * trading is on and the window open; `/sleeves/live` runs DRY_RUN sleeves
     * live for this window; `/sleeves/stop` stops every order until restart.
     *
     * Each POST needs [CONTROL_HEADER]. A form on another site can post to a
     * tailnet address from the person's own browser, but cannot set a header,
     * and a script that tries is stopped by the browser's preflight, which
     * this server never answers.
     */
    @Bean
    fun sleeveRoutes(app: Tickguard): RouterFunction<ServerResponse> =
        router {
            GET("/control") {
                ServerResponse.async(
                    app.sleeves.ledger().thenApply { (orders, tags) ->
                        ServerResponse
                            .ok()
                            .contentType(MediaType.TEXT_HTML)
                            .body(renderControl(controlView(app.sleeves, orders, tags)))
                    },
                )
            }
            POST("/sleeves/rebalance") { request ->
                guarded(request) {
                    app.sleeves.requestNow()
                    ServerResponse.accepted().body(
                        "rebalance requested: proposal to Discord; orders only if trading is on and the window open\n",
                    )
                }
            }
            POST("/sleeves/live") { request ->
                guarded(request) {
                    when (val refusal = app.sleeves.goLive()) {
                        null -> ServerResponse.accepted().body("LIVE for this window: proposing and placing now\n")
                        else -> ServerResponse.status(HttpStatus.CONFLICT).body("refused: $refusal\n")
                    }
                }
            }
            POST("/sleeves/stop") { request ->
                guarded(request) {
                    app.sleeves.stop()
                    ServerResponse.ok().body("stopped: no order until restart\n")
                }
            }
        }

    private fun guarded(
        request: ServerRequest,
        handle: () -> ServerResponse,
    ): ServerResponse =
        if (request.headers().firstHeader(CONTROL_HEADER) == "1") {
            handle()
        } else {
            ServerResponse.status(HttpStatus.FORBIDDEN).body("send the $CONTROL_HEADER: 1 header\n")
        }

    private fun controlView(
        desk: SleeveDesk,
        orders: List<Order>,
        tags: Map<String, String>,
    ) = ControlView(
        trading = desk.describe(),
        halted = desk.halted(),
        now = Instant.now(),
        windowEnd = desk.windowEnd(),
        proposedAt = desk.proposedAt,
        proposals = desk.proposals,
        lastRun = desk.lastRun,
        orders = orders,
        tags = tags,
    )

    /**
     * `tickguard.autostart=false` builds everything and starts nothing, for a
     * test of the wiring that must not reach Toss.
     */
    @Bean
    fun tickguardLifecycle(
        app: Tickguard,
        config: Config,
        environment: Environment,
    ) = TickguardLifecycle(
        app,
        config,
        autoStartup = environment.getProperty("tickguard.autostart", "true").toBoolean(),
    )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(TickguardConfiguration::class.java)

        /** What the control page's buttons send and a cross-site form cannot. */
        const val CONTROL_HEADER = "X-Tickguard-Control"
    }
}
