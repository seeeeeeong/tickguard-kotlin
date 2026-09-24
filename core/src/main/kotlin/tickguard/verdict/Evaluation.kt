package tickguard.verdict

import tickguard.text.jsTrim
import tickguard.text.toFixed

/**
 * Scores model verdicts against labels a person wrote without seeing them.
 *
 * Three questions, because they fail differently. Relevance: does the model
 * tell filler from news. Direction: when both agree a story matters, do they
 * agree which way. Alerting: of the stories that would have paged, how many
 * the person wanted (precision), and of those they wanted, how many would
 * have paged (recall) — at several thresholds, so the threshold is chosen
 * from data rather than guessed.
 */
data class Label(
    val key: String,
    val relevant: Boolean,
    val direction: Direction,
    val alert: Boolean,
)

data class AlertScore(
    val threshold: Double,
    val fired: Int,
    val wanted: Int,
    val hits: Int,
    /** Null when nothing fired: no alerts means no precision to speak of. */
    val precision: Double?,
    val recall: Double?,
)

data class Relevance(
    val accuracy: Double,
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
    val trueNegative: Int,
)

data class DirectionAgreement(
    val agreed: Int,
    val compared: Int,
)

data class EvaluationReport(
    val scored: Int,
    val missing: Int,
    val relevance: Relevance,
    val direction: DirectionAgreement,
    val alerts: List<AlertScore>,
)

/** The label file's columns, in the order a person fills them. */
val LABEL_COLUMNS =
    listOf("key", "code", "publisher", "published_kst", "title", "title_ko", "relevant", "direction", "alert")

/** Around the alert threshold in use, to show what moving it would cost. */
val DEFAULT_THRESHOLDS = listOf(0.5, 0.6, 0.7, 0.8)

fun evaluate(
    labels: List<Label>,
    verdicts: Map<String, Verdict>,
    thresholds: List<Double> = DEFAULT_THRESHOLDS,
): EvaluationReport {
    val pairs = labels.mapNotNull { label -> verdicts[label.key]?.let { label to it } }
    val count = { test: (Pair<Label, Verdict>) -> Boolean -> pairs.count(test) }
    val truePositive = count { (label, verdict) -> label.relevant && verdict.relevant }
    val trueNegative = count { (label, verdict) -> !label.relevant && !verdict.relevant }
    val bothRelevant = pairs.filter { (label, verdict) -> label.relevant && verdict.relevant }

    return EvaluationReport(
        scored = pairs.size,
        missing = labels.size - pairs.size,
        relevance =
            Relevance(
                accuracy = ratio(truePositive + trueNegative, pairs.size) ?: 0.0,
                truePositive = truePositive,
                falsePositive = count { (label, verdict) -> !label.relevant && verdict.relevant },
                falseNegative = count { (label, verdict) -> label.relevant && !verdict.relevant },
                trueNegative = trueNegative,
            ),
        direction =
            DirectionAgreement(
                bothRelevant.count { (label, verdict) ->
                    label.direction == verdict.direction
                },
                bothRelevant.size,
            ),
        alerts =
            thresholds.map { threshold ->
                val fires = { pair: Pair<Label, Verdict> -> pair.second.relevant && pair.second.impact >= threshold }
                val fired = count(fires)
                val wanted = count { it.first.alert }
                val hits = count { fires(it) && it.first.alert }
                AlertScore(threshold, fired, wanted, hits, ratio(hits, fired), ratio(hits, wanted))
            },
    )
}

fun formatReport(
    report: EvaluationReport,
    title: String,
): String {
    val r = report.relevance
    val missing = if (report.missing > 0) " (판정 없음 ${report.missing}건)" else ""
    return (
        listOf(
            "── $title ── 채점 ${report.scored}건$missing",
            "관련 여부 정확도 ${pct(r.accuracy)}   맞게 관련 ${r.truePositive} · 노이즈를 관련으로 ${r.falsePositive} · " +
                "놓침 ${r.falseNegative} · 맞게 무관 ${r.trueNegative}",
            "방향 일치 ${report.direction.agreed}/${report.direction.compared} (둘 다 관련이라 한 것 중)",
            "알림 기준   울림  원함  적중  정밀도  재현율",
        ) +
            report.alerts.map {
                "  ≥ ${it.threshold.toFixed(1)}   ${"${it.fired}".padStart(COUNT_WIDTH)}  " +
                    "${"${it.wanted}".padStart(
                        COUNT_WIDTH,
                    )}  ${"${it.hits}".padStart(COUNT_WIDTH)}   ${pct(it.precision)}   ${pct(it.recall)}"
            }
    ).joinToString("\n")
}

/** Columns of the alert table, as the original padded them. */
private const val COUNT_WIDTH = 4

/** See [COUNT_WIDTH]. */
private const val PERCENT_WIDTH = 3

/** A ratio times this is a percent. */
private const val PERCENT = 100

private fun pct(value: Double?): String =
    if (value == null) "  -  " else "${(value * PERCENT).toFixed(0).padStart(PERCENT_WIDTH)}%"

/** Reads the labelled file. Unlabelled rows are skipped; malformed ones are named. */
fun parseLabels(csv: String): List<Label> {
    val rows = parseCsv(csv)
    val header = rows.firstOrNull()
    val at = listOf("key", "relevant", "direction", "alert").associateWith { header?.indexOf(it) ?: -1 }
    require(at.values.none { it < 0 }) {
        "label file needs columns key, relevant, direction, alert; found ${header?.joinToString(", ")}"
    }

    val problems = mutableListOf<String>()
    val labels = rows.drop(1).mapIndexedNotNull { index, row -> readLabel(row, at, index + 2, problems) }
    require(problems.isEmpty()) { problems.joinToString("\n") }
    return labels
}

private fun readLabel(
    row: List<String>,
    at: Map<String, Int>,
    line: Int,
    problems: MutableList<String>,
): Label? {
    val cell = { name: String ->
        row
            .getOrNull(at.getValue(name))
            ?.jsTrim()
            ?.lowercase()
            .orEmpty()
    }
    val relevant = cell("relevant")
    val direction = cell("direction")
    val alert = cell("alert")
    if (relevant.isEmpty() && direction.isEmpty() && alert.isEmpty()) return null

    if (relevant !in YES_NO) problems += "line $line: relevant must be y or n"
    if (Direction.fromWire(direction) == null) problems += "line $line: direction must be up, down or neutral"
    if (alert !in YES_NO) problems += "line $line: alert must be y or n"

    return Label(
        key = row.getOrNull(at.getValue("key")).orEmpty(),
        relevant = relevant == "y",
        direction = Direction.fromWire(direction) ?: Direction.NEUTRAL,
        alert = alert == "y",
    )
}

/** What a label cell may say for a yes-or-no column. */
private val YES_NO = setOf("y", "n")

/**
 * RFC 4180, the subset spreadsheets write: quoted fields, doubled quotes,
 * commas and newlines inside quotes. A leading byte-order mark is dropped —
 * it is written so Excel reads the file as UTF-8, and some editors keep it.
 *
 * Read as field-then-delimiter tokens: a field is either quoted or runs to
 * the next comma or line end, and the delimiter says whether the row ends.
 */
fun parseCsv(text: String): List<List<String>> {
    val source = text.removePrefix(BOM)
    val matcher = CSV_TOKEN.matcher(source)
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    var index = 0

    while (index < source.length) {
        matcher.region(index, source.length)
        require(matcher.lookingAt()) { "malformed CSV at offset $index" }
        val raw = matcher.group(1).orEmpty()
        row += if (raw.startsWith("\"")) raw.substring(1, raw.length - 1).replace("\"\"", "\"") else raw
        if (matcher.group(2) != ",") {
            rows += row.toList()
            row.clear()
        }
        index = matcher.end()
    }
    if (row.isNotEmpty()) rows += row.toList()
    return rows.filter { cells -> cells.any { it.isNotEmpty() } }
}

/**
 * A field and the delimiter after it. `\z` rather than `$`: Java's `$` also
 * matches before a final line break, JavaScript's only at the very end.
 */
private val CSV_TOKEN = Regex("(\"(?:[^\"]|\"\")*\"|[^\",\\r\\n]*)(,|\\r\\n|\\n|\\r|\\z)").toPattern()

/** Quoted only where a cell needs it; a byte-order mark first, so Excel reads the file as UTF-8. */
fun toCsv(rows: List<List<String>>): String {
    val quote = { cell: String ->
        if (NEEDS_QUOTES.containsMatchIn(cell)) "\"${cell.replace("\"", "\"\"")}\"" else cell
    }
    return "$BOM${rows.joinToString("\n") { row -> row.joinToString(",") { quote(it) } }}\n"
}

/** Excel reads a CSV as UTF-8 only when it starts with one. */
private const val BOM = "\uFEFF"

/** A quote, comma or line break inside a cell. */
private val NEEDS_QUOTES = Regex("[\",\\r\\n]")

fun labelKey(
    source: String,
    id: String,
    code: String,
): String = "$source|$id|$code"

private fun ratio(
    part: Int,
    whole: Int,
): Double? = if (whole == 0) null else part.toDouble() / whole
