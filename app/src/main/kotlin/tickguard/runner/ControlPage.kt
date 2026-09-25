package tickguard.runner

import tickguard.observability.escapeHtml
import tickguard.orders.Order
import tickguard.time.SEOUL
import tickguard.trading.SleeveProposal
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Everything the control page shows, read at the moment it is asked for. */
internal data class ControlView(
    val trading: String,
    val halted: Boolean,
    val now: Instant,
    /** When the open order window closes; null while it is shut. */
    val windowEnd: Instant?,
    val proposedAt: Instant?,
    val proposals: List<SleeveProposal>,
    val lastRun: Pair<Instant, String>?,
    val orders: List<Order>,
    /** Order id to sleeve; an order without one is the person's own. */
    val tags: Map<String, String>,
)

/**
 * `/control`: the test's evening on one page, for a person on a phone. What
 * would be placed, what was, and three buttons: propose now, LIVE for this
 * window only, stop everything until restart. The buttons send a header a
 * form on another site cannot, so a page elsewhere cannot press them.
 */
internal fun renderControl(view: ControlView): String {
    val window =
        view.windowEnd?.let { "열림 · ${CLOCK.format(it)} 까지" } ?: "닫힘 · 미국 정규장 개장 10분 후부터 마감 1시간 전까지"
    return """
        |<!doctype html>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>tickguard 제어</title>
        |<style>
        |  :root { color-scheme: light dark; --ok:#1a7f37; --bad:#cf222e; --dim:#8b949e; --line:color-mix(in srgb, currentColor 18%, transparent) }
        |  body { margin:0 auto; max-width:760px; padding:1rem; font:15px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace }
        |  h1 { font-size:1rem; margin:0 0 .75rem } h2 { font-size:.85rem; color:var(--dim); margin:1.5rem 0 .5rem }
        |  .state { font-size:1.1rem } .ok { color:var(--ok) } .bad { color:var(--bad) } .dim { color:var(--dim) }
        |  .buttons { display:flex; flex-wrap:wrap; gap:.5rem; margin:1rem 0 }
        |  button { font:inherit; padding:.6rem 1rem; border-radius:8px; border:1px solid var(--line); background:transparent; color:inherit; cursor:pointer }
        |  button.live { border-color:var(--ok); color:var(--ok) } button.stop { border-color:var(--bad); color:var(--bad) }
        |  pre { white-space:pre-wrap; margin:0; padding:.75rem; border:1px solid var(--line); border-radius:8px }
        |  table { border-collapse:collapse; width:100%; font-size:.85rem } td, th { text-align:left; padding:.25rem .4rem; border-bottom:1px solid var(--line) }
        |  .scroll { overflow-x:auto }
        |  #result { min-height:1.5em }
        |</style>
        |<h1>tickguard 제어 · ${CLOCK.format(view.now)}</h1>
        |<div class="state ${if (view.halted) "bad" else ""}">${escapeHtml(view.trading)}</div>
        |<div class="dim">주문 시간대: ${escapeHtml(window)}</div>
        |<div class="buttons">
        |  <button onclick="send('/sleeves/rebalance')">제안 요청</button>
        |  <button class="live" onclick="live()">오늘 밤만 LIVE</button>
        |  <button class="stop" onclick="stop()">■ 긴급 중지</button>
        |</div>
        |<div id="result" class="dim"></div>
        |<h2>제안 ${view.proposedAt?.let { CLOCK.format(it) } ?: "없음"}</h2>
        |<pre>${escapeHtml(proposalText(view.proposals))}</pre>
        |<h2>마지막 실행 ${view.lastRun?.let { CLOCK.format(it.first) } ?: "없음"}</h2>
        |<pre>${escapeHtml(view.lastRun?.second ?: "-")}</pre>
        |<h2>테스트 주문 (${view.orders.size})</h2>
        |<div class="scroll">${ordersTable(view.orders, view.tags)}</div>
        |<script>
        |async function send(path) {
        |  const out = document.getElementById('result');
        |  out.textContent = '요청 중…';
        |  const r = await fetch(path, { method: 'POST', headers: { 'X-Tickguard-Control': '1' } });
        |  out.textContent = r.status + ' ' + (await r.text());
        |  setTimeout(() => location.reload(), 4000);
        |}
        |function live() {
        |  if (prompt('DRY_RUN 슬리브가 이번 주문 시간대 동안 LIVE로 실주문을 냅니다. 확인하려면 LIVE 입력') === 'LIVE') send('/sleeves/live');
        |}
        |function stop() {
        |  if (confirm('재시작 전까지 모든 자동 주문을 막습니다. 이미 나간 주문은 취소되지 않습니다.')) send('/sleeves/stop');
        |}
        |setTimeout(() => location.reload(), 30000);
        |</script>
        |
        """.trimMargin()
}

private fun proposalText(proposals: List<SleeveProposal>): String =
    proposals
        .joinToString("\n") { proposal ->
            val trades =
                proposal.trades.joinToString("") {
                    "\n  ${it.side} ${it.code} \$${it.value.format(2)}"
                }
            "${proposal.sleeve.name} · \$${proposal.value.format(2)}${trades.ifEmpty { "\n  거래 없음" }}"
        }.ifEmpty { "-" }

private fun ordersTable(
    orders: List<Order>,
    tags: Map<String, String>,
): String {
    if (orders.isEmpty()) return "<div class=\"dim\">없음</div>"
    val rows =
        orders.sortedByDescending { it.orderedAt }.joinToString("") { order ->
            val filled =
                order.execution.averageFilledPrice?.let {
                    "${order.execution.filledQuantity.toPlainString()} @ ${it.toPlainString()}"
                } ?: "-"
            val asked = order.orderAmount?.let { "\$${it.toPlainString()}" } ?: order.quantity.toPlainString()
            listOf(
                CLOCK.format(order.orderedAt),
                tags[order.orderId] ?: "개인",
                order.side,
                order.symbol,
                asked,
                order.status,
                filled,
            ).joinToString("", "<tr>", "</tr>") { "<td>${escapeHtml(it)}</td>" }
        }
    return "<table><tr><th>시각</th><th>슬리브</th><th>매매</th><th>종목</th><th>주문</th><th>상태</th><th>체결</th></tr>$rows</table>"
}

/** Times as a person in Seoul reads them. */
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm 'KST'").withZone(SEOUL)
