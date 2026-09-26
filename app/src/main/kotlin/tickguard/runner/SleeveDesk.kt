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
import tickguard.time.NEW_YORK
import tickguard.time.SEOUL
import tickguard.trading.Bar
import tickguard.trading.DipRules
import tickguard.trading.LossAction
import tickguard.trading.RefreshedBars
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
    /** Fetches these symbols' daily bars from a date into the store; what it read is in the result. */
    private val refresh: suspend (
        Collection<String>,
        LocalDate,
    ) -> RefreshedBars = { _, _ -> RefreshedBars(0, emptyList()) },
    /** Where the page's daily switch is kept, so a restart keeps it. */
    private val daily: DailySwitch = NoDailySwitch,
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

    // Only dip sleeves are ever switched daily: anything else in the file is ignored, never made live.
    @Volatile private var arming = Arming(daily = daily.load().filter { it in DIP_IDS }.toSet())

    /** Serialises the switch's file and [arming], so two presses cannot leave them disagreeing. */
    private val switching = Any()

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
        val made = proposedAt
        val refusal =
            when {
                !arming.enabled(trading.enabled) -> "trading is off"
                executor.halted != null -> "halted"
                until == null -> "outside the order window"
                SleeveMode.DRY_RUN !in trading.modes.values -> "no sleeve in DRY_RUN"
                made != null && liveFor == made -> "already placed"
                else -> null
            }
        if (refusal == null) {
            synchronized(switching) { arming = arming.copy(liveUntil = until) }
            log.warn("control: DRY_RUN sleeves live until {}", until)
            // The morning's proposal, checked against the session that closed before it, is what
            // was listed and what goes out; proposing again after midnight would price on a guess.
            if (made != null && Duration.between(made, now) <= STALE_AFTER) {
                executedFor = null
                scope.launch { execute() }
            } else {
                requestNow()
            }
        }
        return refusal
    }

    /**
     * Reports a halt no run has reported: one the executor started with,
     * from an order a previous process never accounted for. It blocks exits
     * too, so it must not wait for someone to open the status page.
     */
    private fun announce() {
        val halted = executor.halted
        if (halted != null && halted != announced) {
            announced = halted
            report(Signal(EXECUTION_ID, "-", "자동 주문 중지", halted, clock.instant()))
            log.error("execution halted: {}", halted)
        }
    }

    @Volatile private var announced: String? = null

    /** The proposal whose orders went out live: it is never placed a second time. */
    @Volatile private var liveFor: Instant? = null

    /** The control page's stop: no order of any kind until the process restarts. */
    @Suppress("TooGenericExceptionCaught") // Whatever the file did, orders are already stopped.
    fun stop() {
        synchronized(switching) {
            // Orders stop first, in memory, whatever happens to the file after.
            val wasDaily = arming.daily.isNotEmpty()
            arming = Arming(stopped = true)
            try {
                // Stop means stop: a daily switch left on would start trading again at the next restart.
                daily.save(emptySet())
                if (wasDaily) announceSwitch("매일 자동 주문 꺼짐", "긴급 중지로 매일 자동 주문도 꺼졌습니다.")
                log.warn("control: trading stopped until restart, daily orders switched off")
            } catch (failure: Exception) {
                val message = "긴급 중지는 됐지만 매일 자동 주문 파일을 지우지 못함: ${failure.message} — 재시작 전에 ${daily.location} 를 지우세요"
                announceSwitch("자동 주문 중지", message)
                log.error("control: trading stopped, but the daily switch file remains: {}", failure.message)
            }
        }
    }

    /**
     * The page's "every day": runs the dip sleeves configured `DRY_RUN` live
     * every weekday until switched off, kept across restarts. Refused with
     * trading off or stopped, after a halt, or with no dip sleeve in DRY_RUN.
     * Announced in Discord, as its switching off is.
     */
    fun startDaily(): String? {
        val sleeves =
            TestSleeves.ALL
                .filter { it.dip != null && trading.modes[it.id] == SleeveMode.DRY_RUN }
                .map { it.id }
                .toSet()
        // Checked, switched and announced in one step: a stop in between must not be overwritten, and
        // Discord hears of switches in the order they happened.
        val refusal =
            synchronized(switching) {
                val reason =
                    when {
                        !arming.enabled(trading.enabled) -> "trading is off"
                        executor.halted != null -> "halted"
                        sleeves.isEmpty() -> "no sleeve in DRY_RUN"
                        else -> saveDaily(sleeves)
                    }
                if (reason == null) {
                    val most = limitsFor(trading.modes).maxBuys.format(2)
                    announceSwitch(
                        "매일 자동 주문 켜짐",
                        "평일마다 ${sleeves.joinToString()} 가 09:00 에 판단하고 그날 밤 버튼 없이 주문합니다 (한 번에 최대 \$$most).",
                    )
                }
                reason
            }
        if (refusal == null) {
            log.warn("control: daily live orders on for {}", sleeves)
            replaceDryRun()
        }
        return refusal
    }

    /**
     * Places the current proposal live if it has only been listed so far:
     * switching daily orders on during tonight's window means tonight.
     */
    private fun replaceDryRun() {
        val made = proposedAt
        if (made != null && liveFor != made && Duration.between(made, clock.instant()) <= STALE_AFTER) {
            executedFor = null
            scope.launch { execute() }
        }
    }

    /** Saves the switch, then turns it on in memory; a switch that could not be saved stays off. */
    @Suppress("TooGenericExceptionCaught") // Whatever the file did, the switch stays off.
    private fun saveDaily(sleeves: Set<String>): String? =
        try {
            daily.save(sleeves)
            arming = arming.copy(daily = sleeves)
            null
        } catch (failure: Exception) {
            log.error("control: daily switch not saved: {}", failure.message)
            "could not save the switch"
        }

    /** Switches daily orders off: in memory first, so a file that will not go cannot keep them on. */
    @Suppress("TooGenericExceptionCaught") // Whatever the file did, daily orders are already off.
    fun stopDaily() {
        synchronized(switching) {
            // Tonight's LIVE too: "off" must stop the run under way, whichever switch made it live.
            arming = arming.copy(daily = emptySet(), liveUntil = null)
            val text =
                try {
                    daily.save(emptySet())
                    "이제 [구매하기]를 눌러야 주문이 나갑니다."
                } catch (failure: Exception) {
                    log.error("control: daily orders off, but the switch file remains: {}", failure.message)
                    "지금은 꺼졌지만 스위치 파일을 지우지 못함(${failure.message}) — 재시작 전에 ${daily.location} 를 지우세요."
                }
            announceSwitch("매일 자동 주문 꺼짐", text)
        }
        log.warn("control: daily live orders off")
    }

    /** Queues a switch's Discord message on the engine, which sends what it is given in order. */
    private fun announceSwitch(
        title: String,
        text: String,
    ) {
        val message = Signal(EXECUTION_ID, "-", title, text, clock.instant())
        scope.launch { report(message) }
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
            daily = arming.daily.takeIf { !arming.stopped }.orEmpty(),
            switchable = TestSleeves.ALL.any { it.dip != null && trading.modes[it.id] == SleeveMode.DRY_RUN },
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
            arming.daily.isNotEmpty() -> "on · $modes · daily"
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
        announce()
        catchUp()
        val made = proposedAt ?: return
        val now = clock.instant()
        val due = arming.enabled(trading.enabled) && executedFor != made && Duration.between(made, now) <= STALE_AFTER
        if (!due || !inOrderWindow(calendar.hours(Market.US), now)) return
        executedFor = made
        val plan =
            planOrders(
                proposals,
                arming.modes(trading.modes, now),
                limitsFor(trading.modes),
                LocalDate.ofInstant(made, SEOUL),
            )
        if (verified(plan)) {
            if (plan.live.isNotEmpty()) liveFor = made
            val result =
                executor.run(plan) { order ->
                    arming.enabled(trading.enabled) &&
                        arming.modes(trading.modes, clock.instant())[order.sleeve] == SleeveMode.LIVE
                }
            // The run's report says why it halted, if it did.
            announced = executor.halted
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
     * The symbols of [sleeve] with an order open that the sleeve did not
     * place, as the order stream recorded every order in the account: one
     * placed by hand would fill beside the sleeve's shares and be taken for
     * its own.
     */
    private suspend fun foreignOrders(sleeve: Sleeve): List<String> {
        val tags = store.orderTags()
        return store
            .openOrders()
            .filter { it.symbol in sleeve.universe && tags[it.orderId] != sleeve.id }
            .map { it.symbol }
            .distinct()
    }

    @Suppress("TooGenericExceptionCaught") // Any failure falls back to the last rate.
    private suspend fun rateOrLast(): Decimal =
        try {
            fetchUsdKrw(rest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("rebalance: exchange rate unavailable, using {}: {}", fx, failure.message)
            fx
        }

    /**
     * Why the ledger is missing an order, or null: an order tagged to any
     * sleeve, whichever is on now, that the ledger never recorded was
     * accepted but its fill was lost (a crash before the stream delivered
     * it, and a resync that only looks at today), so money would be spent
     * again by whichever sleeve runs next.
     */
    private fun unrecorded(
        orders: List<Order>,
        tags: Map<String, String>,
    ): String? {
        val recorded = orders.map { it.orderId }.toSet()
        val missing = tags.filter { (id, _) -> id !in recorded }.keys
        return missing.takeIf { it.isNotEmpty() }?.let { ids ->
            "장부에 없는 주문: ${ids.joinToString { it.take(ID_SHOWN) }} — 토스 앱에서 확인 필요"
        }
    }

    /**
     * The last US session that has closed, from the calendar asked by New
     * York's date: that day and the business day before, of which the latest
     * closed one is the answer at any hour in Seoul. None if the calendar
     * cannot be read, and then a proposal fails rather than guess.
     */
    private suspend fun closedSession(): LocalDate? {
        val now = clock.instant()
        return lastSession(calendar.on(Market.US, LocalDate.ofInstant(now, NEW_YORK)), now)
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
     * Whether [plan]'s live dip orders may go out: the account is checked
     * against the ledger again right before them, since hours pass between
     * the morning's proposal and the evening's orders. A mismatch halts; an
     * account that cannot be read skips tonight's run.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure to read the account skips the run.
    private suspend fun verified(plan: OrderPlan): Boolean {
        val dips =
            TestSleeves.ALL
                .filter { it.dip != null }
                .map { it.id }
                .toSet()
        if (plan.live.none { it.sleeve in dips }) return true
        val problem =
            try {
                val owned =
                    attribute(
                        store.ordersSince(TestSleeves.START),
                        TestSleeves.ALL,
                        store.orderTags(),
                        TestSleeves.PERSONAL,
                        TestSleeves.START,
                    )
                val open =
                    TestSleeves.ALL
                        .filter { sleeve -> plan.live.any { it.sleeve == sleeve.id } && sleeve.dip != null }
                        .flatMap { foreignOrders(it) }
                untracked(owned)?.also {
                    executor.halt(it)
                    announced = it
                } ?: open.takeIf { it.isNotEmpty() }?.let { "D 종목에 직접 넣은 미체결 주문이 있어 오늘 주문을 건너뜀: ${it.joinToString()}" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "계좌 수량을 확인하지 못해 오늘 주문을 건너뜀: ${failure.message}"
            }
        problem?.let {
            report(Signal(EXECUTION_ID, "-", "자동 주문 중지", it, clock.instant()))
            log.error("execution skipped: {}", it)
        }
        return problem == null
    }

    /**
     * Why the account contradicts the ledger, or null if it does not; see
     * [mismatched]. Checked for the dip sleeves that are on.
     */
    private suspend fun untracked(owned: Map<String, List<Order>>): String? {
        val dips =
            TestSleeves.ALL.filter {
                it.dip != null && (trading.modes[it.id] ?: SleeveMode.OFF) != SleeveMode.OFF
            }
        if (dips.isEmpty()) return null
        val ledger = LinkedHashMap<String, Decimal>()
        TestSleeves.ALL.forEach { sleeve ->
            position(sleeve, owned[sleeve.id].orEmpty()).holdings.forEach { (code, quantity) ->
                ledger[code] = (ledger[code] ?: Decimal.ZERO) + quantity
            }
        }
        // Every symbol the sleeve may trade, not only those it holds: shares of a candidate bought
        // outside it would be mistaken for its own once it buys the same symbol.
        val symbols = dips.flatMap { it.universe }.distinct()
        val off = mismatched(account(), ledger, symbols, TestSleeves.PERSONAL)
        return off.takeIf { it.isNotEmpty() }?.let {
            "계좌 수량이 장부와 다름: ${it.joinToString()} — 기록 안 된 주문, 주식 분할, 개인 매매 확인 필요"
        }
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
        // A restart in the small hours loses the morning's proposal while its session still trades.
        val lost = proposedAt == null && inOrderWindow(calendar.hours(Market.US), now)
        val early = LocalTime.ofInstant(now, SEOUL) < PROPOSE_AT && !lost
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
        val symbols = sleeves.flatMap { it.universe }.distinct()
        // A dip sleeve's signal averages 200 closes: all of them are fetched again, so a split the API
        // has adjusted for never leaves older bars in the store in the old units.
        val from =
            if (sleeves.any { it.dip != null }) {
                today.minusYears(
                    HISTORY_YEARS,
                )
            } else {
                today.minusDays(OVERLAP_DAYS)
            }
        val refreshed = refresh(symbols, from)
        val unreadable = refreshed.unreadable.map { it.substringBefore(" ") }.toSet()
        // A monthly sleeve needs every symbol; a dip sleeve only its parking and holdings, below.
        val broken = sleeves.filter { it.dip == null }.flatMap { it.universe }.filter { it in unreadable }
        check(broken.isEmpty()) { "bars unreadable for ${broken.distinct().joinToString()}" }
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
                val left = leftovers(sleeve, TestSleeves.ALL, owned)
                check(left.isEmpty()) { "${sleeve.id}: ${left.joinToString()} still hold shares or open orders" }
                if (rules == null) {
                    proposeRebalance(sleeve, position, bars)
                } else {
                    complete(sleeve, rules, bars)
                    // Only a close this fetch returned is known to be final: a row the store kept may be a
                    // bar stored while its session was still trading.
                    // A symbol with an unreadable row is left out too: its history may be short a day.
                    val stale =
                        sleeve.universe.filter {
                            it in unreadable || session == null || session !in refreshed.fetched[it].orEmpty()
                        }
                    val foreign = foreignOrders(sleeve)
                    check(
                        foreign.isEmpty(),
                    ) { "${sleeve.id}: orders of its symbols it did not place are open: ${foreign.joinToString()}" }
                    val needed = stale.filter { it == rules.parking || (position.holdings[it]?.signum() ?: 0) > 0 }
                    check(needed.isEmpty()) {
                        "${sleeve.id}: ${needed.joinToString()} not refreshed through the last US session, $session"
                    }
                    // No proposal means the parking symbol has no current close: fail and retry rather than
                    // lose the day's exits.
                    val proposal =
                        checkNotNull(proposeDip(sleeve, position, lots(mine), bars - stale.toSet(), rules)) {
                            "${sleeve.id}: no current bar for ${rules.parking}"
                        }
                    current(proposal, session)
                    proposal.takeIf { settled(sleeve, mine) }
                }
            }
        unrecorded(orders, tags)?.let { reason ->
            executor.halt(reason)
            announced = reason
            report(Signal(EXECUTION_ID, "-", "자동 주문 중지", reason, clock.instant()))
            log.error("execution halted: {}", reason)
        }
        untracked(owned)?.let { reason ->
            executor.halt(reason)
            announced = reason
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
        // The rate only prices the report in won: read it before the proposal counts as made, and
        // fall back to the last one, so a rate outage never leaves orders due that were never reported.
        val fx = rateOrLast()
        this.fx = fx
        proposals = waiting + made
        proposedAt = clock.instant()
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

        /** Days of bars a monthly proposal fetches again: enough to cover a long weekend's gap. */
        const val OVERLAP_DAYS = 10L

        /** The sleeves the daily switch may name: the dip sleeves. */
        val DIP_IDS: Set<String> =
            TestSleeves.ALL
                .filter { it.dip != null }
                .map { it.id }
                .toSet()

        /** When a day's proposals are made, Seoul time: after the US close, before the next session. */
        val PROPOSE_AT: LocalTime = LocalTime.of(9, 0)

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

/**
 * The symbols among [checked] whose shares in the account differ from what
 * every sleeve's fills explain: an order whose sleeve was never recorded, a
 * split the ledger's quantities predate, or a sale by hand. The user's own
 * symbols are left out, since the account holds those beside the sleeves.
 */
internal fun mismatched(
    account: Map<String, Decimal>,
    ledger: Map<String, Decimal>,
    checked: List<String>,
    personal: Set<String>,
): List<String> =
    checked.filter { code ->
        code !in personal && ((account[code] ?: Decimal.ZERO) - (ledger[code] ?: Decimal.ZERO)).abs() > SHARE_TOLERANCE
    }

/** Shares the account and the ledger may differ by: fills are quoted to six places. */
private val SHARE_TOLERANCE: Decimal = Decimal.parse("0.0001", "tolerance")

/**
 * The hard limits, in code so configuration cannot raise them: every buy in
 * one run within the dip sleeve's capital while it is on, which the user set
 * on 2026-09-25 in place of the three-sleeve test, and within that test's
 * 300,000 won otherwise; no more orders than a full rebalance could need.
 */
internal fun limitsFor(modes: Map<String, SleeveMode>): ExecutionLimits {
    val dip = TestSleeves.ALL.any { it.dip != null && (modes[it.id] ?: SleeveMode.OFF) != SleeveMode.OFF }
    val maxBuys = if (dip) TestSleeves.DIP_CAPITAL else Decimal.of(TEST_WON) / TestSleeves.FX
    return ExecutionLimits(maxBuys = maxBuys, maxOrdersPerRun = MAX_ORDERS)
}

/** The three-sleeve test's capital in won. */
private const val TEST_WON = 300_000L

/** More orders than any sleeve's full rebalance could need. */
private const val MAX_ORDERS = 20

/**
 * The sleeves of the other kind than [sleeve] that still hold shares, or
 * have an order open that could fill into some, by their ledgers. The dip
 * sleeve and the three-sleeve test take turns in one account, each starting
 * from its whole capital in cash, so whichever runs, the other's shares
 * must be sold or moved first, or the same dollars would be counted twice.
 */
internal fun leftovers(
    sleeve: Sleeve,
    sleeves: List<Sleeve>,
    owned: Map<String, List<Order>>,
): List<String> =
    sleeves
        .filter { other ->
            val orders = owned[other.id].orEmpty()
            (other.dip == null) != (sleeve.dip == null) &&
                (position(other, orders).holdings.isNotEmpty() || orders.any { !it.closed })
        }.map { it.id }

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
    /** The sleeves switched to trade live every day. */
    val daily: Set<String>,
    /** A dip sleeve is configured DRY_RUN, so the daily switch has something to switch. */
    val switchable: Boolean,
    val window: ClosedRange<Instant>?,
    val proposedAt: Instant?,
    val proposals: List<SleeveProposal>,
    val lastRun: LastRun?,
    val fx: Decimal,
)
