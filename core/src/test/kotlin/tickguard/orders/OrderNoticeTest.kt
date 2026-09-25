package tickguard.orders

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.order

class OrderNoticeTest {
    @Test
    fun `says what filled, at what average, and what it cost`() {
        val notice = orderNotice(order(status = "FILLED", filled = "10"), OrderSource.STREAM)!!

        assertThat(notice.title).isEqualTo("AAPL 매수 체결 10/10주 @ 100")
        assertThat(notice.detail).isEqualTo("지정가 100.5 USD · 수수료 1.23 · 세금 0")
    }

    @Test
    fun `names a market order and leaves out a price for a cancel with nothing filled`() {
        val notice = orderNotice(order(status = "CANCELED", filled = "0", price = null), OrderSource.STREAM)!!

        assertThat(notice.title).isEqualTo("AAPL 매수 취소 0/10주")
        assertThat(notice.detail).isEqualTo("시장가 USD")
    }

    @Test
    fun `marks a change the stream missed and the resync found`() {
        val notice = orderNotice(order(status = "PARTIAL_FILLED", filled = "3"), OrderSource.RESYNC)!!

        assertThat(notice.title).startsWith("AAPL 매수 부분 체결 3/10주")
        assertThat(notice.detail).endsWith("\n(연결이 끊긴 사이의 변화, 재연결 후 확인)")
    }

    @Test
    fun `stays quiet about an order that was only accepted`() {
        assertThat(orderNotice(order(status = "PENDING", filled = "0"), OrderSource.STREAM)).isNull()
        assertThat(orderNotice(order(status = "PENDING_CANCEL", filled = "0"), OrderSource.STREAM)).isNull()
    }
}
