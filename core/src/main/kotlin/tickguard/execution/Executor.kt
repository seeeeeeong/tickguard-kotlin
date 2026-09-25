package tickguard.execution

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
        if (halted != null) return ExecutionResult(placed, refused, null)
        for (request in plan.live) {
            when (val outcome = placer.place(request)) {
                is PlaceOutcome.Placed -> {
                    orders.tagOrder(outcome.orderId, request.sleeve)
                    placed += request to outcome.orderId
                }

                is PlaceOutcome.Refused -> {
                    refused += request to outcome
                }

                is PlaceOutcome.Unknown -> {
                    halted = "${request.clientOrderId}: ${outcome.reason}"
                    return ExecutionResult(placed, refused, request to outcome.reason)
                }
            }
        }
        return ExecutionResult(placed, refused, null)
    }
}
