package tickguard.rules

import tickguard.stream.Decimal
import tickguard.stream.Trade
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps a bounded recent history per symbol so rules can ask about a span of
 * time rather than a single print.
 *
 * Two bounds, both necessary. Time alone is not enough: a burst can put
 * thousands of prints inside five minutes, and a process that watches a
 * hundred symbols would hold all of them. Count alone is not enough either: a
 * quiet symbol would answer a five minute question with an hour of history.
 *
 * When the count bound bites, the oldest points go — which means a snapshot
 * may cover less time than asked for. `span` reports what it actually covered
 * so a rule can decline rather than quietly answer a different question.
 */
data class WindowPoint(
    /** Exchange time, epoch milliseconds. */
    val at: Long,
    val price: Decimal,
    val volume: Decimal,
)

data class WindowSnapshot(
    /** Price at the start of the covered span. */
    val open: Decimal,
    val last: Decimal,
    val high: Decimal,
    val low: Decimal,
    val volume: Decimal,
    val count: Int,
    /** How much time the points actually cover, which may be less than asked. */
    val span: Duration,
)

data class WindowStats(
    val symbols: Int,
    val points: Int,
    val evicted: Int,
)

class WindowStore(
    /** Longest span any rule will ask for. Older points are dropped. */
    private val retention: Duration = DEFAULT_RETENTION,
    /** Per symbol. Bounds memory when a burst arrives. */
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
) {
    private val bySymbol = LinkedHashMap<String, ArrayDeque<WindowPoint>>()
    private var evicted = 0

    fun record(trade: Trade) {
        val at = trade.at.toEpochMilli()
        val points = bySymbol.getOrPut(trade.code) { ArrayDeque() }
        points.addLast(WindowPoint(at, trade.price, trade.volume))
        trim(points, at)
    }

    /** Null when nothing is retained for the symbol. */
    fun snapshot(
        code: String,
        window: Duration,
    ): WindowSnapshot? {
        val points = bySymbol[code]
        val newest = points?.lastOrNull() ?: return null

        val cutoff = newest.at - window.inWholeMilliseconds
        var start = points.size - 1
        while (start > 0 && points[start - 1].at >= cutoff) start -= 1

        return summarise(points, start, newest)
    }

    fun stats() = WindowStats(symbols = bySymbol.size, points = bySymbol.values.sumOf { it.size }, evicted = evicted)

    private fun trim(
        points: ArrayDeque<WindowPoint>,
        now: Long,
    ) {
        val cutoff = now - retention.inWholeMilliseconds
        var stale = 0
        while (stale < points.size && points[stale].at < cutoff) stale += 1

        val overflow = maxOf(0, points.size - stale - maxPoints)
        repeat(stale + overflow) { points.removeFirst() }
        evicted += stale + overflow
    }

    private fun summarise(
        points: ArrayDeque<WindowPoint>,
        start: Int,
        newest: WindowPoint,
    ): WindowSnapshot {
        val oldest = points[start]
        var high = oldest.price
        var low = oldest.price
        var volume = oldest.volume

        for (i in start + 1 until points.size) {
            val point = points[i]
            if (point.price > high) high = point.price
            if (point.price < low) low = point.price
            volume += point.volume
        }

        return WindowSnapshot(
            open = oldest.price,
            last = newest.price,
            high = high,
            low = low,
            volume = volume,
            count = points.size - start,
            span = (newest.at - oldest.at).milliseconds,
        )
    }

    private companion object {
        /** Longer than any span a rule asks about; the rapid-move rule asks about five minutes. */
        val DEFAULT_RETENTION = 30.minutes

        /** Enough for a busy symbol's five minutes, small enough across a hundred symbols. */
        const val DEFAULT_MAX_POINTS = 2_000
    }
}
