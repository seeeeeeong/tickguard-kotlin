package tickguard.tools

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import tickguard.verdict.Direction
import tickguard.verdict.JudgeInput
import tickguard.verdict.Label
import tickguard.verdict.Verdict
import tickguard.verdict.deepseek.DeepSeekJudge
import tickguard.verdict.evaluate
import tickguard.verdict.formatReport
import tickguard.verdict.labelKey
import tickguard.verdict.parseCsv
import tickguard.verdict.parseLabels
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Scores verdicts against eval/verdict-labels.csv.
 *
 *   ./gradlew :tools:verdictScore                                  # the verdicts already stored
 *   ./gradlew :tools:verdictScore --args="--model deepseek-v4-pro" # judge the labelled set again with another model
 *   ./gradlew :tools:verdictScore --args="--labels other.csv"      # a different label file
 *
 * Re-judging calls the API once per labelled story, outside the daily budget
 * of the running process, and writes nothing back. It reads each story from
 * the label file itself, not the database, so a labelled set stays a
 * benchmark after its stories are pruned.
 */
suspend fun main(args: Array<String>) {
    val flag = { name: String -> args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } }
    val input = flag("--labels") ?: OUTPUT
    val rejudgeWith = flag("--model")

    val labelFile = Files.readString(Path.of(input))
    val labels = parseLabels(labelFile)
    if (labels.isEmpty()) {
        System.err.println("✖ No labelled rows in $input yet.")
        exitProcess(1)
    }

    val env = environment()
    val (stored, storedModel) = storedVerdicts(env, labels)
    println(formatReport(evaluate(labels, stored), "저장된 판정 ($storedModel)"))

    if (rejudgeWith != null) {
        val apiKey = env["TICKGUARD_LLM_API_KEY"].orEmpty()
        check(apiKey.isNotEmpty()) { "TICKGUARD_LLM_API_KEY is not set" }
        val (fresh, failed) = rejudge(DeepSeekJudge(apiKey, OkHttpClient(), model = rejudgeWith), labels, labelFile)
        println()
        println(formatReport(evaluate(labels, fresh), "다시 판정 ($rejudgeWith)${if (failed > 0) " · 실패 $failed" else ""}"))
    }
}

/** The stored verdict for each labelled story that has one, and the model that made them. */
private fun storedVerdicts(
    env: Map<String, String>,
    labels: List<Label>,
): Pair<Map<String, Verdict>, String> {
    val rows =
        openReadOnly(env).use { db ->
            db.rows(
                """SELECT n.source, n.id, n.code, v.relevant, v.direction, v.impact, v.summary, v.model
                   FROM news n LEFT JOIN verdicts v USING (source, id, code)""",
            ) {
                StoredRow(
                    key = labelKey(it.getString("source"), it.getString("id"), it.getString("code")),
                    relevant = (it.nullable("relevant") as? Number)?.toInt(),
                    direction = it.getString("direction")?.let(Direction::fromWire),
                    impact = (it.nullable("impact") as? Number)?.toDouble(),
                    summary = it.getString("summary"),
                    model = it.getString("model"),
                )
            }
        }
    val byKey = rows.associateBy { it.key }
    val stored =
        labels
            .mapNotNull { label ->
                val row = byKey[label.key] ?: return@mapNotNull null
                if (row.relevant == null || row.direction == null || row.impact == null) return@mapNotNull null
                label.key to Verdict(row.relevant == 1, row.direction, row.impact, row.summary.orEmpty())
            }.toMap()
    return stored to (rows.firstNotNullOfOrNull { it.model } ?: "stored")
}

private data class StoredRow(
    val key: String,
    val relevant: Int?,
    val direction: Direction?,
    val impact: Double?,
    val summary: String?,
    val model: String?,
)

/** Each labelled story judged again, read from the label file; and how many calls failed. */
@Suppress("TooGenericExceptionCaught") // Any failure is counted and named, then the next story is tried.
private suspend fun rejudge(
    judge: DeepSeekJudge,
    labels: List<Label>,
    labelFile: String,
): Pair<Map<String, Verdict>, Int> {
    val rows = parseCsv(labelFile)
    val header = rows.firstOrNull().orEmpty()
    val cell = { row: List<String>, name: String -> row.getOrNull(header.indexOf(name)).orEmpty() }
    val stories =
        rows.drop(1).associate { row ->
            val key = cell(row, "key")
            key to JudgeInput(cell(row, "code"), cell(row, "title"), cell(row, "publisher"), key.substringBefore('|'))
        }

    val fresh = mutableMapOf<String, Verdict>()
    var failed = 0
    for (label in labels) {
        val story = stories[label.key] ?: continue
        try {
            fresh[label.key] = judge.judge(story)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed += 1
            // Counted and named: v4-pro's first run failed six calls and said nothing about why.
            System.err.println("  ${label.key}: ${error.message}")
        }
    }
    return fresh to failed
}
