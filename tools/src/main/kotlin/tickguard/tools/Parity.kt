package tickguard.tools

import tickguard.backtest.parityCases
import tickguard.backtest.parityLines
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Replays a real database with the Kotlin engine and compares every fire,
 * byte for byte, with the original's golden for the same database.
 *
 *   TICKGUARD_TS=../tickguard node tools/parity/replay-golden.ts db ../tickguard/tickguard.db > eval/parity/golden.tsv
 *   TICKGUARD_DB=../tickguard/tickguard.db ./gradlew :tools:parity
 *   TICKGUARD_DB=../tickguard/tickguard.db ./gradlew :tools:parity --args="--mutate"
 *
 * `--mutate` lengthens every drawdown hold by a millisecond and must report a
 * difference; if it does not, the comparison is not looking at anything.
 * Reads the database read-only. The golden names held symbols and stays in
 * eval/, which is gitignored.
 */
fun main(args: Array<String>) {
    val golden = Files.readString(Path.of(args.firstOrNull { !it.startsWith("--") } ?: "eval/parity/golden.tsv"))
    val skew = if ("--mutate" in args) 1.milliseconds else Duration.ZERO

    val ticks =
        openReadOnly(environment()).use { db ->
            db
                .rows(
                    "SELECT DISTINCT code FROM ticks ORDER BY code",
                ) { it.getString("code") }
                .flatMap { ticksOf(db, it) }
        }
    val kotlin = parityLines(ticks, parityCases(skew))

    val expected = golden.lines()
    val actual = kotlin.lines()
    val firstDiff =
        expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) }
            ?: actual.size.takeIf { it != expected.size }
    val fires = expected.count { it.isNotEmpty() }
    println(
        "${ticks.size} ticks · ${ticks.map {
            it.code
        }.distinct().size} symbols · original $fires fires · kotlin ${actual.count {
            it
                .isNotEmpty()
        }} fires",
    )
    if (firstDiff == null) {
        println("✔ identical, byte for byte")
        exitProcess(0)
    }
    println("✖ first difference at line ${firstDiff + 1}")
    println("  original: ${expected.getOrNull(firstDiff)}")
    println("  kotlin:   ${actual.getOrNull(firstDiff)}")
    exitProcess(1)
}
