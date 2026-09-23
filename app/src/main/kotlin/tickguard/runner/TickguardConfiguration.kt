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
import tickguard.network.reasonOf
import tickguard.observability.Metrics
import tickguard.observability.StatusSource
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
                store = SqliteStore.open(config.storePath),
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
    }
}
