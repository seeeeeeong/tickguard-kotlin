package tickguard.observability

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class StatusPanel(
    val label: String,
    val value: String,
    /** Drives the colour. Null means neutral. */
    val ok: Boolean? = null,
)

/** What the status page shows, read at the moment it is asked for. */
fun interface StatusSource {
    fun panels(): List<StatusPanel>
}

/** The page's stamp, as `toISOString()` wrote it: always milliseconds, always UTC. */
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * `/` is for a person on a phone who wants to know whether the thing is alive
 * without installing anything — which, for a process whose whole job is to
 * notice silence, is the difference between trusting it and checking the app
 * anyway.
 */
fun renderStatusPage(
    panels: List<StatusPanel>,
    now: Instant = Instant.now(),
): String {
    val cards = panels.joinToString("") { renderPanel(it) }
    return """
        |<!doctype html>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>tickguard</title>
        |<style>
        |  :root { color-scheme: light dark; --ok:#1a7f37; --bad:#cf222e; --dim:#8b949e }
        |  body { margin:0; padding:1.5rem; font:15px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace }
        |  h1 { font-size:1rem; font-weight:600; margin:0 0 1rem; color:var(--dim) }
        |  .grid { display:grid; gap:.75rem; grid-template-columns:repeat(auto-fill,minmax(210px,1fr)) }
        |  .card { border:1px solid color-mix(in srgb, currentColor 18%, transparent); border-radius:8px; padding:.75rem .9rem }
        |  .label { color:var(--dim); font-size:.8rem }
        |  .value { font-size:1.35rem; margin-top:.2rem; font-variant-numeric:tabular-nums }
        |  .ok { color:var(--ok) } .bad { color:var(--bad) }
        |</style>
        |<h1>tickguard · ${escapeHtml(STAMP.format(now))}</h1>
        |<div class="grid">$cards</div>
        |
        """.trimMargin()
}

private fun renderPanel(panel: StatusPanel): String {
    val tone =
        when (panel.ok) {
            null -> ""
            true -> " ok"
            false -> " bad"
        }
    return "<div class=\"card\"><div class=\"label\">${escapeHtml(panel.label)}</div>" +
        "<div class=\"value$tone\">${escapeHtml(panel.value)}</div></div>"
}

/** Panel text comes from symbols and error messages, so it is never trusted. */
private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
