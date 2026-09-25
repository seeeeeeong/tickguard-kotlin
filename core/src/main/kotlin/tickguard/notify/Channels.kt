package tickguard.notify

import tickguard.rules.Signal
import tickguard.time.kstTime

/**
 * Without a webhook the process still has to say what it would have sent, or a
 * misconfigured deployment looks identical to a quiet market. So the console
 * is a channel of its own, not a placeholder.
 */
class ConsoleChannel(
    /** Injected so the library never reaches for a console itself. */
    private val write: (String) -> Unit,
) : Channel {
    override val name = "console"

    override suspend fun send(notification: Notification) = write(notification.text)
}

fun formatSignal(signal: Signal): String =
    "[${kstTime(signal.firedAt)}] ${signal.title}\n${signal.detail}\n· ${signal.ruleId}"

/**
 * One notification for a whole group. The count goes in the first line because
 * that is what a phone shows before it is opened — "8 symbols" and "1 symbol"
 * are different situations and the difference should not need a tap.
 *
 * This is the only place a group becomes text. Formatting it here and again
 * in a channel is how the original's first live run produced two timestamps
 * on one line.
 */
fun toNotification(group: SignalGroup) = Notification(group.key, formatGroup(group), group.signals)

fun formatGroup(group: SignalGroup): String {
    val first = group.signals.first()
    if (group.signals.size == 1) return formatSignal(first)

    val lines = group.signals.joinToString("\n") { "· ${it.title}\n  ${it.detail}" }
    return "[${kstTime(first.firedAt)}] ${group.key} — ${group.signals.size}건\n$lines"
}
