package tickguard.rest

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.StubRest
import tickguard.testing.decimal
import tickguard.testing.failureOf

class ExchangeRateTest {
    @Test
    fun `reads the documented rate, asking for dollars in won`() =
        runTest {
            val rest =
                StubRest("""{"result":{"baseCurrency":"USD","quoteCurrency":"KRW","rate":"1380.5","midRate":"1375"}}""")

            assertThat(fetchUsdKrw(rest)).isEqualTo(decimal("1380.5"))
            assertThat(
                rest.asked.single().query,
            ).containsEntry("baseCurrency", "USD").containsEntry("quoteCurrency", "KRW")
            assertThat(rest.asked.single().group).isEqualTo(RateLimitGroup.MARKET_INFO)
        }

    @Test
    fun `refuses a response without a rate rather than guessing one`() =
        runTest {
            assertThat(failureOf { fetchUsdKrw(StubRest("""{"result":{}}""")) }).hasMessageContaining("no rate")
        }
}
