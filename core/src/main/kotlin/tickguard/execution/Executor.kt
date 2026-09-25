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
) {
    @Volatile var halted: String? = null
        private set

    suspend fun run(plan: OrderPlan): ExecutionResult {
        val placed = ArrayList<Pair<OrderRequest, String>>()
        val refused = ArrayList<Pair<OrderRequest, PlaceOutcome.Refused>>()
        var stop: Pair<OrderRequest, String>? = null
        for (request in plan.live) {
            if (halted != null) break
            when (val outcome = placer.place(request)) {
                is PlaceOutcome.Placed -> {
                    placed += request to outcome.orderId
                    // The order exists but no sleeve owns it: its sleeve would count the money as
                    // unspent and spend it again. Nothing more goes out until a person looks.
                    tag(outcome.orderId, request.sleeve)?.let { why ->
                        halted = "${request.clientOrderId}: placed as ${outcome.orderId}, sleeve not recorded ($why)"
                        stop = request to "sleeve not recorded"
                    }
                }

                is PlaceOutcome.Refused -> {
                    refused += request to outcome
                }

                is PlaceOutcome.Unknown -> {
                    halted = "${request.clientOrderId}: ${outcome.reason}"
                    stop = request to outcome.reason
                }
            }
        }
        return ExecutionResult(placed, refused, stop)
    }

    /** Stops every order until restart, for a reason found outside a run, such as a ledger the account contradicts. */
    fun halt(reason: String) {
        halted = reason
    }

    /** Records the order's sleeve; returns why it could not, or null once recorded. */
    @Suppress("TooGenericExceptionCaught") // Whatever the store threw, the tag is missing.
    private suspend fun tag(
        orderId: String,
        sleeve: String,
    ): String? =
        try {
            orders.tagOrder(orderId, sleeve)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.simpleName
        }
}
