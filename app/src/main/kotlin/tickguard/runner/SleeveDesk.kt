package tickguard.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.execution.ExecutionLimits
import tickguard.execution.ExecutionResult
import tickguard.execution.Executor
import tickguard.execution.OrderPlan
import tickguard.execution.OrderRequest
import tickguard.execution.inOrderWindow
import tickguard.execution.planOrders
import tickguard.rest.RestClient
import tickguard.rest.fetchUsdKrw
import tickguard.rules.Signal
import tickguard.sla.Calendar
import tickguard.sla.Market
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
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate

/**
 * The test sleeves, from the service's side: what each holds, from the order
 * ledger, and on a rebalance morning what each should trade, sent to Discord
 * for the day's session. The proposals are kept for the execution module,
 * which places them in LIVE mode; until then a person does.
 */
@Suppress("LongParameterList") // The desk's collaborators, each a separate concern it coordinates.
internal class SleeveDesk(
    private val store: Store,
    private val rest: RestClient,
    private val clock: InstantSource,
    private val report: (Signal) -> Unit,
    private val trading: TradingConfig,
    private val executor: Executor,
    private val calendar: Calendar,
    /** The engine: proposals and orders run where the rest of the domain state does. */
    private val scope: CoroutineScope,
) {
    /** The last proposals made, for the execution module and the status page. */
    @Volatile var proposals: List<SleeveProposal> = emptyList()
        private set

    /** When [proposals] were made; they are placed once, in the next order window. */
    @Volatile private var proposedAt: Instant? = null

    @Volatile private var executedFor: Instant? = null

    /**
     * Proposes now, whatever the day, and places the result if the kill switch
     * and the order window allow: how the test's opening orders are placed, and
     * how a person reruns a month by hand. A second request the same day
     * proposes from the updated ledger, and the day's client order ids make a
     * repeated order return the first instead of placing another.
     */
    fun requestNow() {
        scope.launch {
            propose(force = true)
            execute()
        }
    }

    /** What the status page shows: off, the modes, or why automated orders halted. */
    fun describe(): String {
        val modes = trading.modes.entries.joinToString(" ") { "${it.key}:${it.value}" }
        return when {
            executor.halted != null -> "HALTED — ${executor.halted}"
            !trading.enabled -> "off · $modes"
            else -> "on · $modes"
        }
    }

    fun halted(): Boolean = executor.halted != null

    /**
     * Places the latest proposals once, in the first order window after they
     * were made, for sleeves in LIVE; lists DRY_RUN sleeves' orders instead.
     * Checked every minute; does nothing unless the kill switch is on.
     */
    suspend fun execute() {
        val made = proposedAt ?: return
        val now = clock.instant()
        val due = trading.enabled && executedFor != made && Duration.between(made, now) <= STALE_AFTER
        if (!due || !inOrderWindow(calendar.hours(Market.US), now)) return
        executedFor = made
        val plan = planOrders(proposals, trading.modes, LIMITS, LocalDate.ofInstant(made, SEOUL))
        val result = executor.run(plan)
        report(Signal(EXECUTION_ID, "-", "리밸런싱 실행", summary(plan, result), now))
        log.info(
            "execution: {} placed, {} refused, {} skipped, halted={}",
            result.placed.size,
            result.refused.size,
            plan.skipped.size,
            result.haltedAt != null,
        )
    }

    private fun summary(
        plan: OrderPlan,
        result: ExecutionResult,
    ): String {
        val lines = ArrayList<String>()
        result.placed.forEach { (order, id) ->
            lines +=
                "✔ ${order.sleeve} ${order.side} ${order.symbol} ${size(order)} · ${id.take(ID_SHOWN)}…"
        }
        result.refused.forEach { (order, why) ->
            lines +=
                "✖ ${order.sleeve} ${order.side} ${order.symbol} ${size(order)} · ${why.code}"
        }
        result.haltedAt?.let { (order, why) -> lines += "⛔ 중지: ${order.symbol} 결과 불명 ($why) — 이후 주문 없음, 확인 후 재시작 필요" }
        plan.dryRun.forEach { order ->
            lines += "· DRY_RUN ${order.sleeve} ${order.side} ${order.symbol} ${size(order)}"
        }
        plan.skipped.filter { it.reason != "sleeve is off" }.forEach {
            lines +=
                "– ${it.sleeve} ${it.symbol}: ${it.reason}"
        }
        return lines.ifEmpty { listOf("보낼 주문 없음") }.joinToString("\n")
    }

    private fun size(order: OrderRequest) =
        order.amount?.let { "\$${it.toPlainString()}" } ?: "${order.quantity?.toPlainString()}주"

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
        proposedAt = clock.instant()
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

        /** The signal's rule id for an execution report. */
        const val EXECUTION_ID = "execution"

        /** Enough of an order id to recognise it. */
        const val ID_SHOWN = 6

        /** A proposal older than this is not placed: the next session's prices are another month's question. */
        val STALE_AFTER: Duration = Duration.ofHours(30)

        /**
         * The test's hard limits, in code so configuration cannot raise them:
         * all buys in one run within the test's 300,000 won at the starting rate,
         * and no more orders than three sleeves' full rebalance could need.
         */
        val LIMITS = ExecutionLimits(maxBuys = Decimal.of(TEST_WON) / TestSleeves.FX, maxOrdersPerRun = 20)

        /** The whole test's capital in won. */
        const val TEST_WON = 300_000L
    }
}
