package tickguard.runner

import tickguard.auth.TossCredentials
import tickguard.auth.loadCredentials
import tickguard.subscribe.Topic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConfigError(
    message: String,
) : IllegalArgumentException(message)

data class RulesConfig(
    val drawdownFraction: String,
    val drawdownFor: Duration,
    val drawdownCooldown: Duration,
    val rapidMoveFraction: String,
    val rapidMoveWindow: Duration,
)

data class LlmConfig(
    val apiKey: String?,
    val model: String,
    val dailyLimit: Int,
) {
    override fun toString() = "LlmConfig(apiKey=${apiKey?.let { "***" }}, model=$model, dailyLimit=$dailyLimit)"
}

data class Intervals(
    val holdings: Duration,
    val slaCheck: Duration,
    val reconcile: Duration,
    val prune: Duration,
    val news: Duration,
)

data class FallbackConfig(
    /** REST stands in for the stream after this much silence, polling at [interval]. */
    val silence: Duration,
    val interval: Duration,
)

/**
 * Everything that varies between a laptop and a server, read once at startup.
 *
 * Environment variables rather than a config file: there are a dozen values,
 * and a file brings a schema, validation and a migration story for something
 * that fits in a compose block. Every value is resolved and checked here, so
 * a bad setting fails at boot with a message rather than at 3am with a stack
 * trace.
 *
 * The variable names, defaults and messages are the original's, so the same
 * `.env` and compose file drive either service.
 */
data class Config(
    val credentials: TossCredentials,
    /** From `GET /api/v1/accounts`. Required for holdings. */
    val accountSeq: String,
    /** Watched on top of whatever is held. `NVDA:us`, `005930:kr`. */
    val extraSymbols: List<Topic>,
    val rules: RulesConfig,
    val slackWebhookUrl: String?,
    /** `tickguard you@example.com`. Without it, SEC filings are not collected. */
    val secContact: String?,
    /** Without an API key, headlines are collected but not judged. */
    val llm: LlmConfig,
    /** A relevant story at or above this impact is alerted on by itself. */
    val newsAlertImpact: Double,
    /** Signals inside this window go out as one message. */
    val groupWait: Duration,
    val intervals: Intervals,
    val slaThreshold: Duration,
    val fallback: FallbackConfig,
    /** Ticks older than this are pruned. Bounds how far back a backtest can see. */
    val tickRetention: Duration,
    val storePath: String,
    val metricsPort: Int,
) {
    override fun toString() = "Config(accountSeq=$accountSeq, extraSymbols=$extraSymbols, rules=$rules, llm=$llm, …)"
}

/** Reads each variable through [env], so the whole path is testable without a process environment. */
fun loadConfig(env: (String) -> String?): Config {
    val read = EnvReader(env)
    return Config(
        credentials =
            loadCredentials(
                listOf("TOSS_CLIENT_ID", "TOSS_CLIENT_SECRET").associateWith { env(it).orEmpty() },
            ),
        accountSeq = read.required("TOSS_ACCOUNT_SEQ"),
        extraSymbols = parseSymbols(env("TICKGUARD_EXTRA_SYMBOLS")),
        rules =
            RulesConfig(
                drawdownFraction = read.ratio("TICKGUARD_DRAWDOWN", "0.07"),
                drawdownFor = read.duration("TICKGUARD_DRAWDOWN_FOR_MS", 2.minutes),
                drawdownCooldown = read.duration("TICKGUARD_DRAWDOWN_COOLDOWN_MS", 60.minutes),
                rapidMoveFraction = read.ratio("TICKGUARD_RAPID_MOVE", "0.03"),
                rapidMoveWindow = read.duration("TICKGUARD_RAPID_MOVE_WINDOW_MS", 5.minutes),
            ),
        slackWebhookUrl = env("TICKGUARD_SLACK_WEBHOOK")?.takeIf { it.isNotEmpty() },
        secContact = secContact(env("TICKGUARD_SEC_CONTACT")),
        llm =
            LlmConfig(
                apiKey = env("TICKGUARD_LLM_API_KEY")?.takeIf { it.isNotEmpty() },
                model = env("TICKGUARD_LLM_MODEL")?.takeIf { it.isNotEmpty() } ?: "deepseek-flash",
                // About sixty stories a day for three symbols; five times that is
                // headroom for a busy day without leaving the bill open-ended.
                dailyLimit = read.integer("TICKGUARD_LLM_DAILY_LIMIT", DEFAULT_DAILY_LIMIT, minimum = 1),
            ),
        // 0.7 fired on nothing in a day and a half of news, missing a 5% drop
        // with a stated cause (scored 0.60) and a lock-up expiry (0.50). At 0.6
        // one of the two pages, at one false alarm in the labelled set; 0.5
        // catches both at four. eval/verdict-labels.csv holds the measurement.
        newsAlertImpact = read.number(read.ratio("TICKGUARD_NEWS_ALERT_IMPACT", "0.6")) ?: 0.0,
        groupWait = read.duration("TICKGUARD_GROUP_WAIT_MS", 30.seconds),
        intervals =
            Intervals(
                holdings = read.duration("TICKGUARD_HOLDINGS_INTERVAL_MS", 5.minutes),
                slaCheck = read.duration("TICKGUARD_SLA_INTERVAL_MS", 1.minutes),
                reconcile = read.duration("TICKGUARD_RECONCILE_INTERVAL_MS", 5.minutes),
                prune = read.duration("TICKGUARD_PRUNE_INTERVAL_MS", 60.minutes),
                news = read.duration("TICKGUARD_NEWS_INTERVAL_MS", 5.minutes),
            ),
        slaThreshold = read.duration("TICKGUARD_SLA_THRESHOLD_MS", 10.minutes),
        fallback =
            FallbackConfig(
                silence = read.duration("TICKGUARD_FALLBACK_SILENCE_MS", 30.seconds),
                // Three symbols in one call every ten seconds, against a group allowed 15/s.
                interval = read.duration("TICKGUARD_FALLBACK_INTERVAL_MS", 10.seconds),
            ),
        // Days, unlike every other duration here: 90 days in milliseconds is a
        // ten-digit number nobody can check by eye.
        tickRetention = read.integer("TICKGUARD_TICK_RETENTION_DAYS", DEFAULT_RETENTION_DAYS, minimum = 1).days,
        storePath = env("TICKGUARD_DB") ?: "tickguard.db",
        metricsPort = read.integer("TICKGUARD_METRICS_PORT", DEFAULT_METRICS_PORT),
    )
}

/** A generous daily ceiling on model calls, so a news storm costs at most this. */
private const val DEFAULT_DAILY_LIMIT = 300

/** Enough history for a quarter's backtest. */
private const val DEFAULT_RETENTION_DAYS = 90

/** The port the original served its status page and metrics on. */
private const val DEFAULT_METRICS_PORT = 9_464

/**
 * `NVDA:us,005930:kr`. The market is explicit because a bare ticker does not
 * say which one it belongs to, and guessing puts a KR code on a US channel
 * where it is rejected rather than failing here.
 */
fun parseSymbols(value: String?): List<Topic> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split(",").map { entry ->
        val parts = entry.trim().split(":")
        val code = parts.getOrNull(0)
        val market = parts.getOrNull(1)
        if (code.isNullOrEmpty() || (market != "kr" && market != "us")) {
            throw ConfigError("TICKGUARD_EXTRA_SYMBOLS entry \"$entry\" must look like NVDA:us or 005930:kr.")
        }
        Topic("trade:$market", code)
    }
}

/**
 * SEC asks for a reachable email in every request. Checked for the one thing
 * checkable here, an address at all, because the common mistake is a name
 * alone, and SEC answers that with a block rather than an error message.
 */
private fun secContact(value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (!EMAIL.containsMatchIn(value)) {
        throw ConfigError(
            "TICKGUARD_SEC_CONTACT must include an email SEC can reach, " +
                "e.g. \"tickguard you@example.com\". Got \"$value\".",
        )
    }
    return value.trim()
}

/** Strictly between 0 and 1: a ratio of none or all of a price is not a threshold. */
private val OPEN_UNIT = Math.nextUp(0.0)..Math.nextDown(1.0)

/** An address at all: something, an at sign, something, a dot, something. */
private val EMAIL = Regex("\\S+@\\S+\\.\\S+")

/**
 * Values parsed as the original's `Number()` parsed them: trimmed, and whole numbers
 * written with a fraction allowed.
 */
private class EnvReader(
    private val env: (String) -> String?,
) {
    fun required(key: String): String =
        env(key)?.takeIf { it.isNotEmpty() } ?: throw ConfigError("$key is not set. See .env.example.")

    /** As `Number()` read it: surrounding whitespace ignored; null where it gave NaN or Infinity. */
    fun number(value: String): Double? = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /** A ratio, not a percent: 0.07 is seven percent. Rejects the common mix-up. */
    fun ratio(
        key: String,
        fallback: String,
    ): String {
        val value = env(key)?.takeIf { it.isNotEmpty() } ?: return fallback
        val parsed = number(value)
        if (parsed == null || parsed !in OPEN_UNIT) {
            throw ConfigError("$key must be a ratio between 0 and 1, e.g. 0.07 for 7%. Got $value.")
        }
        return value
    }

    fun duration(
        key: String,
        fallback: Duration,
    ): Duration = integer(key, fallback.inWholeMilliseconds.toInt(), minimum = 1).milliseconds

    fun integer(
        key: String,
        fallback: Int,
        minimum: Int = 0,
    ): Int {
        val value = env(key)?.takeIf { it.isNotEmpty() } ?: return fallback
        val parsed = number(value)?.takeIf { it == Math.floor(it) }
        if (parsed == null || parsed < minimum) {
            throw ConfigError("$key must be an integer of at least $minimum. Got $value.")
        }
        return parsed.toInt()
    }
}
