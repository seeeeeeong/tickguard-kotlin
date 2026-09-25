package tickguard.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.execution.Arming
import tickguard.execution.ExecutionLimits
import tickguard.execution.ExecutionResult
import tickguard.execution.Executor
import tickguard.execution.OrderPlan
import tickguard.execution.OrderRequest
import tickguard.execution.inOrderWindow
import tickguard.execution.orderWindowEnd
import tickguard.execution.planOrders
import tickguard.orders.Order
import tickguard.rest.RestClient
import tickguard.rest.fetchUsdKrw
import tickguard.rules.Signal
import tickguard.sla.Calendar
import tickguard.sla.Market
import tickguard.store.Store
import tickguard.stream.Decimal
import tickguard.time.SEOUL
import tickguard.trading.LossAction
import tickguard.trading.SleeveMode
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
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

/**
 * The test sleeves, from the service's side: what each holds, from the order
 * ledger, and on a rebalance morning what each should trade, sent to Discord
 * for the day's session. The proposals are kept for the execution module,
 * which places them in LIVE mode; until then a person does.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Proposals, execution and the page's switches share one state.
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
    @Volatile var proposedAt: Instant? = null
        private set

    /** The last run's time and its report, for the control page. */
    @Volatile var lastRun: Pair<Instant, String>? = null
        private set

    @Volatile private var arming = Arming()

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

    /**
     * The control page's "LIVE tonight": runs the DRY_RUN sleeves live until
     * this order window closes, then proposes and places at once. Refused, with
     * the reason, outside a window, with trading off or stopped, after a halt,
     * or with no sleeve in DRY_RUN: each a state in which pressing it would
     * do something other than what the button says.
     */
    fun goLive(): String? {
        val now = clock.instant()
        val until = orderWindowEnd(calendar.hours(Market.US), now)
        val refusal =
            when {
                !arming.enabled(trading.enabled) -> "trading is off"
                executor.halted != null -> "halted"
                until == null -> "outside the order window"
                SleeveMode.DRY_RUN !in trading.modes.values -> "no sleeve in DRY_RUN"
                else -> null
            }
        if (refusal == null) {
            arming = arming.copy(liveUntil = until)
            log.warn("control: DRY_RUN sleeves live until {}", until)
            requestNow()
        }
        return refusal
    }

    /** The control page's stop: no order of any kind until the process restarts. */
    fun stop() {
        arming = Arming(stopped = true)
        log.warn("control: trading stopped until restart")
    }

    /** The test's orders and their sleeves, for the control page, read on the engine like the rest. */
    fun ledger(): CompletableFuture<Pair<List<Order>, Map<String, String>>> =
        scope.future { store.ordersSince(TestSleeves.START) to store.orderTags() }

    /** When the order window now open closes, or null while it is shut. */
    fun windowEnd(): Instant? = orderWindowEnd(calendar.hours(Market.US), clock.instant())

    /** What the status page shows: off, the modes in effect, or why automated orders halted. */
    fun describe(): String {
        val now = clock.instant()
        val modes = arming.modes(trading.modes, now).entries.joinToString(" ") { "${it.key}:${it.value}" }
        return when {
            executor.halted != null -> "HALTED — ${executor.halted}"
            arming.stopped -> "STOPPED until restart · $modes"
            !trading.enabled -> "off · $modes"
            arming.isLive(now) -> "on · $modes · LIVE until ${clockTime(arming.liveUntil)}"
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
        val due = arming.enabled(trading.enabled) && executedFor != made && Duration.between(made, now) <= STALE_AFTER
        if (!due || !inOrderWindow(calendar.hours(Market.US), now)) return
        executedFor = made
        val plan = planOrders(proposals, arming.modes(trading.modes, now), LIMITS, LocalDate.ofInstant(made, SEOUL))
        val result = executor.run(plan)
        val text = summary(plan, result)
        lastRun = now to text
        report(Signal(EXECUTION_ID, "-", "리밸런싱 실행", text, now))
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

    private fun clockTime(at: Instant?) = at?.let { CLOCK.format(it) }.orEmpty()

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

        /** A window's close, as a person in Seoul reads it. */
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm 'KST'").withZone(SEOUL)

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
