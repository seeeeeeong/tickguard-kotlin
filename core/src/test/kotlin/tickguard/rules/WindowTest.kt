package tickguard.rules

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.tick
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WindowTest {
    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    @Test
    fun `summarises the points inside the requested span`() {
        val store = WindowStore()

        store.record(tick("A", "100", "10", at(0)))
        store.record(tick("A", "110", "20", at(10)))
        store.record(tick("A", "90", "30", at(20)))
        store.record(tick("A", "105", "40", at(30)))

        val snapshot = store.snapshot("A", 60.seconds)!!

        assertThat(listOf(snapshot.open, snapshot.last, snapshot.high, snapshot.low, snapshot.volume).map { "$it" })
            .containsExactly("100", "105", "110", "90", "100")
        assertThat(snapshot.count).isEqualTo(4)
        assertThat(snapshot.span).isEqualTo(30.seconds)
    }

    @Test
    fun `excludes points older than the span`() {
        val store = WindowStore()

        store.record(tick("A", "100", at = at(0)))
        store.record(tick("A", "200", at = at(60)))
        store.record(tick("A", "210", at = at(70)))

        val snapshot = store.snapshot("A", 30.seconds)!!

        assertThat(snapshot.count).isEqualTo(2)
        assertThat("${snapshot.open}").isEqualTo("200")
    }

    @Test
    fun `drops points past the retention window`() {
        val store = WindowStore(retention = 10.seconds)

        store.record(tick("A", "100", at = at(0)))
        store.record(tick("A", "110", at = at(5)))
        store.record(tick("A", "120", at = at(30)))

        assertThat(store.stats().points).isEqualTo(1)
        assertThat(store.stats().evicted).isEqualTo(2)
    }

    @Test
    fun `bounds points per symbol so a burst cannot grow without limit`() {
        val store = WindowStore(maxPoints = 50)

        repeat(5_000) { store.record(tick("A", "100", at = Instant.ofEpochMilli(it.toLong()))) }

        assertThat(store.stats().points).isEqualTo(50)
    }

    @Test
    fun `reports the span it actually covered, not the one requested`() {
        // The count bound bit, so five minutes of history is not there to give.
        val store = WindowStore(maxPoints = 3)

        repeat(100) { store.record(tick("A", "100", at = at(it.toLong()))) }

        assertThat(store.snapshot("A", 5.minutes)!!.span).isEqualTo(2.seconds)
    }

    @Test
    fun `keeps symbols apart`() {
        val store = WindowStore()

        store.record(tick("A", "100", at = at(0)))
        store.record(tick("B", "900", at = at(0)))

        assertThat("${store.snapshot("A", 1.seconds)!!.last}").isEqualTo("100")
        assertThat("${store.snapshot("B", 1.seconds)!!.last}").isEqualTo("900")
        assertThat(store.stats().symbols).isEqualTo(2)
    }

    @Test
    fun `has nothing to say about a symbol it has not seen`() {
        assertThat(WindowStore().snapshot("NOPE", 1.seconds)).isNull()
    }
}
