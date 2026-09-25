@file:Suppress("TooManyFunctions") // One page: each function renders one part of it.

package tickguard.runner

import tickguard.execution.OrderRequest
import tickguard.observability.escapeHtml
import tickguard.orders.Order
import tickguard.stream.Decimal
import tickguard.time.SEOUL
import tickguard.trading.Side
import tickguard.trading.SleeveMode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the control page shows, read at the moment it is asked for. */
internal data class ControlView(
    val desk: DeskState,
    val now: Instant,
    /** The test's orders, since it began. */
    val orders: List<Order>,
    /** Order id to sleeve; an order without one is the person's own. */
    val tags: Map<String, String>,
)

/** Where the evening is: what the page asks the person to do next. */
internal enum class Stage(
    /** The step the progress bar marks as current; -1 for none. */
    val step: Int,
) {
    STOPPED(-1),
    OFF(-1),
    PROPOSE(0),
    WAIT(1),
    REVIEW(2),
    PLACED(3),
}

/**
 * The stage follows from the desk alone: a proposal not yet run is waiting for
 * the window, a run that only listed orders is waiting for a person, and a run
 * that sent any is done but for the fills.
 */
internal fun stageOf(desk: DeskState): Stage {
    val made = desk.proposedAt
    val run = desk.lastRun
    return when {
        desk.halted != null || desk.stopped -> Stage.STOPPED
        !desk.tradingOn -> Stage.OFF
        made == null -> Stage.PROPOSE
        run == null || run.at < made -> Stage.WAIT
        run.plan.live.isNotEmpty() -> Stage.PLACED
        else -> Stage.REVIEW
    }
}

/** The card's text and its one button, if the stage has one. */
private data class Card(
    val tone: String,
    val title: String,
    val body: String,
    val button: String = "",
)

/**
 * `/control`: the test's evening as steps, for a person on a phone. One card
 * says what to do now and holds the one button that does it; a button that
 * would be refused is shown disabled with the reason instead. The stop button
 * stays at the bottom of the screen throughout. The buttons send a header a
 * form on another site cannot, so a page elsewhere cannot press them.
 */
internal fun renderControl(view: ControlView): String {
    val stage = stageOf(view.desk)
    val card = card(stage, view)
    val stop =
        if (stage == Stage.STOPPED) {
            ""
        } else {
            "<div class=\"bar\"><button class=\"stop\" onclick=\"stopAll()\">■ 긴급 중지</button></div>"
        }
    return """
        |<!doctype html>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>tickguard 제어</title>
        |<style>$STYLE</style>
        |<main>
        |<header><b>tickguard</b><span class="dim">${CLOCK.format(view.now)}</span></header>
        |${stepper(stage)}
        |<section class="card ${card.tone}"><h1>${card.title}</h1><p>${card.body}</p>${card.button}</section>
        |<div id="toast" hidden></div>
        |<h2>슬리브</h2>
        |${sleeves(view)}
        |<h2>테스트 주문 ${view.orders.size}건</h2>
        |${orders(view.orders, view.tags)}
        |</main>
        |$stop
        |${confirmDialog(view)}
        |<script>$SCRIPT</script>
        |
        """.trimMargin()
}

private fun stepper(stage: Stage): String =
    listOf("제안", "모의 실행", "실제 주문", "체결 확인")
        .mapIndexed { index, name ->
            val state =
                when {
                    stage.step < 0 -> ""
                    index < stage.step -> "done"
                    index == stage.step -> "now"
                    else -> ""
                }
            "<li class=\"$state\">${if (state == "done") "✓" else "${index + 1}"} $name</li>"
        }.joinToString("", "<ol class=\"steps\">", "</ol>")

@Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per stage, each a few lines of the page's words.
private fun card(
    stage: Stage,
    view: ControlView,
): Card {
    val desk = view.desk
    val window = desk.window
    val opensAt = window?.start
    val closesAt = window?.endInclusive
    val open = window != null && view.now in window
    val notYet = opensAt?.let { "실제 주문은 ${CLOCK_SHORT.format(it)}부터" } ?: "미국 장 일정 없음"
    val closedReason = if (closesAt != null && view.now > closesAt) "오늘 주문 시간대가 끝났습니다" else notYet
    return when (stage) {
        Stage.STOPPED -> {
            desk.halted?.let {
                Card(
                    "bad",
                    "자동 주문이 멈췄습니다",
                    "결과를 알 수 없는 주문이 있었습니다: <code>${escapeHtml(it)}</code><br>" +
                        "토스 앱에서 그 주문이 들어갔는지 확인한 뒤 서버를 재시작하세요. 그 전에는 어떤 주문도 나가지 않습니다.",
                )
            } ?: Card(
                "bad",
                "긴급 중지 상태입니다",
                "서버를 재시작하기 전까지 주문이 나가지 않습니다. 이미 나간 주문은 취소되지 않았으니 아래 목록과 토스 앱에서 확인하세요.",
            )
        }

        Stage.OFF -> {
            Card(
                "dim",
                "자동 주문이 꺼져 있습니다",
                "서버 설정 <code>TICKGUARD_TRADING=on</code> 일 때만 이 페이지로 주문할 수 있습니다.",
            )
        }

        Stage.PROPOSE -> {
            Card(
                "",
                "오늘 주문할 목록을 먼저 받으세요",
                "목록만 만들고 주문은 보내지 않습니다. Discord에도 같은 목록이 갑니다.",
                "<button class=\"primary\" onclick=\"send('/sleeves/rebalance')\">주문 목록 받기</button>",
            )
        }

        Stage.WAIT -> {
            when {
                open -> {
                    Card("", "모의 실행 중입니다", "1분 안에 결과가 나옵니다.")
                }

                opensAt != null && view.now < opensAt -> {
                    Card(
                        "",
                        "${CLOCK_SHORT.format(opensAt)}에 모의 실행이 자동으로 됩니다",
                        "기다리기만 하면 됩니다 · ${until(view.now, opensAt)} 남음<br>" +
                            "모의 실행은 주문을 보내지 않고, 보낼 주문 목록만 만듭니다.",
                        "<button class=\"primary\" disabled>$notYet</button>",
                    )
                }

                else -> {
                    Card("dim", closedReason, "목록은 다음 주문 시간대에 실행됩니다.")
                }
            }
        }

        Stage.REVIEW -> {
            review(view, open, closedReason)
        }

        Stage.PLACED -> {
            placed(view)
        }
    }
}

private fun review(
    view: ControlView,
    open: Boolean,
    closedReason: String,
): Card {
    val desk = view.desk
    val plan = desk.lastRun?.plan ?: return Card("dim", "보낼 주문이 없습니다", "")
    val dry = plan.dryRun
    if (dry.isEmpty()) return Card("", "보낼 주문이 없습니다", "모든 슬리브가 목표 비중 안에 있습니다.")
    val summary =
        "${dry.size}건 · ${money(
            buys(dry),
            desk.fx,
        )}${sells(dry)} · 건너뜀 ${plan.skipped.count { it.reason != OFF_REASON }}건"
    val closes = desk.window?.endInclusive
    val button =
        if (open) {
            "<button class=\"primary live\" onclick=\"openConfirm()\">실제 주문 ${dry.size}건 넣기</button>"
        } else {
            "<button class=\"primary\" disabled>$closedReason</button>"
        }
    val until = closes?.let { " · 주문 가능 ${CLOCK_SHORT.format(it)}까지" }.orEmpty()
    return Card(
        "",
        "모의 실행 결과를 확인하세요",
        "$summary$until<br>아래 목록이 맞으면 실제 주문을 넣으세요. 이번 주문 시간대가 끝나면 다시 모의로 돌아갑니다.",
        button,
    )
}

private fun placed(view: ControlView): Card {
    val run = view.desk.lastRun ?: return Card("", "주문을 넣었습니다", "")
    val ids =
        run.result.placed
            .map { it.second }
            .toSet()
    val filled = view.orders.count { it.orderId in ids && it.status == "FILLED" }
    val refused =
        run.result.refused.joinToString("") { (order, why) ->
            val what = escapeHtml("${order.sleeve} ${order.symbol} · ${why.code} ${why.message}")
            "<br>✖ $what"
        }
    val back =
        view.desk.liveUntil
            ?.let { "<br>실주문 모드는 ${CLOCK_SHORT.format(it)}에 자동으로 모의로 돌아갑니다." }
            .orEmpty()
    val total = run.plan.live.size
    val share = if (ids.isEmpty()) 0 else filled * PERCENT / ids.size
    return Card(
        if (refused.isEmpty()) "ok" else "warn",
        "주문을 넣었습니다",
        "접수 ${ids.size}/$total · 체결 $filled/${ids.size}" +
            "<div class=\"meter\"><span style=\"width:$share%\"></span></div>" +
            "체결은 아래 목록에 몇 초~몇 분 안에 나타납니다.$refused$back",
    )
}

private fun sleeves(view: ControlView): String {
    val desk = view.desk
    if (desk.proposals.isEmpty()) return "<p class=\"dim\">아직 목록이 없습니다.</p>"
    val skipped =
        desk.lastRun
            ?.plan
            ?.skipped
            .orEmpty()
            .filter { it.reason != OFF_REASON }
    return desk.proposals.joinToString("") { proposal ->
        val sleeve = proposal.sleeve
        val mode = desk.modes[sleeve.id] ?: SleeveMode.OFF
        val rows =
            proposal.trades.joinToString("") { trade ->
                val side = if (trade.side == Side.BUY) "매수" else "매도"
                "<li><span>$side ${escapeHtml(trade.code)}</span><span class=\"num\">\$${trade.value.format(2)}" +
                    " <small>${won(trade.value, desk.fx)}</small></span></li>"
            }
        val skips =
            skipped.filter { it.sleeve == sleeve.id }.joinToString("") {
                "<li class=\"dim\"><span>– ${escapeHtml(it.symbol)}</span>" +
                    "<span>${escapeHtml(skipReason(it.reason))}</span></li>"
            }
        "<section class=\"sleeve\"><div class=\"head\"><b>${escapeHtml(sleeve.name)}</b>" +
            "<span class=\"badge ${mode.name.lowercase()}\">${modeName(mode)}</span></div>" +
            "<div class=\"dim\">자본 \$${sleeve.capital.format(2)} · 현재 \$${proposal.value.format(2)}</div>" +
            "<ul>${rows.ifEmpty { "<li class=\"dim\">거래 없음</li>" }}$skips</ul></section>"
    }
}

private fun orders(
    orders: List<Order>,
    tags: Map<String, String>,
): String {
    if (orders.isEmpty()) return "<p class=\"dim\">아직 없습니다.</p>"
    return orders.sortedByDescending { it.orderedAt }.joinToString("", "<ul class=\"orders\">", "</ul>") { order ->
        val side = if (order.side == "BUY") "매수" else "매도"
        val asked = order.orderAmount?.let { "\$${it.toPlainString()}" } ?: "${order.quantity.toPlainString()}주"
        val filled =
            order.execution.averageFilledPrice
                ?.let { " · ${order.execution.filledQuantity.toPlainString()}주 @ \$${it.toPlainString()}" }
                .orEmpty()
        val (label, tone) = STATUS[order.status] ?: (order.status to "")
        "<li><div><b>${escapeHtml(order.symbol)}</b> $side $asked<div class=\"dim\">" +
            escapeHtml("${tags[order.orderId] ?: "개인"} · ${CLOCK_SHORT.format(order.orderedAt)}$filled") +
            "</div></div>" +
            "<span class=\"badge $tone\">${escapeHtml(label)}</span></li>"
    }
}

private fun confirmDialog(view: ControlView): String {
    val dry =
        view.desk.lastRun
            ?.plan
            ?.dryRun
            .orEmpty()
    return """
        |<dialog id="confirm">
        |<h1>실제 주문 ${dry.size}건</h1>
        |<p>${money(buys(dry), view.desk.fx)}${sells(dry)}<br>시장가로 바로 체결됩니다. 넣은 주문은 이 페이지에서 취소할 수 없습니다.</p>
        |<p>확인하려면 <b>실제 주문</b> 을 입력하세요.</p>
        |<input id="typed" autocomplete="off" oninput="checkTyped()">
        |<div class="row"><button onclick="closeConfirm()">취소</button><button id="go" class="primary live" disabled onclick="goLive()">주문 넣기</button></div>
        |</dialog>
        """.trimMargin()
}

private fun buys(orders: List<OrderRequest>): Decimal =
    orders.mapNotNull { it.amount }.fold(Decimal.ZERO) { sum, amount -> sum + amount }

private fun money(
    dollars: Decimal,
    fx: Decimal,
) = "매수 \$${dollars.format(2)} (${won(dollars, fx)})"

private fun sells(orders: List<OrderRequest>): String =
    orders
        .count { it.side == Side.SELL }
        .takeIf { it > 0 }
        ?.let { " · 매도 ${it}건" }
        .orEmpty()

private fun won(
    dollars: Decimal,
    fx: Decimal,
) = "약 ${"%,d".format(Locale.US, (dollars * fx).floor())}원"

private fun until(
    now: Instant,
    at: Instant,
): String {
    val left = Duration.between(now, at)
    return if (left.toHours() > 0) "${left.toHours()}시간 ${left.toMinutesPart()}분" else "${left.toMinutesPart()}분"
}

private fun modeName(mode: SleeveMode) =
    when (mode) {
        SleeveMode.OFF -> "꺼짐"
        SleeveMode.DRY_RUN -> "모의"
        SleeveMode.LIVE -> "실주문"
    }

private fun skipReason(reason: String) = SKIP_REASONS[reason] ?: reason

/** What the plan says of a sleeve left off: not worth a line on the page. */
private const val OFF_REASON = "sleeve is off"

/** The plan's reasons, as the page says them. */
private val SKIP_REASONS =
    mapOf(
        "buy under \$1" to "\$1 미만 매수",
        "buy above the sleeve's capital" to "슬리브 자본 초과",
        "not in the sleeve's symbols" to "허용 종목 아님",
        "over the run's buy limit" to "총 매수 한도 초과",
        "over the run's order limit" to "주문 수 한도 초과",
    )

/** Toss's order statuses, as the page says them, with their colour. */
private val STATUS =
    mapOf(
        "PENDING" to ("대기" to ""),
        "PARTIAL_FILLED" to ("부분 체결" to "warn"),
        "FILLED" to ("체결" to "ok"),
        "CANCELED" to ("취소" to "dim"),
        "REJECTED" to ("거절" to "bad"),
    )

/** A ratio times this is a percent. */
private const val PERCENT = 100

/** Times as a person in Seoul reads them. */
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm 'KST'").withZone(SEOUL)

/** A time later the same evening. */
private val CLOCK_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(SEOUL)

/** The page's look: phone first, light and dark. */
private val STYLE =
    """
    :root { color-scheme: light dark; --bg:#fff; --fg:#1f2328; --dim:#6e7781; --line:#d0d7de; --card:#f6f8fa;
      --ok:#1a7f37; --bad:#cf222e; --warn:#9a6700; --live:#0969da }
    @media (prefers-color-scheme: dark) { :root { --bg:#0d1117; --fg:#e6edf3; --dim:#8b949e; --line:#30363d; --card:#161b22;
      --ok:#3fb950; --bad:#f85149; --warn:#d29922; --live:#4493f8 } }
    * { box-sizing:border-box }
    body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.55 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR",sans-serif }
    main { max-width:560px; margin:0 auto; padding:16px 16px 96px; word-break:keep-all; overflow-wrap:anywhere }
    header { display:flex; justify-content:space-between; align-items:baseline; margin-bottom:12px }
    h1 { font-size:1.2rem; margin:0 0 6px } h2 { font-size:.9rem; color:var(--dim); margin:28px 0 8px }
    p { margin:0 } code { font-size:.85em } .dim { color:var(--dim) } small { color:var(--dim) }
    .num, .orders .badge { font-variant-numeric:tabular-nums }
    .steps { display:flex; gap:4px; list-style:none; padding:0; margin:0 0 14px; font-size:.8rem }
    .steps li { flex:1; text-align:center; padding:6px 2px; border-bottom:3px solid var(--line); color:var(--dim) }
    .steps li.done { border-color:var(--ok); color:var(--ok) }
    .steps li.now { border-color:var(--live); color:var(--fg); font-weight:700 }
    .card { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:18px }
    .card.bad { border-color:var(--bad) } .card.bad h1 { color:var(--bad) }
    .card.ok { border-color:var(--ok) } .card.warn { border-color:var(--warn) }
    button { font:inherit; border-radius:10px; border:1px solid var(--line); background:var(--bg); color:var(--fg); padding:10px 14px; cursor:pointer }
    button.primary { display:block; width:100%; margin-top:16px; padding:14px; font-weight:700; background:var(--fg); color:var(--bg); border:0 }
    button.live { background:var(--live); color:#fff }
    button:disabled { background:var(--line); color:var(--dim); cursor:default }
    .sleeve { border:1px solid var(--line); border-radius:12px; padding:12px 14px; margin-bottom:10px }
    .head { display:flex; justify-content:space-between; align-items:center }
    ul { list-style:none; padding:0; margin:8px 0 0 } li { display:flex; justify-content:space-between; gap:8px; padding:6px 0; border-top:1px solid var(--line) }
    .badge { font-size:.75rem; padding:2px 8px; border-radius:999px; border:1px solid var(--line); white-space:nowrap; align-self:center }
    .badge.live, .badge.ok { color:var(--ok); border-color:var(--ok) } .badge.dry_run { color:var(--live); border-color:var(--live) }
    .badge.bad { color:var(--bad); border-color:var(--bad) } .badge.warn { color:var(--warn); border-color:var(--warn) }
    .meter { height:8px; background:var(--line); border-radius:4px; margin:10px 0; overflow:hidden } .meter span { display:block; height:100%; background:var(--ok) }
    .bar { position:fixed; left:0; right:0; bottom:0; padding:10px 16px calc(10px + env(safe-area-inset-bottom)); background:var(--bg); border-top:1px solid var(--line) }
    .bar button { display:block; width:100%; max-width:560px; margin:0 auto; color:var(--bad); border-color:var(--bad); font-weight:700 }
    #toast { margin-top:12px; padding:12px; border-radius:10px; background:var(--card); border:1px solid var(--line) }
    dialog { border:1px solid var(--line); border-radius:14px; background:var(--bg); color:var(--fg); max-width:420px; width:calc(100% - 32px) }
    dialog input { width:100%; font:inherit; padding:10px; margin:12px 0; border-radius:8px; border:1px solid var(--line); background:var(--card); color:var(--fg) }
    dialog .row { display:flex; gap:8px } dialog .row button { flex:1; margin-top:0 }
    """.trimIndent()

/** The buttons: each sends the control header, says what happened, and refreshes; refresh waits for a dialog. */
private val SCRIPT =
    """
    const REFUSALS = { 'trading is off': '자동 주문이 꺼져 있거나 중지된 상태입니다',
      'halted': '결과 불명 주문 때문에 멈춘 상태입니다', 'outside the order window': '주문 시간대가 아닙니다',
      'no sleeve in DRY_RUN': '모의 상태인 슬리브가 없습니다' };
    let busy = false;
    async function send(path) {
      busy = true;
      const toast = document.getElementById('toast');
      toast.hidden = false; toast.textContent = '요청 중…';
      try {
        const r = await fetch(path, { method: 'POST', headers: { 'X-Tickguard-Control': '1' } });
        const text = (await r.text()).trim();
        const reason = text.replace(/^refused: /, '');
        toast.textContent = r.ok ? '요청했습니다. 잠시 후 화면이 바뀝니다.' : '거절됨: ' + (REFUSALS[reason] || text);
      } catch (e) { toast.textContent = '서버에 닿지 않았습니다: ' + e; }
      busy = false;
      setTimeout(() => location.reload(), 3000);
    }
    function openConfirm() { busy = true; document.getElementById('typed').value = ''; checkTyped(); document.getElementById('confirm').showModal(); }
    function closeConfirm() { document.getElementById('confirm').close(); busy = false; }
    function checkTyped() { document.getElementById('go').disabled = document.getElementById('typed').value.trim() !== '실제 주문'; }
    function goLive() { closeConfirm(); send('/sleeves/live'); }
    function stopAll() {
      if (confirm('서버를 재시작하기 전까지 모든 자동 주문을 막습니다. 이미 넣은 주문은 취소되지 않습니다.')) send('/sleeves/stop');
    }
    setInterval(() => { if (!busy) location.reload(); }, 15000);
    """.trimIndent()
