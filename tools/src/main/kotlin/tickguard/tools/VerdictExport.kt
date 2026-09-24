package tickguard.tools

import okhttp3.OkHttpClient
import tickguard.text.jsTrim
import tickguard.time.kstDate
import tickguard.time.kstTime
import tickguard.verdict.LABEL_COLUMNS
import tickguard.verdict.deepseek.Translator
import tickguard.verdict.labelKey
import tickguard.verdict.parseCsv
import tickguard.verdict.toCsv
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.system.exitProcess

/**
 * Writes stories for a person to label, without the model's answers, so the
 * labels are not anchored on them.
 *
 * Every story the model called relevant goes in, and filler fills the rest.
 * A random fifty would be almost all filler and could not score the cases
 * that page anyone. The filler is picked by a hash of its id: arbitrary, but
 * the same on every run, so the set can be reproduced.
 *
 *   ./gradlew :tools:verdictExport                # eval/verdict-labels.csv, 50 stories
 *   ./gradlew :tools:verdictExport --args="80"
 *
 * With an LLM key set, each headline gets a plain Korean translation in its
 * own column — translation only, never the verdict's summary, which carries
 * the model's judgement.
 */
suspend fun main(args: Array<String>) {
    val size = args.getOrNull(0)?.toInt() ?: DEFAULT_SIZE
    val output = Path.of(OUTPUT)

    if (Files.exists(output) && holdsLabels(Files.readString(output))) {
        System.err.println("✖ $OUTPUT already holds labels; move it aside first.")
        exitProcess(1)
    }

    val env = environment()
    val judged = readJudged(env)

    val relevant = judged.filter { it.relevant }
    val chosen = choose(judged, size)

    val apiKey = env["TICKGUARD_LLM_API_KEY"].orEmpty()
    val korean =
        if (apiKey.isEmpty()) {
            chosen.map {
                null
            }
        } else {
            Translator(apiKey, OkHttpClient()).translate(chosen.map { it.title })
        }
    val untranslated = korean.count { it == null }

    Files.createDirectories(output.toAbsolutePath().parent)
    Files.writeString(
        output,
        toCsv(
            listOf(LABEL_COLUMNS) +
                chosen.mapIndexed { index, row ->
                    listOf(
                        row.key,
                        row.code,
                        row.publisher,
                        "${kstDate(row.publishedAt)} ${kstTime(row.publishedAt)}",
                        row.title,
                        korean[index].orEmpty(),
                        "",
                        "",
                        "",
                    )
                },
        ),
    )

    println("$OUTPUT: ${chosen.size} stories (${minOf(relevant.size, size)} the model called relevant, shuffled in)")
    if (untranslated > 0) println("  ($untranslated headlines left without a Korean translation)")
    println("Fill three columns per row, then run  ./gradlew :tools:verdictScore")
    println("  relevant   y / n              could this move the stock at all")
    println("  direction  up / down / neutral")
    println("  alert      y / n              would you want your phone to buzz for it")
}

/** Every story with a verdict. */
private fun readJudged(env: Map<String, String>): List<ExportRow> =
    openReadOnly(env).use { db ->
        db.rows(
            """SELECT n.source, n.id, n.code, n.publisher, n.title, n.published_at, v.relevant
               FROM verdicts v JOIN news n USING (source, id, code) WHERE v.status = 'judged'""",
        ) {
            ExportRow(
                key = labelKey(it.getString("source"), it.getString("id"), it.getString("code")),
                code = it.getString("code"),
                publisher = it.getString("publisher"),
                title = it.getString("title"),
                publishedAt = Instant.ofEpochMilli(it.getLong("published_at")),
                relevant = it.getInt("relevant") == 1,
            )
        }
    }

/** Where a person fills in labels. Gitignored: it names the symbols held. */
const val OUTPUT = "eval/verdict-labels.csv"

/** Enough stories to score a threshold, few enough to label in one sitting. */
private const val DEFAULT_SIZE = 50

internal data class ExportRow(
    val key: String,
    val code: String,
    val publisher: String,
    val title: String,
    val publishedAt: Instant,
    val relevant: Boolean,
)

/**
 * Every relevant story first, filler after in hash order, cut to [size], then
 * shuffled by hash so the relevant ones do not sit together at the top.
 */
internal fun choose(
    judged: List<ExportRow>,
    size: Int,
): List<ExportRow> {
    val byHash = compareBy<ExportRow> { sha1(it.key) }
    val (relevant, filler) = judged.partition { it.relevant }
    return (relevant + filler.sortedWith(byHash)).take(size).sortedWith(byHash)
}

/** An existing file is replaced only while it holds no labels at all. */
internal fun holdsLabels(csv: String): Boolean {
    val rows = parseCsv(csv)
    val header = rows.firstOrNull().orEmpty()
    val labelled = listOf("relevant", "direction", "alert").map { header.indexOf(it) }.filter { it >= 0 }
    return rows.drop(1).any { row ->
        labelled.any {
            row
                .getOrNull(it)
                .orEmpty()
                .jsTrim()
                .isNotEmpty()
        }
    }
}

private fun sha1(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()))
