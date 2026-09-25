package tickguard.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.gateway.ServerFrame
import tickguard.network.reasonOf
import tickguard.orders.OrderDecode
import tickguard.orders.OrderRecorder
import tickguard.orders.OrderResync
import tickguard.orders.OrderSource
import tickguard.orders.OrderStore
import tickguard.orders.decodeOrderEvent
import tickguard.orders.orderNotice
import tickguard.rest.RestClient
import tickguard.rules.Signal
import java.time.Instant
import java.time.InstantSource

/**
 * The account's order events, recorded and reported, never acted on.
 *
 * The stream and the resync after each (re)connect both feed one recorder, so
 * the ledger hears every change once and in order. A change that moves an
 * order to a state worth knowing — a fill, a cancellation, a rejection — goes
 * out on the same path as every other alert.
 */
internal class OrderFeed(
    private val counters: Counters,
    store: OrderStore,
    rest: RestClient,
    clock: InstantSource,
    report: (Signal) -> Unit,
) {
    val recorder =
        OrderRecorder(
            store = store,
            clock = clock,
            onNewState = { order, source ->
                orderNotice(order, source)?.let {
                    report(Signal(RULE_ID, order.symbol, it.title, it.detail, clock.instant()))
                }
            },
            onError = {
                error,
                order,
                ->
                log.error("order {} not recorded: {} — {}", order.orderId, reasonOf(error), order)
            },
        )

    private val resync = OrderResync(rest, store, recorder, clock)

    fun start(scope: CoroutineScope) = recorder.start(scope)

    /** On the engine. One that cannot be read is logged whole: the stream will not send it again. */
    fun handle(frame: ServerFrame.Message) {
        when (val decoded = decodeOrderEvent(frame)) {
            is OrderDecode.Failed -> {
                counters.orderUnreadable.incrementAndGet()
                log.error("order event unreadable on {}: {} — {}", decoded.topic, decoded.reason, frame.data)
            }

            is OrderDecode.Ok -> {
                counters.orderEvents.incrementAndGet()
                val order = decoded.event.order
                log.info(
                    "order {}: {} {} {}/{} ({})",
                    decoded.event.event,
                    order.symbol,
                    order.side,
                    order.execution.filledQuantity.toPlainString(),
                    order.quantity.toPlainString(),
                    order.status,
                )
                recorder.offer(order, decoded.event.event, OrderSource.STREAM)
            }
        }
    }

    /**
     * After every successful connection. [gapStart] is when the previous one
     * closed, or when the process started: changes to orders placed since then
     * are announced, older ones only recorded.
     */
    @Suppress("TooGenericExceptionCaught") // A failed resync is retried by the next connection.
    suspend fun resync(gapStart: Instant) {
        try {
            val result = resync.run(gapStart)
            counters.orderResyncs.incrementAndGet()
            if (result.unreadable.isNotEmpty()) {
                counters.orderUnreadable.addAndGet(result.unreadable.size.toLong())
                log.error("orders resync: {} unreadable: {}", result.unreadable.size, result.unreadable)
            }
            log.info("orders resync: {} orders checked", result.orders)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            counters.orderResyncFailures.incrementAndGet()
            log.error("orders resync failed: {}", reasonOf(failure))
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OrderFeed::class.java)

        /** The signal's rule id: order alerts are not a rule, and never cool down. */
        const val RULE_ID = "order"
    }
}
