package tickguard.notify

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NotifierTest {
    private val notification = Notification("drawdown-7pct", "[09:14:00] 005930 평단 대비 -7.2%")

    /** Fails a set number of times before succeeding, recording when each attempt came. */
    private class Flaky(
        private val scope: TestScope,
        private val failures: Int,
        override val name: String = "flaky",
    ) : Channel {
        val attemptsAt = mutableListOf<Long>()

        override suspend fun send(notification: Notification) {
            attemptsAt += scope.testScheduler.currentTime
            check(attemptsAt.size > failures) { "down" }
        }
    }

    private fun TestScope.notifier(
        vararg channels: Channel,
        maxAttempts: Int = 3,
        baseDelay: Duration = 500.milliseconds,
        onGaveUp: (Notification, String, Exception) -> Unit = { _, _, _ -> },
    ) = Notifier(channels.toList(), backgroundScope, maxAttempts, baseDelay, onGaveUp = onGaveUp)

    @Test
    fun `returns before delivery, so the caller is never blocked on a round trip`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val slow =
                object : Channel {
                    override val name = "slow"

                    override suspend fun send(notification: Notification) = release.await()
                }
            val notifier = notifier(slow)

            notifier.notify(notification)
            runCurrent()

            assertThat(notifier.stats().pending).isEqualTo(1)
            assertThat(notifier.stats().delivered).isZero()
            release.complete(Unit)
        }

    /** Refuses every send the same way, recording when each attempt came. */
    private class Refusing(
        private val scope: TestScope,
        private val refusal: Exception,
    ) : Channel {
        override val name = "refusing"
        val attemptsAt = mutableListOf<Long>()

        override suspend fun send(notification: Notification) {
            attemptsAt += scope.testScheduler.currentTime
            throw refusal
        }
    }

    private class Refusal(
        override val retryable: Boolean,
        override val retryAfter: Duration? = null,
    ) : Exception("refused"),
        DeliveryFailure

    @Test
    fun `gives up at once on a refusal that a retry would meet again`() =
        runTest {
            val gaveUp = mutableListOf<String>()
            val channel = Refusing(this, Refusal(retryable = false))
            val notifier = notifier(channel, onGaveUp = { _, name, _ -> gaveUp += name })

            notifier.notify(notification)
            notifier.drain()

            assertThat(channel.attemptsAt).hasSize(1)
            assertThat(gaveUp).containsExactly("refusing")
            assertThat(notifier.stats().retried).isZero()
        }

    @Test
    fun `waits as long as a rate limit asks before trying again`() =
        runTest {
            val channel = Refusing(this, Refusal(retryable = true, retryAfter = 7.seconds))
            val notifier = notifier(channel, maxAttempts = 2)

            notifier.notify(notification)
            notifier.drain()

            assertThat(channel.attemptsAt).containsExactly(0L, 7_000L)
        }

    @Test
    fun `caps a Retry-After too long for an alert to still be worth sending`() =
        runTest {
            val channel = Refusing(this, Refusal(retryable = true, retryAfter = 1.hours))
            val notifier = notifier(channel, maxAttempts = 2)

            notifier.notify(notification)
            notifier.drain()

            assertThat(channel.attemptsAt).containsExactly(0L, 60_000L)
        }

    @Test
    fun `retries a failing channel before giving up`() =
        runTest {
            val flaky = Flaky(this, failures = 2)
            val notifier = notifier(flaky)

            notifier.notify(notification)
            notifier.drain()

            assertThat(flaky.attemptsAt).hasSize(3)
            assertThat(notifier.stats().delivered).isEqualTo(1)
            assertThat(notifier.stats().retried).isEqualTo(2)
        }

    @Test
    fun `gives up after the attempt limit without throwing`() =
        runTest {
            val gaveUp = mutableListOf<String>()
            val flaky = Flaky(this, failures = 99)
            val notifier = notifier(flaky, maxAttempts = 2, onGaveUp = { _, channel, _ -> gaveUp += channel })

            notifier.notify(notification)
            notifier.drain()

            assertThat(flaky.attemptsAt).hasSize(2)
            assertThat(notifier.stats().abandoned).isEqualTo(1)
            assertThat(gaveUp).containsExactly("flaky")
        }

    @Test
    fun `keeps one channel working when another is down`() =
        runTest {
            val lines = mutableListOf<String>()
            val notifier = notifier(Flaky(this, 99, "dead"), ConsoleChannel { lines += it }, maxAttempts = 1)

            notifier.notify(notification)
            notifier.drain()

            // Slack being down is not a reason to lose the alert entirely.
            assertThat(lines).containsExactly(notification.text)
            assertThat(notifier.stats().delivered).isEqualTo(1)
            assertThat(notifier.stats().abandoned).isEqualTo(1)
        }

    @Test
    fun `backs off between attempts`() =
        runTest {
            val flaky = Flaky(this, failures = 2)
            val notifier = notifier(flaky, baseDelay = 100.milliseconds)

            notifier.notify(notification)
            notifier.drain()

            val start = flaky.attemptsAt.first()
            assertThat(flaky.attemptsAt.map { it - start }).containsExactly(0L, 100L, 300L)
        }

    @Test
    fun `drains deliveries queued while draining`() =
        runTest {
            val notifier = notifier(Flaky(this, failures = 1))

            notifier.notify(notification)
            notifier.notify(notification)
            notifier.drain()

            assertThat(notifier.stats().pending).isZero()
        }
}
