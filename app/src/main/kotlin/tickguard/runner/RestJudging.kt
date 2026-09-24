package tickguard.runner

import tickguard.fallback.FallbackSymbol
import tickguard.rules.RuleEngine
import tickguard.rules.Signal
import tickguard.stream.Trade
import tickguard.subscribe.Topic

/**
 * Marks a signal raised from a REST price, so the alert says the stream was
 * down and what it was judged on instead.
 *
 * [RuleEngine.evaluate] does not suspend and the engine runs one thing at a
 * time, so a signal raised while [evaluate] runs came from that price and
 * nothing else.
 */
internal class RestJudging(
    private val report: (Signal) -> Unit,
) {
    private var active = false

    fun onSignal(signal: Signal) = report(if (active) signal.copy(detail = "${signal.detail}\n$NOTE") else signal)

    fun evaluate(
        trade: Trade,
        rules: RuleEngine,
    ) {
        active = true
        try {
            rules.evaluate(trade)
        } finally {
            active = false
        }
    }

    private companion object {
        /** The original's words, on the line under the detail. */
        const val NOTE = "(스트림 끊김 · REST 시세로 판단)"
    }
}

/** Every held symbol, then the configured extras: what REST is asked about while the stream is silent. */
internal fun fallbackSymbols(
    markets: Map<String, String>,
    extraSymbols: List<Topic>,
): List<FallbackSymbol> =
    markets.map { (code, type) -> FallbackSymbol(code, type, marketOf(type)) } +
        extraSymbols.map { FallbackSymbol(it.code, it.type, marketOf(it.type)) }
