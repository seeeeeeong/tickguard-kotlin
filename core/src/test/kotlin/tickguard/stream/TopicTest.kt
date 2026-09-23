package tickguard.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TopicTest {
    @Test
    fun `splits on the last colon, since the type itself contains colons`() {
        assertThat(parseTopic("trade:us:AAPL")).isEqualTo(ParsedTopic("trade:us", "AAPL"))
        assertThat(parseTopic("orderbook:kr:005930")).isEqualTo(ParsedTopic("orderbook:kr", "005930"))
    }

    @Test
    fun `handles a type with a different number of segments`() {
        // personal:order carries an accountSeq, not a symbol, and has no market part.
        assertThat(parseTopic("personal:order:3")).isEqualTo(ParsedTopic("personal:order", "3"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["trade", "trade:us:", ":AAPL", ""])
    fun `returns null for a malformed topic`(topic: String) {
        assertThat(parseTopic(topic)).isNull()
    }

    @Test
    fun `names the channel without its market`() {
        assertThat(channelOf("trade:us")).isEqualTo("trade")
        assertThat(channelOf("orderbook:kr")).isEqualTo("orderbook")
        assertThat(channelOf("personal:order")).isEqualTo("personal")
    }
}
