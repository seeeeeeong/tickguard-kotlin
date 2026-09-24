package tickguard.news

import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tickguard.json.StrictJson
import java.time.Instant

/** EDGAR's parallel arrays, one index per filing: two 8-Ks among other forms. */
private val SUBMISSIONS =
    """
    {"filings":{"recent":{
      "form":["8-K","10-Q","8-K","4"],
      "accessionNumber":["0001018724-26-000081","0001018724-26-000077","0001018724-26-000070","x"],
      "acceptanceDateTime":["2026-07-30T20:01:05.000Z","2026-07-31T10:00:00.000Z","2026-06-02T12:00:00.000Z","x"],
      "filingDate":["2026-07-30","2026-07-31","2026-06-02","2026-06-01"],
      "primaryDocument":["amzn-20260730.htm","q.htm","amzn-20260602.htm","f.xml"],
      "items":["2.02,9.01","","5.02",""]
    }}}
    """.trimIndent()

class SecTest {
    @Nested
    inner class ParseTickers {
        @Test
        fun `maps a ticker to its zero-padded CIK`() {
            val map =
                parseTickers(StrictJson.parse("""{"0":{"cik_str":1018724,"ticker":"AMZN","title":"AMAZON COM INC"}}"""))

            assertThat(map["AMZN"]).isEqualTo("0001018724")
        }
    }

    @Nested
    inner class ParseFilings {
        @Test
        fun `keeps 8-Ks only, named by what they report`() {
            val items = parseFilings(StrictJson.parse(SUBMISSIONS), "AMZN", "0001018724")

            assertThat(items.map { it.title }).containsExactly("8-K · 실적 발표", "8-K · 임원·이사 변동")
            val first = items.first()
            assertThat(first.source).isEqualTo(NewsSourceName.SEC)
            assertThat(first.id).isEqualTo("0001018724-26-000081")
            assertThat(first.publisher).isEqualTo("SEC EDGAR")
            assertThat(first.url)
                .isEqualTo("https://www.sec.gov/Archives/edgar/data/1018724/000101872426000081/amzn-20260730.htm")
            assertThat(first.publishedAt).isEqualTo(Instant.parse("2026-07-30T20:01:05.000Z"))
        }

        @Test
        fun `returns nothing for a body without recent filings`() {
            assertThat(parseFilings(JsonObject(emptyMap()), "AMZN", "1")).isEmpty()
        }
    }

    @Nested
    inner class DescribeItems {
        @Test
        fun `drops the exhibits item that rides along with nearly every 8-K`() {
            assertThat(describeItems("2.02,9.01")).isEqualTo("실적 발표")
            assertThat(describeItems("9.01")).isEqualTo("재무제표·첨부서류")
        }

        @Test
        fun `names an item it has no translation for by number`() {
            assertThat(describeItems("6.05")).isEqualTo("Item 6.05")
        }
    }

    @Nested
    inner class UserAgent {
        @Test
        fun `puts a name in front of an address given alone`() {
            assertThat(userAgent("ops@example.com")).isEqualTo("tickguard ops@example.com")
            assertThat(userAgent("  Lee ops@example.com ")).isEqualTo("Lee ops@example.com")
        }
    }
}
