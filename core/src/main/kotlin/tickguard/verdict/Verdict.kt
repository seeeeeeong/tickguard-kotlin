package tickguard.verdict

enum class Direction(
    val wire: String,
) {
    UP("up"),
    DOWN("down"),
    NEUTRAL("neutral"),
    ;

    companion object {
        fun fromWire(wire: String): Direction? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a model is asked about a headline, and what it must answer. */
data class Verdict(
    /** False for filler: fund-position filings, price predictions, listicles. */
    val relevant: Boolean,
    val direction: Direction,
    /** 0 is no effect on the price, 1 a major event. */
    val impact: Double,
    /** One short Korean sentence: what happened. */
    val summary: String,
)
