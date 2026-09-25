package tickguard.orders

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.decimal
import tickguard.testing.order

class OrderStoreTest {
    @Test
    fun `takes any state of an order it has not seen`() {
        assertThat(supersedes(order(status = "PENDING"), held = null)).isTrue()
    }

    @Test
    fun `moves forward with the fill, never back`() {
        val partial = order(status = "PARTIAL_FILLED", filled = "3")
        val more = order(status = "PARTIAL_FILLED", filled = "5")

        assertThat(supersedes(more, partial)).isTrue()
        assertThat(supersedes(partial, more)).isFalse()
    }

    @Test
    fun `takes a status change at the same fill, until the status is final`() {
        val pending = order(status = "PENDING", filled = "0")
        val canceling = order(status = "PENDING_CANCEL", filled = "0")
        val canceled = order(status = "CANCELED", filled = "0")

        assertThat(supersedes(canceling, pending)).isTrue()
        assertThat(supersedes(canceled, canceling)).isTrue()
        assertThat(supersedes(pending, canceled)).isFalse()
        assertThat(supersedes(canceled, canceled)).isFalse()
    }

    @Test
    fun `counts fills by quantity, not by how the number is written`() {
        assertThat(supersedes(order(filled = "10.0"), order(filled = "10"))).isFalse()
        assertThat(decimal("10.0")).isEqualTo(decimal("10"))
    }
}
