package tickguard.news

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.text.JS_SPACE
import tickguard.text.jsTrim
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.time.Duration.Companion.hours

// 8-K filings from SEC EDGAR: the primary source, filed before anyone writes
// the story. A listed company must file one within four business days of an
// event that matters — earnings, a departing CEO, a deal, a lawsuit.

/**
 * How long to leave SEC alone after a 403. The block page it serves is
 * titled "Request Rate Threshold Exceeded" and is applied per address — a
 * shared office or café address can arrive already blocked by someone
 * else's traffic, as a first live test did — and polling into it every few
 * minutes is exactly the behaviour the rule is there to stop.
 */
val SEC_BLOCK_PAUSE = 1.hours

/** A current report and its amendment: the forms filed when something happens. */
private val CURRENT_REPORTS = setOf("8-K", "8-K/A")

/** 9.01 accompanies nearly every 8-K; named only when it is all there is. */
private val ITEM_NAMES =
    mapOf(
        "1.01" to "중요 계약 체결",
        "1.02" to "중요 계약 해지",
        "1.03" to "파산·법정관리",
        "1.05" to "중대한 사이버보안 사고",
        "2.01" to "자산 인수·처분 완료",
        "2.02" to "실적 발표",
        "2.03" to "채무 발생",
        "2.04" to "채무 조기상환 사유 발생",
        "2.05" to "구조조정 비용",
        "2.06" to "자산 손상",
        "3.01" to "상장폐지·상장요건 미달 통지",
        "3.02" to "미등록 증권 발행",
        "3.03" to "주주 권리 변경",
        "4.01" to "감사인 변경",
        "4.02" to "재무제표 신뢰성 상실",
        "5.01" to "경영권 변동",
        "5.02" to "임원·이사 변동",
        "5.03" to "정관 변경",
        "5.07" to "주주총회 결과",
        "7.01" to "공정공시(Reg FD)",
        "8.01" to "기타 중요 사항",
        "9.01" to "재무제표·첨부서류",
    )

/**
 * SEC's documented shape is a name and an address. An address alone is sent
 * with this program's name in front, rather than refused, since the address
 * is the part SEC needs.
 */
fun userAgent(contact: String): String {
    val trimmed = contact.jsTrim()
    return if (JS_SPACE.containsMatchIn(trimmed)) trimmed else "tickguard $trimmed"
}

/** `{"0": {"cik_str": 1018724, "ticker": "AMZN", ...}, ...}` → AMZN → 0001018724 */
fun parseTickers(body: JsonElement?): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (entry in (body as? JsonObject)?.values.orEmpty()) {
        val fields = entry as? JsonObject ?: continue
        val cik = (fields["cik_str"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
        val ticker = (fields["ticker"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (cik != null && ticker != null) map[ticker.uppercase()] = cik.padStart(CIK_DIGITS, '0')
    }
    return map
}

/** EDGAR pads a CIK to ten digits in its URLs. */
private const val CIK_DIGITS = 10

/** EDGAR returns recent filings as parallel arrays, one index per filing. */
fun parseFilings(
    body: JsonElement?,
    code: String,
    cik: String,
): List<NewsItem> {
    val recent =
        ((body as? JsonObject)?.get("filings") as? JsonObject)?.get("recent") as? JsonObject ?: return emptyList()
    val column = { name: String -> (recent[name] as? JsonArray).orEmpty() }
    val forms = column("form")
    val accessions = column("accessionNumber")
    val accepted = column("acceptanceDateTime")
    val filed = column("filingDate")
    val documents = column("primaryDocument")
    val itemLists = column("items")

    return forms.indices.mapNotNull { index ->
        val form = forms[index].text()
        val accession = accessions.getOrNull(index)?.text()
        val publishedAt = instantOf(accepted.getOrNull(index)?.text() ?: filed.getOrNull(index)?.text().orEmpty())
        if (form !in CURRENT_REPORTS) return@mapNotNull null
        if (accession == null || publishedAt == null) return@mapNotNull null

        NewsItem(
            source = NewsSourceName.SEC,
            id = accession,
            code = code,
            title = "$form · ${describeItems(itemLists.getOrNull(index)?.text().orEmpty())}",
            publisher = "SEC EDGAR",
            url =
                "https://www.sec.gov/Archives/edgar/data/${cik.trimStart('0')}/${accession.replace("-", "")}/" +
                    documents.getOrNull(index)?.text().orEmpty(),
            publishedAt = publishedAt,
        )
    }
}

fun describeItems(items: String): String {
    val codes = items.split(",").map { it.jsTrim() }.filter { it.isNotEmpty() }
    val meaningful = if (codes.size > 1) codes.filter { it != "9.01" } else codes
    if (meaningful.isEmpty()) return "내용 미상"
    return meaningful.joinToString(", ") { ITEM_NAMES[it] ?: "Item $it" }
}

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** An acceptance time is an instant; a filing date alone is read as midnight UTC, as `Date.parse` read it. */
private fun instantOf(text: String): Instant? =
    try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
