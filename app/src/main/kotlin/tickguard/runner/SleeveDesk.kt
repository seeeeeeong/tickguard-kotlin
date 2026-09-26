package tickguard.runner

import kotlinx.coroutines.CancellationException
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
import tickguard.execution.firstSession
import tickguard.execution.inOrderWindow
import tickguard.execution.lastSession
import tickguard.execution.orderWindow
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
import tickguard.trading.Bar
import tickguard.trading.DipRules
import tickguard.trading.LossAction
import tickguard.trading.Sleeve
import tickguard.trading.SleeveMode
import tickguard.trading.SleeveProposal
import tickguard.trading.TestSleeves
import tickguard.trading.attribute
import tickguard.trading.isRebalanceDay
import tickguard.trading.lots
import tickguard.trading.position
import tickguard.trading.proposeDip
import tickguard.trading.proposeRebalance
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.LocalTime
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
    /** Fetches the latest daily bars; returns the symbols with rows it could not read. */
    private val refresh: suspend () -> Set<String> = { emptySet() },
    /** The account's shares by symbol, fresh: what the ledger is checked against before a dip sleeve trades. */
    private val account: suspend () -> Map<String, Decimal> = { emptyMap() },
) {
    /** The last proposals made, for the execution module and the status page. */
    @Volatile var proposals: List<SleeveProposal> = emptyList()
        private set

    /** When [proposals] were made; they are placed once, in the next order window. */
    @Volatile var proposedAt: Instant? = null
        private set

    /** The last run, for the control page. */
    @Volatile var lastRun: LastRun? = null
        private set

    /** The rate the last proposal was priced at in won, for the control page. */
    @Volatile var fx: Decimal = TestSleeves.FX
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

    /** Everything the control page shows about the desk, read at once. */
    fun state(): DeskState {
        val now = clock.instant()
        return DeskState(
            tradingOn = arming.enabled(trading.enabled),
            stopped = arming.stopped,
            halted = executor.halted,
            modes = arming.modes(trading.modes, now),
            liveUntil = arming.liveUntil?.takeIf { arming.isLive(now) },
            window = orderWindow(calendar.hours(Market.US)),
            proposedAt = proposedAt,
            proposals = proposals,
            lastRun = lastRun,
            fx = fx,
        )
    }

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
        catchUp()
        val made = proposedAt ?: return
        val now = clock.instant()
        val due = arming.enabled(trading.enabled) && executedFor != made && Duration.between(made, now) <= STALE_AFTER
        if (!due || !inOrderWindow(calendar.hours(Market.US), now)) return
        executedFor = made
        val plan = planOrders(proposals, arming.modes(trading.modes, now), LIMITS, LocalDate.ofInstant(made, SEOUL))
        val result = executor.run(plan)
        lastRun = LastRun(now, plan, result)
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

    /**
     * A dip sleeve with an order still open waits a day: its fill has not
     * reached the ledger, so its cash would count money already committed.
     */
    private fun settled(
        sleeve: Sleeve,
        orders: List<Order>,
    ): Boolean =
        orders.none { !it.closed }.also {
            if (!it) log.warn("rebalance: {} skipped, an order of its is still open", sleeve.id)
        }

    /**
     * Why a dip sleeve's ledger is missing an order, or null: an order tagged
     * to it that the ledger never recorded was accepted, but its fill was
     * lost (a crash before the stream delivered it, and a resync that only
     * looks at today), so the sleeve would spend its money again.
     */
    private fun unrecorded(
        orders: List<Order>,
        tags: Map<String, String>,
        sleeves: List<Sleeve>,
    ): String? {
        val dips = sleeves.filter { it.dip != null }.map { it.id }.toSet()
        val recorded = orders.map { it.orderId }.toSet()
        val missing = tags.filter { (id, sleeve) -> sleeve in dips && id !in recorded }.keys
        return missing.takeIf { it.isNotEmpty() }?.let { ids ->
            "장부에 없는 주문: ${ids.joinToString { it.take(ID_SHOWN) }} — 토스 앱에서 확인 필요"
        }
    }

    /**
     * The last US session that has closed. The calendar names it until
     * midnight in Seoul; after that it lists only the session still running
     * and the next, and the last closed session is the last stored close
     * before the running one's date.
     */
    private suspend fun closedSession(): LocalDate? {
        val hours = calendar.load(Market.US)
        return lastSession(hours, clock.instant())
            ?: firstSession(hours)?.let { running ->
                store
                    .bars(TestSleeves.PARKING, running.minusDays(LOOKBACK_DAYS), running.minusDays(1))
                    .maxOfOrNull { it.day }
            }
    }

    /**
     * Throws unless [proposal] is priced on exactly the last US session that
     * has closed, as the calendar has it: bars a session behind would trade on
     * an old signal, so the proposal fails and is tried again in
     * [RETRY_AFTER], by when the bars may have caught up.
     */
    private fun current(
        proposal: SleeveProposal,
        session: LocalDate?,
    ) {
        check(session != null && proposal.asOf == session) {
            "${proposal.sleeve.id}: bars end at ${proposal.asOf}, the last US session was $session"
        }
    }

    /**
     * Throws unless every symbol the dip sleeve may buy has the history its
     * signal needs: a symbol without it can never signal, and a sleeve that
     * silently cannot enter is not the strategy that was backtested.
     */
    private fun complete(
        sleeve: Sleeve,
        rules: DipRules,
        bars: Map<String, List<Bar>>,
    ) {
        val short = sleeve.universe.filter { it != rules.parking && (bars[it]?.size ?: 0) <= rules.trendDays }
        check(short.isEmpty()) { "${sleeve.id}: under ${rules.trendDays + 1} daily bars for ${short.joinToString()}" }
    }

    /**
     * Why the account contradicts the ledger, or null if it does not: more
     * of a dip sleeve's parking symbol than every sleeve's fills explain
     * means an order was placed whose sleeve was never recorded, and the
     * sleeve would spend that money again. Only the parking symbol is
     * checked: the user holds none of it, while any of the dip symbols may
     * also be the user's own. It is also where most of the money sits.
     */
    private suspend fun untracked(owned: Map<String, List<Order>>): String? {
        val parking =
            TestSleeves.ALL
                .filter { (trading.modes[it.id] ?: SleeveMode.OFF) != SleeveMode.OFF }
                .mapNotNull { it.dip?.parking }
                .distinct()
        if (parking.isEmpty()) return null
        val held = account()
        val ledger =
            TestSleeves.ALL.map { position(it, owned[it.id].orEmpty()).holdings }
        val extra =
            parking.filter { code ->
                val recorded = ledger.fold(Decimal.ZERO) { sum, holdings -> sum + (holdings[code] ?: Decimal.ZERO) }
                (held[code] ?: Decimal.ZERO) - recorded > SHARE_TOLERANCE
            }
        return extra.takeIf { it.isNotEmpty() }?.let { "계좌 보유가 장부보다 많음: ${it.joinToString()} — 기록 안 된 주문 확인 필요" }
    }

    private fun clockTime(at: Instant?) = at?.let { CLOCK.format(it) }.orEmpty()

    private fun size(order: OrderRequest) =
        order.amount?.let { "\$${it.toPlainString()}" } ?: "${order.quantity?.toPlainString()}주"

    /**
     * The proposal a restart lost: once the day's proposal time has passed, a
     * weekday without one proposes now, at most every [RETRY_AFTER] while it
     * keeps failing (a bar fetch that fails must not be retried every minute).
     */
    @Suppress("TooGenericExceptionCaught") // A failed catch-up must not stop tonight's run of an earlier proposal.
    private suspend fun catchUp() {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, SEOUL)
        val madeToday = proposedAt?.let { LocalDate.ofInstant(it, SEOUL) } == today
        val early = LocalTime.ofInstant(now, SEOUL) < PROPOSE_AT
        if (madeToday || early || due(today, force = false).isEmpty()) return
        try {
            propose()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("rebalance: catch-up failed, retrying in {}: {}", RETRY_AFTER, failure.message)
        }
    }

    /** The sleeves that propose on [today]: a dip sleeve every weekday, the rest on a rebalance day; never one off. */
    private fun due(
        today: LocalDate,
        force: Boolean,
    ) = TestSleeves.ALL.filter { sleeve ->
        val on = (trading.modes[sleeve.id] ?: SleeveMode.OFF) != SleeveMode.OFF
        val day = if (sleeve.dip != null) today.dayOfWeek !in WEEKEND else isRebalanceDay(today)
        on && (force || day)
    }

    /**
     * On a sleeve's day, or when [force]d: works out and sends the proposal of
     * every sleeve that is on. A failure is thrown, and the scheduled callers
     * wait [RETRY_AFTER] before the next try rather than refetching every
     * symbol each minute; a person's request always tries.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure starts the wait, and is rethrown.
    suspend fun propose(force: Boolean = false) {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, SEOUL)
        val sleeves = due(today, force)
        val waiting = failedAt?.let { Duration.between(it, now) < RETRY_AFTER } == true
        if (sleeves.isEmpty() || (waiting && !force)) return
        try {
            proposeNow(today, sleeves, force)
            failedAt = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failedAt = now
            throw failure
        }
    }

    @Volatile private var failedAt: Instant? = null

    @Suppress("LongMethod") // The proposal's steps, each a line or two.
    private suspend fun proposeNow(
        today: LocalDate,
        sleeves: List<Sleeve>,
        force: Boolean,
    ) {
        val unreadable = refresh()
        val broken = sleeves.flatMap { it.universe }.distinct().filter { it in unreadable }
        check(broken.isEmpty()) { "bars unreadable for ${broken.joinToString()}" }
        // Up to the last session that has closed: after midnight in Seoul the store also holds
        // the open session's partial bar, which no proposal may price on.
        val session = closedSession()
        val through = session ?: today.minusDays(1)
        val orders = store.ordersSince(TestSleeves.START)
        val tags = store.orderTags()
        val owned = attribute(orders, TestSleeves.ALL, tags, TestSleeves.PERSONAL, TestSleeves.START)
        val made =
            sleeves.mapNotNull { sleeve ->
                val bars =
                    sleeve.universe.associateWith {
                        store.bars(
                            it,
                            today.minusYears(HISTORY_YEARS),
                            through,
                        )
                    }
                val mine = owned[sleeve.id].orEmpty()
                val position = position(sleeve, mine)
                val rules = sleeve.dip
                if (rules == null) {
                    proposeRebalance(sleeve, position, bars)
                } else {
                    complete(sleeve, rules, bars)
                    // No proposal means the parking symbol has no current close: fail and retry rather than
                    // lose the day's exits.
                    val proposal =
                        checkNotNull(proposeDip(sleeve, position, lots(mine), bars, rules)) {
                            "${sleeve.id}: no current bar for ${rules.parking}"
                        }
                    current(proposal, session)
                    proposal.takeIf { settled(sleeve, mine) }
                }
            }
        unrecorded(orders, tags, sleeves)?.let { reason ->
            executor.halt(reason)
            report(Signal(EXECUTION_ID, "-", "자동 주문 중지", reason, clock.instant()))
            log.error("execution halted: {}", reason)
        }
        untracked(owned)?.let { reason ->
            executor.halt(reason)
            report(Signal(EXECUTION_ID, "-", "자동 주문 중지", reason, clock.instant()))
            log.error("execution halted: {}", reason)
        }
        val waiting =
            if (force) {
                emptyList()
            } else {
                waiting(
                    proposals,
                    proposedAt,
                    executedFor,
                    clock.instant(),
                    sleeves.map { it.id },
                )
            }
        proposals = waiting + made
        proposedAt = clock.instant()
        val fx = fetchUsdKrw(rest)
        this.fx = fx
        // Everything the evening will place, a carried-over month included.
        val all = proposals
        report(Signal(RULE_ID, "-", "$today 리밸런싱 제안", all.joinToString("\n\n") { text(it, fx) }, clock.instant()))
        log.info("rebalance: proposed {} trades across {} sleeves", all.sumOf { it.trades.size }, all.size)
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

        /** When a day's proposals are made, Seoul time: after the US close, before the next session. */
        val PROPOSE_AT: LocalTime = LocalTime.of(9, 0)

        /** Shares the account and the ledger may differ by: fills are quoted to six places. */
        val SHARE_TOLERANCE: Decimal = Decimal.parse("0.0001", "tolerance")

        /** Far enough back to cross a long weekend when looking for the last close before a session. */
        const val LOOKBACK_DAYS = 10L

        /** How long a failed proposal waits before the next try. */
        val RETRY_AFTER: Duration = Duration.ofMinutes(15)

        /** Days the dip sleeve does not propose: no US session follows. */
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** The signal's rule id for an execution report. */
        const val EXECUTION_ID = "execution"

        /** Enough of an order id to recognise it. */
        const val ID_SHOWN = 6

        /** A proposal older than this is not placed: the next session's prices are another month's question. */
        val STALE_AFTER: Duration = PROPOSAL_LIFETIME

        /**
         * The hard limits, in code so configuration cannot raise them: all buys
         * in one run within the dip sleeve's capital, which the user set on
         * 2026-09-25 in place of the three-sleeve test's 300,000 won, and no
         * more orders than a full rebalance could need.
         */
        val LIMITS = ExecutionLimits(maxBuys = TestSleeves.DIP_CAPITAL, maxOrdersPerRun = 20)
    }
}

/** A proposal older than this is not placed: the next session's prices are another month's question. */
internal val PROPOSAL_LIFETIME: Duration = Duration.ofHours(30)

/**
 * The earlier proposals still waiting for a session, for the sleeves a new
 * proposal does not cover: a month's rebalance whose first weekday was a US
 * holiday must survive the next day's dip proposal. None once placed, or
 * once too old to place.
 */
internal fun waiting(
    proposals: List<SleeveProposal>,
    madeAt: Instant?,
    executedFor: Instant?,
    now: Instant,
    covered: List<String>,
): List<SleeveProposal> {
    if (madeAt == null || executedFor == madeAt || Duration.between(madeAt, now) > PROPOSAL_LIFETIME) return emptyList()
    return proposals.filter { it.sleeve.id !in covered }
}

/** A run of the plan, placed or listed, for the control page. */
internal data class LastRun(
    val at: Instant,
    val plan: OrderPlan,
    val result: ExecutionResult,
)

/** The desk as the control page sees it. */
internal data class DeskState(
    /** Configuration's kill switch on and not stopped from the page. */
    val tradingOn: Boolean,
    val stopped: Boolean,
    val halted: String?,
    /** Each sleeve's mode in effect, the page's LIVE included. */
    val modes: Map<String, SleeveMode>,
    val liveUntil: Instant?,
    val window: ClosedRange<Instant>?,
    val proposedAt: Instant?,
    val proposals: List<SleeveProposal>,
    val lastRun: LastRun?,
    val fx: Decimal,
)
