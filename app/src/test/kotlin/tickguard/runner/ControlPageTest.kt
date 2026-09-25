package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.execution.ExecutionResult
import tickguard.execution.OrderPlan
import tickguard.execution.OrderRequest
import tickguard.execution.SkippedTrade
import tickguard.orders.Execution
import tickguard.orders.Order
import tickguard.stream.Decimal
import tickguard.trading.ProposedTrade
import tickguard.trading.Side
import tickguard.trading.SleeveMode
import tickguard.trading.SleevePosition
import tickguard.trading.SleeveProposal
import tickguard.trading.TestSleeves
import java.time.Instant
import java.time.LocalDate

class ControlPageTest {
    /** 20:45 KST, before the 22:40 window. */
    private val evening = Instant.parse("2026-09-25T11:45:00Z")
    private val window = Instant.parse("2026-09-25T13:40:00Z")..Instant.parse("2026-09-25T19:00:00Z")
    private val inWindow = Instant.parse("2026-09-25T13:45:00Z")

    private fun d(text: String) = Decimal.parse(text, "test")

    private val proposal =
        SleeveProposal(
            TestSleeves.ALL.first(),
            SleevePosition(emptyMap(), Decimal.ZERO, Decimal.ZERO, 0),
            d("153.44"),
            emptyMap(),
            listOf(ProposedTrade("SPY", Side.BUY, d("0.046"), d("30.69"), d("663.2"), Decimal.ZERO, Decimal.ZERO)),
            null,
            LocalDate.parse("2026-09-24"),
        )

    private val spy = OrderRequest("tg-20260925-A-B-SPY", "A", "SPY", Side.BUY, d("30.69"), null)

    private fun desk(
        proposedAt: Instant? = null,
        lastRun: LastRun? = null,
        stopped: Boolean = false,
        halted: String? = null,
        tradingOn: Boolean = true,
    ) = DeskState(
        tradingOn = tradingOn && !stopped,
        stopped = stopped,
        halted = halted,
        modes = mapOf("A" to SleeveMode.DRY_RUN, "B" to SleeveMode.DRY_RUN, "C" to SleeveMode.DRY_RUN),
        liveUntil = null,
        window = window,
        proposedAt = proposedAt,
        proposals = if (proposedAt == null) emptyList() else listOf(proposal),
        lastRun = lastRun,
        fx = d("1400"),
    )

    private fun dryRun(at: Instant) =
        LastRun(
            at,
            OrderPlan(emptyList(), listOf(spy), listOf(SkippedTrade("C", "AAPL", "sleeve is off"))),
            ExecutionResult(emptyList(), emptyList(), null),
        )

    private fun liveRun(at: Instant) =
        LastRun(
            at,
            OrderPlan(listOf(spy), emptyList(), emptyList()),
            ExecutionResult(listOf(spy to "o-1"), emptyList(), null),
        )

    @Test
    fun `walks the evening from proposing to waiting to reviewing to placed`() {
        assertThat(stageOf(desk())).isEqualTo(Stage.PROPOSE)
        assertThat(stageOf(desk(proposedAt = evening))).isEqualTo(Stage.WAIT)
        assertThat(stageOf(desk(proposedAt = evening, lastRun = dryRun(inWindow)))).isEqualTo(Stage.REVIEW)
        // Pressing LIVE proposes again: the dry run belongs to the older proposal.
        assertThat(
            stageOf(desk(proposedAt = inWindow.plusSeconds(60), lastRun = dryRun(inWindow))),
        ).isEqualTo(Stage.WAIT)
        assertThat(stageOf(desk(proposedAt = inWindow, lastRun = liveRun(inWindow)))).isEqualTo(Stage.PLACED)
    }

    @Test
    fun `puts a stop or a halt ahead of everything, and says so when trading is off`() {
        assertThat(stageOf(desk(proposedAt = evening, stopped = true))).isEqualTo(Stage.STOPPED)
        assertThat(stageOf(desk(halted = "tg-1: 503"))).isEqualTo(Stage.STOPPED)
        assertThat(stageOf(desk(tradingOn = false))).isEqualTo(Stage.OFF)
    }

    @Test
    fun `before the window, says when the dry run comes and offers no order button`() {
        val page = renderControl(ControlView(desk(proposedAt = evening), evening, emptyList(), emptyMap()))

        assertThat(page).contains("22:40에 모의 실행이 자동으로 됩니다").contains("1시간 55분 남음")
        assertThat(page).contains("<button class=\"primary\" disabled>실제 주문은 22:40부터</button>")
        assertThat(page).doesNotContain("openConfirm()\">")
    }

    @Test
    fun `after a dry run in the window, offers the orders with their total in won`() {
        val page =
            renderControl(
                ControlView(desk(proposedAt = evening, lastRun = dryRun(inWindow)), inWindow, emptyList(), emptyMap()),
            )

        assertThat(page).contains("1건 · 매수 \$30.69 (약 42,966원) · 건너뜀 0건 · 주문 가능 04:00까지")
        assertThat(page).contains("실제 주문 1건 넣기").contains("매수 SPY").contains("모의")
    }

    @Test
    fun `lists orders with their sleeve, a person's own untagged, and escapes what the broker sent`() {
        val filled = Execution(d("0.046"), d("663.2"), null, null, null, null)
        val orders =
            listOf(
                Order(
                    "o-1",
                    "SPY",
                    "BUY",
                    "MARKET",
                    "DAY",
                    "FILLED",
                    null,
                    d("0.046"),
                    d("30.69"),
                    "USD",
                    inWindow,
                    null,
                    filled,
                ),
                Order(
                    "o-2",
                    "<b>X</b>",
                    "SELL",
                    "LIMIT",
                    "DAY",
                    "PENDING",
                    d("1"),
                    d("2"),
                    null,
                    "USD",
                    inWindow,
                    null,
                    filled,
                ),
            )
        val page =
            renderControl(
                ControlView(
                    desk(proposedAt = inWindow, lastRun = liveRun(inWindow)),
                    inWindow,
                    orders,
                    mapOf("o-1" to "A"),
                ),
            )

        assertThat(page).contains("접수 1/1 · 체결 1/1")
        assertThat(page).contains("<b>SPY</b> 매수 \$30.69").contains("A · 22:45 · 0.046주 @ \$663.2").contains(">체결<")
        assertThat(page).contains("개인 · 22:45").contains("&lt;b&gt;X&lt;/b&gt;").doesNotContain("<b><b>X</b></b>")
    }
}
