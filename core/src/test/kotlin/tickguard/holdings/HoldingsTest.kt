package tickguard.holdings

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tickguard.json.StrictJson
import tickguard.testing.StubRest

class HoldingsTest {
    /** The shape of a real /api/v1/holdings response. The values are made up. */
    private val live =
        """
        {"result":{"totalPurchaseAmount":{"krw":"0","usd":"3.166"},"items":[
          {"symbol":"QQQM","name":"인베스코 나스닥 100","marketCountry":"US","currency":"USD",
           "quantity":"0.031415","lastPrice":"212.50","averagePurchasePrice":"101.230001"},
          {"symbol":"005930","name":"삼성전자","marketCountry":"KR","currency":"KRW",
           "quantity":"12","lastPrice":"71000","averagePurchasePrice":"70100"}]}}
        """.trimIndent()

    private fun parse(text: String) = parseHoldings(StrictJson.parse(text))

    @Test
    fun `reads positions out of the live response shape`() {
        val parsed = parse(live)

        assertThat(parsed.positions.keys).containsExactly("QQQM", "005930")
        assertThat("${parsed.positions.getValue("005930").averagePrice}").isEqualTo("70100")
        assertThat(parsed.markets).containsEntry("005930", "trade:kr").containsEntry("QQQM", "trade:us")
    }

    @Test
    fun `keeps fractional quantities exactly`() {
        assertThat("${parse(live).positions.getValue("QQQM").quantity}").isEqualTo("0.031415")
    }

    @Test
    fun `skips one unreadable row rather than discarding the portfolio`() {
        val parsed =
            parse(
                """
                {"result":{"items":[
                  {"symbol":"OK","quantity":"1","averagePurchasePrice":"2"},null,{"symbol":"X"}]}}
                """.trimIndent(),
            )

        assertThat(parsed.positions.keys).containsExactly("OK")
        assertThat(parsed.skipped).isEqualTo(2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"nope\"", "{}", """{"result":{"items":"nope"}}"""])
    fun `returns nothing for a body that is not the holdings shape`(body: String) {
        assertThat(parse(body).positions).isEmpty()
    }

    @Test
    fun `reports what was bought and what was sold`() =
        runTest {
            val changes = mutableListOf<Pair<List<String>, List<String>>>()
            val later =
                """
                {"result":{"items":[
                  {"symbol":"005930","marketCountry":"KR","quantity":"12","averagePurchasePrice":"70100"},
                  {"symbol":"NVDA","marketCountry":"US","quantity":"1","averagePurchasePrice":"200"}]}}
                """.trimIndent()
            val store =
                HoldingsStore(StubRest(live, later), onChange = { added, removed -> changes += added to removed })

            store.refresh()
            store.refresh()

            assertThat(changes.last()).isEqualTo(listOf("NVDA") to listOf("QQQM"))
        }

    @Test
    fun `stays quiet when nothing changed`() =
        runTest {
            val changes = mutableListOf<Any>()
            val store = HoldingsStore(StubRest(live), onChange = { added, removed -> changes += added to removed })

            store.refresh()
            changes.clear()
            store.refresh()

            assertThat(changes).isEmpty()
        }

    @Test
    fun `keeps the last known positions when a refresh fails`() =
        runTest {
            val errors = mutableListOf<Exception>()
            val store = HoldingsStore(StubRest(live, IllegalStateException("503")), onError = { errors += it })

            store.refresh()
            store.refresh()

            // Emptying here would silence every position rule until the next success.
            assertThat(store.current().positions).hasSize(2)
            assertThat(store.stats()).isEqualTo(HoldingsStats(refreshes = 1, failures = 1, skipped = 0))
            assertThat(errors).hasSize(1)
        }

    @Test
    fun `has no positions before the first refresh`() {
        assertThat(HoldingsStore(StubRest(live)).current().positions).isEmpty()
    }

    @Test
    fun `asks for holdings with the account header`() =
        runTest {
            val rest = StubRest(live)

            HoldingsStore(rest).refresh()

            assertThat(rest.asked.single().withAccount).isTrue()
        }
}
