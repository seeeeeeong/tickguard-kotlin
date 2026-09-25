package tickguard.runner

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.rest.RestClient
import tickguard.rest.fetchUsdKrw
import tickguard.rules.Signal
import tickguard.store.Store
import tickguard.stream.Decimal
import tickguard.time.SEOUL
import tickguard.trading.LossAction
import tickguard.trading.SleeveProposal
import tickguard.trading.TestSleeves
import tickguard.trading.attribute
import tickguard.trading.isRebalanceDay
import tickguard.trading.position
import tickguard.trading.proposeRebalance
import java.time.InstantSource
import java.time.LocalDate

/**
 * The test sleeves, from the service's side: what each holds, from the order
 * ledger, and on a rebalance morning what each should trade, sent to Discord
 * for the day's session. The proposals are kept for the execution module,
 * which places them in LIVE mode; until then a person does.
 */
internal class SleeveDesk(
    private val store: Store,
    private val rest: RestClient,
    private val clock: InstantSource,
    private val report: (Signal) -> Unit,
) {
    /** The last proposals made, for the execution module and the status page. */
    @Volatile var proposals: List<SleeveProposal> = emptyList()
        private set

    /** On a rebalance day, or when [force]d: works out and sends every sleeve's proposal. */
    suspend fun propose(force: Boolean = false) {
        val today = LocalDate.now(clock.withZone(SEOUL))
        if (!force && !isRebalanceDay(today)) return
        val orders = store.ordersSince(TestSleeves.START)
        val owned = attribute(orders, TestSleeves.ALL, store.orderTags(), TestSleeves.PERSONAL, TestSleeves.START)
        val made =
            TestSleeves.ALL.mapNotNull { sleeve ->
                val bars =
                    sleeve.universe.associateWith {
                        store.bars(
                            it,
                            today.minusYears(HISTORY_YEARS),
                            today.minusDays(1),
                        )
                    }
                proposeRebalance(sleeve, position(sleeve, owned[sleeve.id].orEmpty()), bars)
            }
        proposals = made
        val fx = fetchUsdKrw(rest)
        report(Signal(RULE_ID, "-", "$today 리밸런싱 제안", made.joinToString("\n\n") { text(it, fx) }, clock.instant()))
        log.info("rebalance: proposed {} trades across {} sleeves", made.sumOf { it.trades.size }, made.size)
    }

    private fun text(
        proposal: SleeveProposal,
        fx: Decimal,
    ): String {
        val lines = ArrayList<String>()
        val change = (proposal.value / proposal.sleeve.capital - Decimal.ONE) * Decimal.HUNDRED
        lines += "${proposal.sleeve.name} · \$${proposal.value.format(2)} (${change.format(1)}%) · ${proposal.asOf} 종가"
        when (proposal.stopped) {
            LossAction.STOP_BUYING -> lines += "⚠ 손실 한도: 매도만"
            LossAction.STOP_SLEEVE -> lines += "⛔ 손실 한도: 영구 중지 — TICKGUARD_SLEEVE_${proposal.sleeve.id}=OFF 로 끄세요"
            null -> Unit
        }
        if (proposal.trades.isEmpty()) lines += "거래 없음"
        for (trade in proposal.trades) {
            val won = (trade.value * fx).format(0)
            lines +=
                "${trade.side} ${trade.code} \$${trade.value.format(2)} (${won}원) · ${trade.quantity.toPlainString()}주"
        }
        return lines.joinToString("\n")
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SleeveDesk::class.java)

        /** The signal's rule id: proposals are not a rule, and never cool down. */
        const val RULE_ID = "rebalance"

        /** Enough daily history for the longest signal, a 200-day average, with room. */
        const val HISTORY_YEARS = 2L
    }
}
