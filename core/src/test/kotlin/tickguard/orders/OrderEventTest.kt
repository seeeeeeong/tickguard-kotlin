package tickguard.orders

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.gateway.ServerFrame
import tickguard.json.StrictJson
import tickguard.testing.decimal
import java.time.Instant
import java.time.LocalDate

class OrderEventTest {
    private fun frame(data: String) = ServerFrame.Message("personal:order:3", StrictJson.parse(data))

    /** The AsyncAPI's own example: a US buy filled in full. */
    private val filled =
        """
        {"event":"FILL","accountSeq":"3","order":{
          "orderId":"bAGz","symbol":"AAPL","side":"BUY","orderType":"LIMIT","timeInForce":"DAY",
          "status":"FILLED","price":"100.5","quantity":"10","orderAmount":null,"currency":"USD",
          "orderedAt":"2026-06-23T09:30:00.000+09:00","canceledAt":null,
          "execution":{"filledQuantity":"10","averageFilledPrice":"100","filledAmount":"1000",
                       "commission":"1.23","tax":"0","settlementDate":"2026-06-25"}}}
        """.trimIndent()

    @Test
    fun `reads the documented fill event`() {
        val event = (decodeOrderEvent(frame(filled)) as OrderDecode.Ok).event

        assertThat(event.event).isEqualTo("FILL")
        assertThat(event.accountSeq).isEqualTo("3")
        with(event.order) {
            assertThat(symbol).isEqualTo("AAPL")
            assertThat(price).isEqualTo(decimal("100.5"))
            assertThat(orderAmount).isNull()
            assertThat(orderedAt).isEqualTo(Instant.parse("2026-06-23T00:30:00Z"))
            assertThat(execution.filledQuantity).isEqualTo(decimal("10"))
            assertThat(execution.commission).isEqualTo(decimal("1.23"))
            assertThat(execution.settlementDate).isEqualTo(LocalDate.parse("2026-06-25"))
            assertThat(closed).isTrue()
        }
    }

    @Test
    fun `reads a market order, which has no price, as still open while pending`() {
        val pending =
            filled
                .replace(""""event":"FILL"""", """"event":"PENDING"""")
                .replace(""""status":"FILLED"""", """"status":"PENDING"""")
                .replace(""""price":"100.5"""", """"price":null""")
                .replace(""""averageFilledPrice":"100"""", """"averageFilledPrice":null""")

        val order = (decodeOrderEvent(frame(pending)) as OrderDecode.Ok).event.order

        assertThat(order.price).isNull()
        assertThat(order.execution.averageFilledPrice).isNull()
        assertThat(order.closed).isFalse()
    }

    @Test
    fun `keeps a status it does not know rather than losing a lossless event`() {
        val novel = filled.replace(""""status":"FILLED"""", """"status":"PARTIAL_CANCELED"""")

        val order = (decodeOrderEvent(frame(novel)) as OrderDecode.Ok).event.order

        assertThat(order.status).isEqualTo("PARTIAL_CANCELED")
    }

    @Test
    fun `says what it could not read`() {
        fun reason(json: String) = (decodeOrderEvent(frame(json)) as OrderDecode.Failed).reason

        assertThat(reason("""[1]""")).isEqualTo("payload is not an object")
        assertThat(reason(filled.replace(""""quantity":"10",""", ""))).isEqualTo("quantity is missing")
        assertThat(
            reason(filled.replace(""""quantity":"10"""", """"quantity":10""")),
        ).isEqualTo("quantity is not a string")
        assertThat(reason(filled.replace(""""quantity":"10"""", """"quantity":"ten""""))).contains("quantity")
        assertThat(
            reason(filled.replace("2026-06-23T09:30:00.000+09:00", "2026-06-23 09:30")),
        ).startsWith("unparsable time")
    }
}
