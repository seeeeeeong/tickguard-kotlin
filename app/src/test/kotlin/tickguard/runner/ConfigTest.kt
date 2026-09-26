package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tickguard.subscribe.Topic
import tickguard.trading.SleeveMode
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConfigTest {
    private val base = mapOf("TOSS_CLIENT_ID" to "id", "TOSS_CLIENT_SECRET" to "secret", "TOSS_ACCOUNT_SEQ" to "1")

    private fun load(vararg overrides: Pair<String, String?>): Config {
        val env = base + overrides
        return loadConfig { env[it] }
    }

    @Test
    fun `runs on defaults with only credentials set`() {
        val config = load()

        assertThat(config.rules.drawdownFraction).isEqualTo("0.07")
        assertThat(config.rules.drawdownFor).isEqualTo(2.minutes)
        assertThat(config.rules.drawdownCooldown).isEqualTo(4.hours)
        assertThat(config.rules.drawdownEscalateEvery).isEqualTo("0.02")
        assertThat(config.groupWait).isEqualTo(30.seconds)
        assertThat(config.slackWebhookUrl).isNull()
        assertThat(config.discordWebhookUrl).isNull()
    }

    @Test
    fun `journals placements beside the database, and refuses to trade on Postgres without a persistent place`() {
        val pg =
            arrayOf(
                "TICKGUARD_PG_URL" to "jdbc:postgresql://db/tickguard",
                "TICKGUARD_PG_USER" to "u",
                "TICKGUARD_PG_PASSWORD" to "p",
            )

        assertThat(load("TICKGUARD_DB" to "/data/tickguard.db").placements).isEqualTo("/data/placements")
        assertThat(
            load("TICKGUARD_PLACEMENTS" to " ", "TICKGUARD_DB" to "/data/tickguard.db").placements,
        ).isEqualTo("/data/placements")
        assertThatThrownBy { load(*pg, "TICKGUARD_TRADING" to "on") }.isInstanceOf(ConfigError::class.java)
        assertThat(load(*pg, "TICKGUARD_TRADING" to "on", "TICKGUARD_PLACEMENTS" to "/data/placements").placements)
            .isEqualTo("/data/placements")
    }

    @Test
    fun `refuses the dip sleeve beside the sleeves it replaced, which would count the same dollars`() {
        assertThatThrownBy { load("TICKGUARD_SLEEVE_D" to "DRY_RUN", "TICKGUARD_SLEEVE_A" to "LIVE") }
            .isInstanceOf(ConfigError::class.java)
        assertThat(load("TICKGUARD_SLEEVE_D" to "LIVE").trading.modes["D"]).isEqualTo(tickguard.trading.SleeveMode.LIVE)
    }

    @Test
    fun `reads the escalation step as a ratio, rejecting a percent`() {
        assertThat(load("TICKGUARD_DRAWDOWN_ESCALATE" to "0.05").rules.drawdownEscalateEvery).isEqualTo("0.05")
        assertThatThrownBy { load("TICKGUARD_DRAWDOWN_ESCALATE" to "2") }.isInstanceOf(ConfigError::class.java)
    }

    @Test
    fun `keeps ticks for 90 days unless told otherwise, counted in days`() {
        assertThat(load().tickRetention).isEqualTo(90.days)
        assertThat(load("TICKGUARD_TICK_RETENTION_DAYS" to "7").tickRetention).isEqualTo(7.days)
        assertThatThrownBy { load("TICKGUARD_TICK_RETENTION_DAYS" to "0") }.isInstanceOf(ConfigError::class.java)
    }

    @Test
    fun `leaves SEC collection off without a contact, and refuses one with no email`() {
        assertThat(load().secContact).isNull()
        assertThat(
            load("TICKGUARD_SEC_CONTACT" to "tickguard ops@example.com").secContact,
        ).isEqualTo("tickguard ops@example.com")
        // A name alone is the common mistake, and SEC answers it with a block.
        assertThatThrownBy { load("TICKGUARD_SEC_CONTACT" to "tickguard") }.isInstanceOf(ConfigError::class.java)
    }

    @Test
    fun `judges news only with an LLM key, on a bounded daily budget`() {
        assertThat(load().llm).isEqualTo(LlmConfig(apiKey = null, model = "deepseek-flash", dailyLimit = 300))
        assertThat(load().newsAlertImpact).isEqualTo(0.6)
        assertThatThrownBy { load("TICKGUARD_NEWS_ALERT_IMPACT" to "70") }.isInstanceOf(ConfigError::class.java)
    }

    @Test
    fun `names what is missing rather than failing later`() {
        assertThatThrownBy { load("TOSS_ACCOUNT_SEQ" to null) }.hasMessageContaining("TOSS_ACCOUNT_SEQ")
    }

    @Test
    fun `rejects a percent written where a ratio belongs`() {
        // 7 instead of 0.07 would otherwise mean a threshold nothing ever crosses.
        assertThatThrownBy { load("TICKGUARD_DRAWDOWN" to "7") }
            .isInstanceOf(ConfigError::class.java)
            .hasMessageContaining("ratio between 0 and 1")
    }

    @Test
    fun `rejects a non-integer duration`() {
        assertThatThrownBy { load("TICKGUARD_SLA_INTERVAL_MS" to "soon") }.isInstanceOf(ConfigError::class.java)
        assertThatThrownBy { load("TICKGUARD_SLA_INTERVAL_MS" to "1.5") }.isInstanceOf(ConfigError::class.java)
    }

    @Test
    fun `keeps state in SQLite unless given a Postgres URL, and then insists on credentials`() {
        assertThat(load().store).isEqualTo(StoreConfig.Sqlite("tickguard.db"))

        val postgres =
            load(
                "TICKGUARD_PG_URL" to "jdbc:postgresql://db:5432/tickguard",
                "TICKGUARD_PG_USER" to "tickguard",
                "TICKGUARD_PG_PASSWORD" to "pg-secret",
            )
        assertThat(
            postgres.store,
        ).isEqualTo(StoreConfig.Postgres("jdbc:postgresql://db:5432/tickguard", "tickguard", "pg-secret"))
        assertThat(postgres.store.toString()).doesNotContain("pg-secret")

        assertThatThrownBy { load("TICKGUARD_PG_URL" to "jdbc:postgresql://db:5432/tickguard") }
            .isInstanceOf(ConfigError::class.java)
            .hasMessageContaining("TICKGUARD_PG_USER")
        assertThatThrownBy { load("TICKGUARD_PG_URL" to "postgres://db/tickguard") }
            .isInstanceOf(ConfigError::class.java)
            .hasMessageContaining("jdbc:postgresql://")
    }

    @Test
    fun `keeps every order in the process unless the user turns trading on, sleeve by sleeve`() {
        val quiet = load().trading
        assertThat(quiet.enabled).isFalse()
        assertThat(quiet.modes.values).containsOnly(SleeveMode.OFF)

        val live =
            load(
                "TICKGUARD_TRADING" to "on",
                "TICKGUARD_SLEEVE_A" to "LIVE",
                "TICKGUARD_SLEEVE_B" to "DRY_RUN",
            ).trading
        assertThat(live.enabled).isTrue()
        assertThat(
            live.modes,
        ).containsEntry("A", SleeveMode.LIVE).containsEntry("B", SleeveMode.DRY_RUN).containsEntry("C", SleeveMode.OFF)

        assertThat(load("TICKGUARD_TRADING" to "yes").trading.enabled).isFalse()
        assertThatThrownBy {
            load("TICKGUARD_SLEEVE_A" to "live")
        }.isInstanceOf(ConfigError::class.java).hasMessageContaining("TICKGUARD_SLEEVE_A")
    }

    @Test
    fun `treats an empty webhook as absent, not as an empty URL`() {
        assertThat(load("TICKGUARD_SLACK_WEBHOOK" to "").slackWebhookUrl).isNull()
        assertThat(load("TICKGUARD_DISCORD_WEBHOOK" to "").discordWebhookUrl).isNull()
    }

    @Test
    fun `never prints the secrets it holds`() {
        val printed =
            load(
                "TICKGUARD_LLM_API_KEY" to "sk-live-123",
                "TICKGUARD_DISCORD_WEBHOOK" to "https://discord.com/api/webhooks/1/secret-token",
            ).toString()
        assertThat(printed).doesNotContain("secret").doesNotContain("sk-live-123")
    }

    @Test
    fun `requires a market, since a bare ticker does not name one`() {
        assertThat(
            parseSymbols("NVDA:us,005930:kr"),
        ).containsExactly(Topic("trade:us", "NVDA"), Topic("trade:kr", "005930"))
    }

    @Test
    fun `refuses a ticker without a market rather than guessing`() {
        // Guessing puts a KR code on a US channel, where it is rejected quietly.
        assertThatThrownBy { parseSymbols("NVDA") }.isInstanceOf(ConfigError::class.java)
        assertThatThrownBy { parseSymbols("NVDA:nasdaq") }.isInstanceOf(ConfigError::class.java)
    }

    @Test
    fun `is empty when unset`() {
        assertThat(parseSymbols(null)).isEmpty()
        assertThat(parseSymbols("  ")).isEmpty()
    }
}
