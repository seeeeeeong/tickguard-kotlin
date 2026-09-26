package tickguard.execution

import kotlinx.coroutines.CancellationException
import tickguard.orders.OrderStore

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
) {
    /** Starts halted when the journal holds an order a previous run never accounted for. */
    @Volatile var halted: String? =
        journal
            .pending()
            .takeIf {
                it.isNotEmpty()
            }?.let { "unaccounted orders from a previous run: ${it.joinToString()}" }
        private set

    suspend fun run(plan: OrderPlan): ExecutionResult {
        val placed = ArrayList<Pair<OrderRequest, String>>()
        val refused = ArrayList<Pair<OrderRequest, PlaceOutcome.Refused>>()
        var stop: Pair<OrderRequest, String>? = null
        for (request in plan.live) {
            if (halted != null) break
            send(request, placed, refused)?.let { (why, reason) ->
                halted = "${request.clientOrderId}: $why"
                stop = request to reason
            }
        }
        return ExecutionResult(placed, refused, stop)
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
                    attempt { journal.done(request) }
                    null
                } else {
                    "placed as ${outcome.orderId}, sleeve not recorded ($why)" to "sleeve not recorded"
                }
            }

            is PlaceOutcome.Refused -> {
                refused += request to outcome
                attempt { journal.done(request) }
                null
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
