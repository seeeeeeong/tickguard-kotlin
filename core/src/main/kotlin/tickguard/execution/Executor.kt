package tickguard.execution

import kotlinx.coroutines.CancellationException
import tickguard.orders.OrderStore
import tickguard.stream.Decimal
import tickguard.trading.Side

/** What the broker said to one order. */
sealed interface PlaceOutcome {
    /** Accepted: an order exists. */
    data class Placed(
        val orderId: String,
    ) : PlaceOutcome

    /** Refused with a reason: no order was created. */
    data class Refused(
        val status: Int,
        val code: String,
        val message: String,
    ) : PlaceOutcome

    /** No answer that says whether an order exists: a timeout, a dropped connection, a 5xx, a key still in progress. */
    data class Unknown(
        val reason: String,
    ) : PlaceOutcome
}

/** The one way an order leaves this process. Implemented in adapters against POST /api/v1/orders only. */
fun interface OrderPlacer {
    suspend fun place(request: OrderRequest): PlaceOutcome
}

data class ExecutionResult(
    val placed: List<Pair<OrderRequest, String>>,
    val refused: List<Pair<OrderRequest, PlaceOutcome.Refused>>,
    /** Set when the run stopped at an order whose outcome is unknown; nothing after it was sent. */
    val haltedAt: Pair<OrderRequest, String>?,
)

/**
 * Sends a plan's live orders, in order, and stops at the first one whose
 * outcome is unknown. An unknown outcome may be a filled order; sending the
 * rest on top of it is how one fault becomes several. Once halted it sends
 * nothing more until a person restarts it: the halt is the process's state,
 * not a retry to be waited out.
 *
 * A refused order created nothing, so the run goes on; an accepted one is
 * tagged with its sleeve at once, since the ledger cannot tell from the order
 * which sleeve placed it.
 */
class Executor(
    private val placer: OrderPlacer,
    private val orders: OrderStore,
    private val journal: PlacementJournal = NoJournal,
    /**
     * Waits for these orders to fill and returns their proceeds, net of fees;
     * null if one did not fill in time. A sale accepted is not yet money: a
     * sleeve's buys wait for its sales' fills, and spend what they brought.
     */
    private val settled: suspend (List<String>) -> Decimal? = { null },
) {
    /** Starts halted when the journal holds an order a previous run never accounted for. */
    @Volatile var halted: String? =
        journal
            .pending()
            .takeIf {
                it.isNotEmpty()
            }?.let { "unaccounted orders from a previous run: ${it.joinToString()}" }
        private set

    /**
     * Sends [plan]'s live orders. [stillLive] is asked again right before each
     * one: a person who switches orders off or stops them mid-run stops the
     * rest of the run, not just the next one.
     */
    suspend fun run(
        plan: OrderPlan,
        stillLive: (OrderRequest) -> Boolean = { true },
    ): ExecutionResult {
        val placed = ArrayList<Pair<OrderRequest, String>>()
        val refused = ArrayList<Pair<OrderRequest, PlaceOutcome.Refused>>()
        var stop: Pair<OrderRequest, String>? = null
        val funding = Funding(plan.cash)
        // Once switched off, the run is over: switching back on must not send the rest of an old plan.
        var off = false
        for (request in plan.live) {
            if (halted != null) break
            val held = if (off) SWITCHED_OFF else hold(request, funding, stillLive)
            off = off || held === SWITCHED_OFF
            if (held != null) {
                refused += request to held
            } else {
                sendRecorded(request, placed, refused, funding)?.let { (why, reason) ->
                    halted = "${request.clientOrderId}: $why"
                    stop = request to reason
                }
            }
        }
        return ExecutionResult(placed, refused, stop)
    }

    /** Sends [request], and records a sale's outcome for the buys it funds. Returns why the run must halt, or null. */
    private suspend fun sendRecorded(
        request: OrderRequest,
        placed: MutableList<Pair<OrderRequest, String>>,
        refused: MutableList<Pair<OrderRequest, PlaceOutcome.Refused>>,
        funding: Funding,
    ): Pair<String, String>? {
        val before = refused.size
        val halt = send(request, placed, refused)
        if (request.side == Side.SELL) funding.sold(request, placed, refused = refused.size > before)
        return halt
    }

    /**
     * Why [request] must not go out now, or null. A buy may wait on its
     * sales' fills, so whether it is still live is asked again after the wait.
     */
    private suspend fun hold(
        request: OrderRequest,
        funding: Funding,
        stillLive: (OrderRequest) -> Boolean,
    ): PlaceOutcome.Refused? {
        if (!stillLive(request)) return SWITCHED_OFF
        val funded = request.side != Side.BUY || funding.ready(request)
        return when {
            !stillLive(request) -> SWITCHED_OFF
            !funded -> UNFUNDED
            else -> null
        }
    }

    /**
     * Which sleeves' buys may go out. A sleeve's buys are sized on its sales'
     * proceeds: a sale refused, or accepted but not filled, would have them
     * spend money the sleeve does not have, which may be the account's other
     * cash. Its sales are waited for once, before its first buy.
     */
    private inner class Funding(
        private val cash: Map<String, Decimal>,
    ) {
        private val sales = LinkedHashMap<String, MutableList<String>>()
        private val unfunded = LinkedHashSet<String>()

        /** What a sleeve that sold may still spend: its cash and its sales' proceeds, less its buys so far. */
        private val budget = LinkedHashMap<String, Decimal>()

        /** Records [sale]'s outcome: its order, if the last placed was it, or an unfunded sleeve. */
        fun sold(
            sale: OrderRequest,
            placed: List<Pair<OrderRequest, String>>,
            refused: Boolean,
        ) {
            val orderId = placed.lastOrNull()?.takeIf { it.first == sale }?.second
            when {
                refused || orderId == null -> unfunded += sale.sleeve
                else -> sales.getOrPut(sale.sleeve) { ArrayList() } += orderId
            }
        }

        /**
         * Whether [request] may go out: never after its sleeve's sale failed,
         * and, for a sleeve that sold, only within what its cash and the sales
         * actually brought, whatever the proposal expected at the close.
         */
        suspend fun ready(request: OrderRequest): Boolean {
            val sleeve = request.sleeve
            val pending = sales[sleeve]
            if (pending != null && sleeve !in budget && sleeve !in unfunded) {
                val proceeds = settled(pending)
                if (proceeds == null) unfunded += sleeve else budget[sleeve] = (cash[sleeve] ?: Decimal.ZERO) + proceeds
            }
            val left = budget[sleeve]
            val amount = request.amount ?: Decimal.ZERO
            val fits = left == null || amount <= left
            if (fits && left != null) budget[sleeve] = left - amount
            return sleeve !in unfunded && fits
        }
    }

    /**
     * Sends one order, journalled around the request. Returns why the run must
     * halt, the long reason and the short one, or null when it may go on.
     */
    private suspend fun send(
        request: OrderRequest,
        placed: MutableList<Pair<OrderRequest, String>>,
        refused: MutableList<Pair<OrderRequest, PlaceOutcome.Refused>>,
    ): Pair<String, String>? {
        attempt { journal.begin(request) }?.let {
            return "not sent, the journal could not be written ($it)" to
                "journal not written"
        }
        return when (val outcome = placer.place(request)) {
            is PlaceOutcome.Placed -> {
                placed += request to outcome.orderId
                // An order no sleeve owns would have its money spent again: its journal entry stays,
                // and nothing more goes out, now or after a restart, until a person looks.
                val why = attempt { orders.tagOrder(outcome.orderId, request.sleeve) }
                if (why == null) {
                    cleared(request)
                } else {
                    "placed as ${outcome.orderId}, sleeve not recorded ($why)" to "sleeve not recorded"
                }
            }

            is PlaceOutcome.Refused -> {
                refused += request to outcome
                cleared(request)
            }

            is PlaceOutcome.Unknown -> {
                outcome.reason to outcome.reason
            }
        }
    }

    /** Stops every order until restart, for a reason found outside a run, such as a ledger the account contradicts. */
    fun halt(reason: String) {
        halted = reason
    }

    /**
     * Crosses [request] out of the journal. An entry that cannot be removed
     * would halt the next start for an order that is accounted for, so it
     * halts now, where the reason is still known.
     */
    private suspend fun cleared(request: OrderRequest): Pair<String, String>? =
        attempt { journal.done(request) }?.let {
            "accounted for, but its journal entry remains ($it)" to
                "journal not cleared"
        }

    /** Runs [step]; returns why it failed, or null. */
    @Suppress("TooGenericExceptionCaught") // Whatever failed, the step did not happen.
    private suspend fun attempt(step: suspend () -> Unit): String? =
        try {
            step()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.simpleName
        }
}

/** Why a buy was not sent: its sleeve's sale was refused or did not fill, so the money it was sized on never came. */
private val UNFUNDED = PlaceOutcome.Refused(0, "not-sent", "a sale of its sleeve was refused or did not fill")

/** Why an order was not sent: its sleeve was switched off or stopped while the run was under way. */
private val SWITCHED_OFF = PlaceOutcome.Refused(0, "not-sent", "switched off during the run")
