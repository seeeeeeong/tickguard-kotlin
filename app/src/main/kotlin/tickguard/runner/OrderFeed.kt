package tickguard.runner

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.gateway.ServerFrame
import tickguard.orders.OrderDecode
import tickguard.orders.decodeOrderEvent

/**
 * The account's order events, read and never acted on.
 *
 * Runs on the engine, which drains the order lane ahead of quotes. One that
 * cannot be read is logged in full at ERROR: the stream will not send it
 * again, and the resync after a reconnect is the only way back to it.
 */
internal class OrderFeed(
    private val counters: Counters,
) {
    fun handle(frame: ServerFrame.Message) {
        when (val decoded = decodeOrderEvent(frame)) {
            is OrderDecode.Failed -> {
                counters.orderUnreadable.incrementAndGet()
                log.error("order event unreadable on {}: {} — {}", decoded.topic, decoded.reason, frame.data)
            }

            is OrderDecode.Ok -> {
                counters.orderEvents.incrementAndGet()
                with(decoded.event.order) {
                    log.info(
                        "order {}: {} {} {} {}/{} ({})",
                        decoded.event.event,
                        symbol,
                        side,
                        orderType,
                        execution.filledQuantity.toPlainString(),
                        quantity.toPlainString(),
                        status,
                    )
                }
            }
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OrderFeed::class.java)
    }
}
