package tickguard.stream

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tickguard.gateway.ServerFrame
import java.time.Instant

class TradeTest {
    @Test
    fun `decodes a live payload without touching a float`() {
        val result = decodeTrade(ServerFrame.Message("trade:us:AAPL", LIVE))

        val trade = (result as DecodeResult.Ok).trade
        assertThat(trade.type).isEqualTo("trade:us")
        assertThat(trade.code).isEqualTo("AAPL")
        assertThat(trade.price.toPlainString()).isEqualTo("339.24")
        assertThat(trade.volume.toPlainString()).isEqualTo("615")
        assertThat(trade.at).isEqualTo(Instant.parse("2026-09-22T14:07:43.000Z"))
        assertThat(trade.currency).isEqualTo("USD")
    }

    @Test
    fun `defaults to KRW, which KR quotes leave out`() {
        val result = decodeTrade(ServerFrame.Message("trade:kr:005930", LIVE.without("currency")))

        assertThat((result as DecodeResult.Ok).trade.currency).isEqualTo("KRW")
    }

    // One odd frame must not take down a stream carrying thousands a minute.
    @ParameterizedTest(name = "reports {0} as a failure instead of throwing")
    @MethodSource("oddFrames")
    fun `reports an odd frame as a failure instead of throwing`(
        label: String,
        topic: String,
        data: JsonElement,
        reason: String,
    ) {
        val result = decodeTrade(ServerFrame.Message(topic, data))

        val failure = (result as DecodeResult.Failed).failure
        assertThat(failure.reason).describedAs(label).contains(reason)
        assertThat(failure.topic).isEqualTo(topic)
    }

    companion object {
        /** A payload copied verbatim from a live US session. */
        private val LIVE =
            JsonObject(
                mapOf(
                    "price" to JsonPrimitive("339.24"),
                    "volume" to JsonPrimitive("615"),
                    "timestamp" to JsonPrimitive("2026-09-22T23:07:43.000+09:00"),
                    "currency" to JsonPrimitive("USD"),
                ),
            )

        private fun JsonObject.without(key: String) = JsonObject(this - key)

        private fun JsonObject.with(
            key: String,
            value: JsonElement,
        ) = JsonObject(this + (key to value))

        @JvmStatic
        fun oddFrames() =
            listOf(
                Arguments.of("a topic with no code", "trade:us", LIVE, "no code segment"),
                Arguments.of("a non-object payload", "trade:us:AAPL", JsonPrimitive("nope"), "not an object"),
                Arguments.of(
                    "a numeric price",
                    "trade:us:AAPL",
                    LIVE.with("price", JsonPrimitive(339.24)),
                    "price is not a string",
                ),
                Arguments.of("a missing volume", "trade:us:AAPL", LIVE.without("volume"), "volume is not a string"),
                Arguments.of("a null volume", "trade:us:AAPL", LIVE.with("volume", JsonNull), "volume is not a string"),
                Arguments.of(
                    "an unparsable timestamp",
                    "trade:us:AAPL",
                    LIVE.with("timestamp", JsonPrimitive("yesterday")),
                    "unparsable timestamp",
                ),
                Arguments.of("a non-numeric price", "trade:us:AAPL", LIVE.with("price", JsonPrimitive("--")), "price"),
            )
    }
}
