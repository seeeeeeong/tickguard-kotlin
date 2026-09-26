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
        daily: Set<String> = emptySet(),
    ) = DeskState(
        tradingOn = tradingOn && !stopped,
        stopped = stopped,
        halted = halted,
        modes = mapOf("A" to SleeveMode.DRY_RUN, "B" to SleeveMode.DRY_RUN, "C" to SleeveMode.DRY_RUN),
        liveUntil = null,
        daily = daily,
        switchable = true,
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
    fun `before the window, says when buying opens and offers no buy button`() {
        val page = renderControl(ControlView(desk(proposedAt = evening), evening, emptyList(), emptyMap()))

        assertThat(page).contains("22:40부터 살 수 있어요 · 1시간 55분 남음")
        assertThat(page).contains("<button class=\"primary\" disabled>22:40부터 구매할 수 있어요</button>")
        assertThat(page).doesNotContain("원 구매하기").doesNotContain("id=\"buy\"")
        assertThat(page).contains("연습 모드").contains("안정형").contains("미국 S&amp;P 500")
    }

    @Test
    fun `after a dry run in the window, offers to buy the total in won`() {
        val page =
            renderControl(
                ControlView(desk(proposedAt = evening, lastRun = dryRun(inWindow)), inWindow, emptyList(), emptyMap()),
            )

        assertThat(page).contains("42,966<small>원</small>").contains("\$30.69 · 1개 종목")
        assertThat(page).contains("지금 살 수 있어요 · 04:00까지").contains("42,966원 구매하기")
        assertThat(page).contains("<h2>42,966원 구매할까요?</h2>").contains("안정형 · 1종목")
    }

    @Test
    fun `after placing, marks each stock with its fill`() {
        val filled = Execution(d("0.046"), d("663.2"), null, null, null, null)
        val order =
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
            )
        val page =
            renderControl(
                ControlView(
                    desk(proposedAt = inWindow, lastRun = liveRun(inWindow)),
                    inWindow,
                    listOf(order),
                    mapOf("o-1" to "A"),
                ),
            )

        assertThat(page).contains("오늘 주문한 금액").contains("모두 체결됐어요").contains("체결 · 0.046주")
        assertThat(page).contains("<button class=\"primary\" disabled>주문 완료</button>")
    }

    @Test
    fun `says a halt in plain words, and escapes what it quotes`() {
        val page = renderControl(ControlView(desk(halted = "<b>tg-1</b>: 503"), evening, emptyList(), emptyMap()))

        assertThat(page).contains("자동 주문이 멈췄어요").contains("&lt;b&gt;tg-1&lt;/b&gt;: 503").doesNotContain("<b>tg-1</b>")
        assertThat(page).doesNotContain("openSheet('stop')")
    }

    @Test
    fun `offers the daily switch while off, and says it is on with a way to switch it off`() {
        val off = renderControl(ControlView(desk(proposedAt = evening), evening, emptyList(), emptyMap()))
        val on =
            renderControl(ControlView(desk(proposedAt = evening, daily = setOf("D")), evening, emptyList(), emptyMap()))

        assertThat(off).contains("매일 자동 주문</b>").contains("openSheet('dailyOn')").doesNotContain("매일 자동 주문 켜짐")
        assertThat(on).contains("매일 자동 주문 켜짐").contains("openSheet('dailyOff')").contains("22:40에 자동으로 주문해요")
        assertThat(on).contains("<span class=\"pill blue\">매일 자동 주문</span>")
    }
}
