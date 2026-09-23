package tickguard.subscribe

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeclarationTest {
    private fun trade(code: String) = Topic("trade:us", code)

    @Test
    fun `matches the full key the server uses in acks and message topics`() {
        assertThat(topicKey(trade("AAPL"))).isEqualTo("trade:us:AAPL")
        assertThat(topicKey(Topic("personal:order", "3"))).isEqualTo("personal:order:3")
    }

    @Test
    fun `groups codes under their channel and puts the id first, byte for byte as the original`() {
        val payload = buildDeclaration(listOf(trade("AAPL"), Topic("orderbook:kr", "005930"), trade("TSLA")), "g1-1")

        assertThat(payload).isEqualTo(
            """[{"id":"g1-1"},{"type":"orderbook:kr","codes":["005930"]},""" +
                """{"type":"trade:us","codes":["AAPL","TSLA"]}]""",
        )
    }

    @Test
    fun `produces identical bytes for the same set in any order, so declarations diff cleanly`() {
        val one = buildDeclaration(listOf(trade("TSLA"), trade("AAPL")), "g1-1")
        val other = buildDeclaration(listOf(trade("AAPL"), trade("TSLA")), "g1-1")

        assertThat(one).isEqualTo(other)
    }

    @Test
    fun `collapses duplicates`() {
        assertThat(buildDeclaration(listOf(trade("AAPL"), trade("AAPL")), "g1-1"))
            .isEqualTo("""[{"id":"g1-1"},{"type":"trade:us","codes":["AAPL"]}]""")
    }

    @Test
    fun `sends a bare empty array to clear everything rather than guessing at an id-only array`() {
        assertThat(buildDeclaration(emptyList(), "g1-1")).isEqualTo("[]")
    }

    @Test
    fun `counts channel x code combinations, which is what the 100 limit counts`() {
        assertThat(countTopics(listOf(trade("AAPL"), Topic("orderbook:us", "AAPL"), trade("AAPL")))).isEqualTo(2)
    }
}
