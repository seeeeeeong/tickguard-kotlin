@file:Suppress("TooManyFunctions") // One page: each function renders one part of it.

package tickguard.runner

import tickguard.observability.escapeHtml
import tickguard.orders.Order
import tickguard.stream.Decimal
import tickguard.time.SEOUL
import tickguard.trading.Side
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
internal enum class Stage {
    STOPPED,
    OFF,
    PROPOSE,
    WAIT,
    REVIEW,
    PLACED,
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

/** The page's headline amount, the line under it, and the one button at the bottom. */
private data class Screen(
    val label: String,
    val dollars: Decimal,
    val count: Int,
    val note: String,
    val noteTone: String,
    val button: String,
)

/**
 * `/control`, laid out like a brokerage's order screen for a person on a
 * phone: the amount to buy in won, the stocks under it, and one button at the
 * bottom that says what it does ("300,000원 구매하기") or, while it cannot be
 * pressed, when it can. The dry run and the modes stay out of sight; the pill
 * at the top says whether real orders are on, and stop sits beside it
 * throughout. The buttons send a header a form on another site cannot.
 */
internal fun renderControl(view: ControlView): String {
    val stage = stageOf(view.desk)
    val screen = screen(stage, view)
    return """
        |<!doctype html>
        |<html lang="ko">
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        |<title>오늘 주문 · tickguard</title>
        |<link rel="stylesheet" href="$FONT">
        |<style>$STYLE</style>
        |<body>
        |<div class="top"><span class="brand">tickguard</span>${pill(view.desk)}${stopLink(stage)}</div>
        |<main>
        |${alert(view.desk)}
        |<section class="hero">
        |  <div class="label">${screen.label}</div>
        |  <div class="big">${won(screen.dollars, view.desk.fx)}<small>원</small></div>
        |  <div class="sub">${'$'}${screen.dollars.format(2)} · ${screen.count}개 종목</div>
        |  <div class="note ${screen.noteTone}">${screen.note}</div>
        |</section>
        |${groups(view)}
        |</main>
        |<div class="cta">${screen.button}</div>
        |<div id="toast" hidden></div>
        |${buySheet(view.desk)}
        |$STOP_SHEET
        |<script>$SCRIPT</script>
        |
        """.trimMargin()
}

private fun pill(desk: DeskState): String {
    val (text, tone) =
        when {
            desk.halted != null || desk.stopped -> "멈춤" to "red"
            !desk.tradingOn -> "꺼짐" to "grey"
            desk.liveUntil != null -> "실제 주문 중" to "blue"
            else -> "연습 모드" to "grey"
        }
    return "<span class=\"pill $tone\">$text</span>"
}

private fun stopLink(stage: Stage) =
    if (stage == Stage.STOPPED) "" else "<button class=\"stoplink\" onclick=\"openSheet('stop')\">중지</button>"

private fun alert(desk: DeskState): String {
    val halted = desk.halted
    val (title, body) =
        when {
            halted != null -> {
                "자동 주문이 멈췄어요" to
                    "응답을 못 받은 주문이 있어요. 토스증권 앱에서 주문이 들어갔는지 확인하고 알려주세요." +
                    "<br><code>${escapeHtml(halted)}</code>"
            }

            desk.stopped -> {
                "자동 주문을 멈췄어요" to "서버를 다시 켜기 전까지 주문이 나가지 않아요. 이미 넣은 주문은 토스증권 앱에서 확인하세요."
            }

            else -> {
                return ""
            }
        }
    return "<section class=\"alert\"><b>$title</b><p>$body</p></section>"
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod") // One branch per stage, a few words each.
private fun screen(
    stage: Stage,
    view: ControlView,
): Screen {
    val desk = view.desk
    val window = desk.window
    val open = window != null && view.now in window
    val opensAt = window?.start
    val closesAt = window?.endInclusive
    val proposed = desk.proposals.flatMap { it.trades }.filter { it.side == Side.BUY }
    val planned = Screen("오늘 살 금액", proposed.total { it.value }, proposed.size, "", "", "")
    val (later, laterButton) =
        when {
            opensAt != null && view.now < opensAt -> {
                "${CLOCK.format(opensAt)}부터 살 수 있어요 · ${until(view.now, opensAt)} 남음" to
                    "${CLOCK.format(opensAt)}부터 구매할 수 있어요"
            }

            closesAt != null && view.now > closesAt -> {
                "오늘 주문 시간이 끝났어요" to "오늘 주문 시간이 끝났어요"
            }

            else -> {
                "미국 장 일정을 아직 못 불러왔어요" to "지금은 구매할 수 없어요"
            }
        }
    return when (stage) {
        Stage.STOPPED -> {
            planned.copy(note = "주문이 나가지 않는 상태예요", noteTone = "red", button = disabled("멈춘 상태예요"))
        }

        Stage.OFF -> {
            planned.copy(note = "서버 설정에서 자동 주문이 꺼져 있어요", button = disabled("자동 주문이 꺼져 있어요"))
        }

        Stage.PROPOSE -> {
            planned.copy(
                note = "목록을 불러와도 바로 사지 않아요",
                button = "<button class=\"primary\" onclick=\"load()\">오늘 주문 목록 불러오기</button>",
            )
        }

        Stage.WAIT -> {
            if (open) {
                planned.copy(note = "주문 준비 중이에요 · 1분 안에 끝나요", button = disabled("준비 중…"))
            } else {
                planned.copy(note = later, button = disabled(laterButton))
            }
        }

        Stage.REVIEW -> {
            val buys =
                desk.lastRun
                    ?.plan
                    ?.dryRun
                    .orEmpty()
            val total = buys.total { it.amount }
            when {
                buys.isEmpty() -> {
                    planned.copy(note = "지금은 살 종목이 없어요", button = disabled("살 종목이 없어요"))
                }

                open -> {
                    Screen(
                        "오늘 살 금액",
                        total,
                        buys.size,
                        "지금 살 수 있어요 · ${closesAt?.let { CLOCK.format(it) }}까지",
                        "blue",
                        "<button class=\"primary\" onclick=\"openSheet('buy')\">${won(total, desk.fx)}원 구매하기</button>",
                    )
                }

                else -> {
                    Screen("오늘 살 금액", total, buys.size, later, "", disabled(laterButton))
                }
            }
        }

        Stage.PLACED -> {
            val run = desk.lastRun
            val placed = run?.result?.placed.orEmpty()
            val ids = placed.map { it.second }.toSet()
            val filled = view.orders.count { it.orderId in ids && it.status == "FILLED" }
            val refused =
                run
                    ?.result
                    ?.refused
                    .orEmpty()
                    .size
            val done = filled == ids.size
            val note =
                buildString {
                    append(if (done) "모두 체결됐어요" else "${ids.size}개 중 ${filled}개 체결됐어요")
                    if (refused > 0) append(" · ${refused}개는 거절됐어요")
                }
            Screen(
                "오늘 주문한 금액",
                placed.total { it.first.amount },
                ids.size,
                note,
                if (refused > 0) "red" else "green",
                disabled(if (done) "주문 완료" else "체결 기다리는 중…"),
            )
        }
    }
}

private fun disabled(text: String) = "<button class=\"primary\" disabled>$text</button>"

private fun groups(view: ControlView): String {
    val desk = view.desk
    if (desk.proposals.isEmpty()) {
        return "<section class=\"empty\">아직 주문 목록이 없어요<br>아래 버튼으로 불러오세요</section>"
    }
    val skipped =
        desk.lastRun
            ?.plan
            ?.skipped
            .orEmpty()
    return desk.proposals.joinToString("") { proposal ->
        val sleeve = proposal.sleeve
        val (name, blurb) = SLEEVE_NAMES[sleeve.id] ?: (sleeve.name to "")
        val rows =
            proposal.trades.joinToString("") { trade ->
                val skip = skipped.firstOrNull { it.sleeve == sleeve.id && it.symbol == trade.code }
                val order =
                    view.orders
                        .filter { view.tags[it.orderId] == sleeve.id && it.symbol == trade.code }
                        .maxByOrNull { it.orderedAt }
                row(Line(trade.code, trade.side, trade.value, order, skip?.reason), desk.fx)
            }
        "<section class=\"group\"><div class=\"ghead\"><div><b>${escapeHtml(
            name,
        )}</b><span>${escapeHtml(blurb)}</span></div>" +
            "<span class=\"cap\">${won(sleeve.capital, desk.fx)}원</span></div>" +
            rows.ifEmpty { "<div class=\"row none\">이번에는 살 종목이 없어요</div>" } + "</section>"
    }
}

/** One stock in a sleeve: what to trade, and what became of it. */
private data class Line(
    val symbol: String,
    val side: Side,
    val dollars: Decimal,
    val order: Order?,
    val skipped: String?,
)

private fun row(
    line: Line,
    fx: Decimal,
): String {
    val order = line.order
    val status =
        when {
            line.skipped != null -> {
                val reason = SKIP_REASONS[line.skipped] ?: line.skipped
                "<span class=\"chip grey\">제외 · ${escapeHtml(reason)}</span>"
            }

            order != null -> {
                val (label, tone) = STATUS[order.status] ?: (order.status to "grey")
                val shares =
                    order.execution.averageFilledPrice
                        ?.let { " · ${order.execution.filledQuantity.toPlainString()}주" }
                        .orEmpty()
                "<span class=\"chip $tone\">${escapeHtml(label + shares)}</span>"
            }

            else -> {
                ""
            }
        }
    val sell = if (line.side == Side.SELL) "<em>팔기</em> " else ""
    val symbol = escapeHtml(line.symbol)
    return "<div class=\"row${if (line.skipped != null) " muted" else ""}\">" +
        "<div class=\"logo\" style=\"background:hsl(${hue(
            line.symbol,
        )} 55% 48%)\">${escapeHtml(line.symbol.take(2))}</div>" +
        "<div class=\"info\"><b>$sell$symbol</b><span>${escapeHtml(NAMES[line.symbol].orEmpty())}</span></div>" +
        "<div class=\"amt\"><b>${won(line.dollars, fx)}원</b><span>\$${line.dollars.format(2)}</span>$status</div></div>"
}

private fun buySheet(desk: DeskState): String {
    val buys =
        desk.lastRun
            ?.plan
            ?.dryRun
            .orEmpty()
    if (buys.isEmpty()) return ""
    val lines =
        buys.groupBy { it.sleeve }.entries.joinToString("") { (sleeve, orders) ->
            val name = SLEEVE_NAMES[sleeve]?.first ?: sleeve
            "<li><span>${escapeHtml(
                name,
            )} · ${orders.size}종목</span><b>${won(orders.total { it.amount }, desk.fx)}원</b></li>"
        }
    val total = buys.total { it.amount }
    return """
        |<dialog id="buy" class="sheet">
        |  <h2>${won(total, desk.fx)}원 구매할까요?</h2>
        |  <p>${buys.size}개 종목을 지금 시장가로 사요.<br>주문한 뒤에는 이 화면에서 취소할 수 없어요.</p>
        |  <ul>$lines</ul>
        |  <div class="btns"><button class="ghost" onclick="closeSheet('buy')">취소</button><button class="primary" onclick="buy()">구매하기</button></div>
        |</dialog>
        """.trimMargin()
}

private fun <T> List<T>.total(value: (T) -> Decimal?): Decimal =
    mapNotNull(value).fold(Decimal.ZERO) { sum, next -> sum + next }

private fun won(
    dollars: Decimal,
    fx: Decimal,
) = "%,d".format(Locale.US, (dollars * fx).floor())

private fun until(
    now: Instant,
    at: Instant,
): String {
    val left = Duration.between(now, at)
    return if (left.toHours() > 0) "${left.toHours()}시간 ${left.toMinutesPart()}분" else "${left.toMinutesPart()}분"
}

/** A stable colour per symbol for its round badge. */
private fun hue(symbol: String) = Math.floorMod(symbol.hashCode(), HUES)

/** Degrees on the colour wheel. */
private const val HUES = 360

/** Korean's webfont of choice for screens like this; the system font stands in without it. */
private const val FONT = "https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css"

/** The sleeves as a person would call them, with what each does in a line. */
private val SLEEVE_NAMES =
    mapOf(
        "A" to ("안정형" to "미국·해외 주식, 채권, 금에 똑같이 나눠 담아요"),
        "B" to ("추세형" to "오르는 자산만 담고, 떨어지면 현금으로 피해요"),
        "C" to ("공격형" to "최근 6개월 가장 많이 오른 대형주 2개"),
    )

/** What each symbol in the test is, in a few words. */
private val NAMES =
    mapOf(
        "SPY" to "미국 S&P 500",
        "QQQ" to "미국 나스닥 100",
        "TLT" to "미국 장기 국채",
        "GLD" to "금",
        "EFA" to "선진국 주식",
        "VTI" to "미국 주식 전체",
        "VEU" to "미국 밖 주식",
        "IEF" to "미국 중기 국채",
        "VNQ" to "미국 부동산(리츠)",
        "DBC" to "원자재",
        "SGOV" to "초단기 국채 · 현금 대신",
        "AAPL" to "애플",
        "MSFT" to "마이크로소프트",
        "NVDA" to "엔비디아",
        "META" to "메타",
        "AVGO" to "브로드컴",
        "TSLA" to "테슬라",
        "BRK.B" to "버크셔 해서웨이",
        "JPM" to "JP모건",
        "LLY" to "일라이 릴리",
        "V" to "비자",
        "WMT" to "월마트",
        "ORCL" to "오라클",
        "NFLX" to "넷플릭스",
        "COST" to "코스트코",
        "XOM" to "엑슨모빌",
        "AMZN" to "아마존",
        "GOOGL" to "알파벳(구글)",
    )

/** The plan's reasons, as the page says them. */
private val SKIP_REASONS =
    mapOf(
        "sleeve is off" to "꺼진 전략",
        "buy under \$1" to "1달러 미만",
        "buy above the sleeve's capital" to "전략 금액 초과",
        "not in the sleeve's symbols" to "허용 종목 아님",
        "over the run's buy limit" to "총 한도 초과",
        "over the run's order limit" to "주문 수 한도 초과",
    )

/** Toss's order statuses, as the page says them, with their colour. */
private val STATUS =
    mapOf(
        "PENDING" to ("주문 중" to "grey"),
        "PARTIAL_FILLED" to ("일부 체결" to "blue"),
        "FILLED" to ("체결" to "green"),
        "CANCELED" to ("취소됨" to "grey"),
        "REJECTED" to ("거절됨" to "red"),
    )

/** Times as a person in Seoul reads them. */
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(SEOUL)

/** The confirmation for stop: what it blocks, and what it cannot take back. */
private val STOP_SHEET =
    """
    <dialog id="stop" class="sheet">
      <h2>자동 주문을 모두 멈출까요?</h2>
      <p>지금부터 어떤 주문도 나가지 않아요.<br>이미 넣은 주문은 취소되지 않아요. 토스증권 앱에서 확인하세요.</p>
      <div class="btns"><button class="ghost" onclick="closeSheet('stop')">취소</button><button class="danger" onclick="stopAll()">멈추기</button></div>
    </dialog>
    """.trimIndent()

/** The page's look: a brokerage's order screen, phone first, light and dark. */
private val STYLE =
    """
    :root { color-scheme:light dark; --bg:#f2f4f6; --card:#fff; --fg:#191f28; --sub:#4e5968; --dim:#8b95a1; --line:#e5e8eb;
      --blue:#3182f6; --blue-bg:#e8f3ff; --red:#f04452; --red-bg:#ffeeee; --green:#03b26c; --green-bg:#e5f8ef; --grey-bg:#f2f4f6 }
    @media (prefers-color-scheme: dark) { :root { --bg:#101013; --card:#1b1b20; --fg:#f9fafb; --sub:#c3c9d0; --dim:#7e8691;
      --line:#2c2c35; --blue-bg:#1c2a40; --red-bg:#3a1c20; --green-bg:#15302a; --grey-bg:#26262d } }
    * { box-sizing:border-box; -webkit-tap-highlight-color:transparent }
    body { margin:0; background:var(--bg); color:var(--fg); word-break:keep-all; overflow-wrap:anywhere;
      font:16px/1.5 Pretendard,-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo",sans-serif }
    .top { position:sticky; top:0; z-index:2; display:flex; align-items:center; gap:8px; max-width:520px; margin:0 auto;
      padding:14px 20px; background:var(--bg) }
    .brand { font-weight:700; font-size:17px; margin-right:auto }
    .pill { font-size:13px; font-weight:600; padding:4px 10px; border-radius:999px }
    .pill.grey { background:var(--card); color:var(--sub) } .pill.blue { background:var(--blue-bg); color:var(--blue) }
    .pill.red { background:var(--red-bg); color:var(--red) }
    .stoplink { border:0; background:none; color:var(--red); font:inherit; font-size:14px; font-weight:600; padding:6px 2px 6px 6px; cursor:pointer }
    main { max-width:520px; margin:0 auto; padding:0 16px 120px }
    .hero { background:var(--card); border-radius:20px; padding:24px 22px; margin-bottom:12px }
    .label { color:var(--sub); font-size:15px }
    .big { font-size:34px; font-weight:700; letter-spacing:-.5px; margin-top:2px; font-variant-numeric:tabular-nums }
    .big small { font-size:24px; margin-left:2px }
    .sub { color:var(--dim); font-size:14px }
    .note { margin-top:16px; padding:12px 14px; border-radius:12px; background:var(--grey-bg); color:var(--sub); font-size:14px; font-weight:600 }
    .note:empty { display:none }
    .note.blue { background:var(--blue-bg); color:var(--blue) } .note.green { background:var(--green-bg); color:var(--green) }
    .note.red { background:var(--red-bg); color:var(--red) }
    .alert { background:var(--red-bg); color:var(--red); border-radius:16px; padding:16px 18px; margin-bottom:12px }
    .alert p { margin:4px 0 0; color:var(--fg); font-size:14px } .alert code { font-size:12px; color:var(--sub) }
    .group { background:var(--card); border-radius:20px; padding:6px 0 10px; margin-bottom:12px }
    .ghead { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; padding:16px 22px 8px }
    .ghead b { font-size:18px } .ghead span { display:block; color:var(--dim); font-size:13px }
    .ghead .cap { color:var(--sub); font-size:15px; font-weight:600; white-space:nowrap }
    .row { display:flex; align-items:center; gap:12px; padding:11px 22px }
    .row.muted { opacity:.45 } .row.none { color:var(--dim); font-size:14px }
    .logo { flex:none; width:40px; height:40px; border-radius:50%; color:#fff; font-size:13px; font-weight:700;
      display:flex; align-items:center; justify-content:center }
    .info { flex:1; min-width:0 } .info b { display:block; font-size:16px } .info span { display:block; color:var(--dim); font-size:13px }
    .info em { font-style:normal; color:var(--blue) }
    .amt { text-align:right } .amt b { display:block; font-size:16px; font-variant-numeric:tabular-nums }
    .amt > span { display:block; color:var(--dim); font-size:13px }
    .chip { display:inline-block; margin-top:4px; font-size:12px; font-weight:600; padding:2px 8px; border-radius:6px }
    .chip.grey { background:var(--grey-bg); color:var(--sub) } .chip.green { background:var(--green-bg); color:var(--green) }
    .chip.blue { background:var(--blue-bg); color:var(--blue) } .chip.red { background:var(--red-bg); color:var(--red) }
    .empty { background:var(--card); border-radius:20px; padding:40px 20px; text-align:center; color:var(--dim); line-height:1.8 }
    .cta { position:fixed; left:0; right:0; bottom:0; padding:24px 16px calc(12px + env(safe-area-inset-bottom));
      background:linear-gradient(transparent, var(--bg) 40%) }
    button.primary, button.ghost, button.danger { display:block; width:100%; max-width:488px; margin:0 auto; border:0; border-radius:16px;
      padding:17px; font:inherit; font-size:17px; font-weight:700; cursor:pointer }
    button.primary { background:var(--blue); color:#fff }
    button.primary:disabled { background:var(--card); color:var(--dim); cursor:default; box-shadow:inset 0 0 0 1px var(--line) }
    button.ghost { background:var(--grey-bg); color:var(--sub) } button.danger { background:var(--red); color:#fff }
    #toast { position:fixed; left:50%; top:64px; transform:translateX(-50%); z-index:3; width:max-content; max-width:90%; padding:12px 18px;
      border-radius:14px; background:#191f28; color:#fff; font-size:14px; box-shadow:0 8px 24px rgba(0,0,0,.2) }
    #toast[hidden] { display:none }
    dialog.sheet { position:fixed; inset:auto 0 0 0; margin:0 auto; width:100%; max-width:520px; border:0; border-radius:24px 24px 0 0;
      padding:28px 22px calc(20px + env(safe-area-inset-bottom)); background:var(--card); color:var(--fg) }
    dialog.sheet::backdrop { background:rgba(0,0,0,.45) }
    .sheet h2 { font-size:22px; margin:0 0 8px } .sheet p { color:var(--sub); margin:0 0 16px; font-size:15px }
    .sheet ul { list-style:none; padding:14px 16px; margin:0 0 20px; background:var(--grey-bg); border-radius:14px }
    .sheet li { display:flex; justify-content:space-between; padding:4px 0; font-size:15px }
    .btns { display:flex; gap:8px } .btns button { margin:0 } .btns .ghost { flex:1 } .btns .primary, .btns .danger { flex:2 }
    """.trimIndent()

/** The buttons: each sends the control header, says what happened, and refreshes; refresh waits for a sheet. */
private val SCRIPT =
    """
    const REFUSALS = { 'trading is off': '자동 주문이 꺼져 있거나 멈춘 상태예요',
      'halted': '응답을 못 받은 주문 때문에 멈춘 상태예요', 'outside the order window': '지금은 주문 시간이 아니에요',
      'no sleeve in DRY_RUN': '연습 모드인 전략이 없어요' };
    let busy = false;
    function toast(text) { const t = document.getElementById('toast'); t.textContent = text; t.hidden = false; }
    async function send(path, done) {
      busy = true; toast('요청하는 중…');
      try {
        const r = await fetch(path, { method: 'POST', headers: { 'X-Tickguard-Control': '1' } });
        const text = (await r.text()).trim();
        toast(r.ok ? done : '안 됐어요 · ' + (REFUSALS[text.replace(/^refused: /, '')] || text));
      } catch (e) { toast('서버에 연결하지 못했어요'); }
      setTimeout(() => location.reload(), 2500);
    }
    function openSheet(id) { busy = true; document.getElementById(id).showModal(); }
    function closeSheet(id) { document.getElementById(id).close(); busy = false; }
    function load() { send('/sleeves/rebalance', '목록을 불러왔어요'); }
    function buy() { closeSheet('buy'); send('/sleeves/live', '주문을 넣었어요 · 체결을 확인하는 중이에요'); }
    function stopAll() { closeSheet('stop'); send('/sleeves/stop', '자동 주문을 멈췄어요'); }
    setInterval(() => { if (!busy) location.reload(); }, 15000);
    """.trimIndent()
