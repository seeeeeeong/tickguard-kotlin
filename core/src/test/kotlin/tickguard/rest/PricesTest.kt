package tickguard.rest

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.json.StrictJson

class PricesTest {
    @Test
    fun `reads each row's last price as a decimal`() {
        val prices = parsePrices(StrictJson.parse("""{"result":[{"symbol":"AAPL","lastPrice":"339.20"}]}"""))

        assertThat(prices.mapValues { "${it.value}" }).containsExactlyEntriesOf(mapOf("AAPL" to "339.2"))
    }

    @Test
    fun `reads the live response shape, keeping every digit`() {
        val body =
            """{"result":[{"symbol":"TSLA","timestamp":"2026-09-23T00:27:33.000+09:00","lastPrice":"376.815","currency":"USD"}]}"""

        assertThat("${parsePrices(StrictJson.parse(body)).getValue("TSLA")}").isEqualTo("376.815")
    }

    @Test
    fun `skips a row it cannot read and keeps the rest`() {
        val body =
            """{"result":[{"symbol":"A","lastPrice":"--"},{"symbol":"B","lastPrice":1},null,{"symbol":"C","lastPrice":"10"}]}"""

        assertThat(parsePrices(StrictJson.parse(body)).keys).containsExactly("C")
    }

    @Test
    fun `reads nothing from a body without results`() {
        assertThat(parsePrices(StrictJson.parse("""{"error":"x"}"""))).isEmpty()
        assertThat(parsePrices(null)).isEmpty()
    }

    @Test
    fun `asks in batches of fifty, since one call per symbol would spend the group`() =
        runTest {
            val asked = mutableListOf<String?>()
            val rest =
                object : RestClient {
                    override val limiter = RateLimiter()

                    override suspend fun get(spec: RequestSpec): JsonElement {
                        asked += spec.query["symbols"]
                        return buildJsonObject {
                            put(
                                "result",
                                buildJsonArray {
                                    spec.query.getValue("symbols").orEmpty().split(",").forEach { symbol ->
                                        addJsonObject {
                                            put("symbol", symbol)
                                            put("lastPrice", JsonPrimitive("1"))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

            val prices = fetchPrices(rest, List(120) { "S$it" })

            assertThat(asked.map { it!!.split(",").size }).containsExactly(50, 50, 20)
            assertThat(prices).hasSize(120)
        }
}
