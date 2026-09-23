package tickguard.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = Metrics(registry, owner = this)

    @Test
    fun `reads the source on every scrape, so a value cannot go stale`() {
        var depth = 1
        metrics.gauge("tickguard.queue", "Queue depth.") { depth }

        assertThat(registry.get("tickguard.queue").gauge().value()).isEqualTo(1.0)
        depth = 7
        assertThat(registry.get("tickguard.queue").gauge().value()).isEqualTo(7.0)
    }

    @Test
    fun `keeps reading after a garbage collection, when nothing else holds the reader`() {
        metrics.gauge("tickguard.held", "Held only by the meter.") { 3 }

        repeat(3) { System.gc() }

        assertThat(registry.get("tickguard.held").gauge().value()).isEqualTo(3.0)
    }

    @Test
    fun `counts from the module's own counter`() {
        var ticks = 41L
        metrics.counter("tickguard.ticks", "Decoded trades.") { ticks }
        ticks += 1

        assertThat(registry.get("tickguard.ticks").functionCounter().count()).isEqualTo(42.0)
    }

    @Test
    fun `turns a stats map into one labelled series per field`() {
        var stats = mapOf("quotes" to 3, "orders" to 0)
        metrics.fromStats("tickguard.inbox.queued", "Frames waiting.", counter = false) { stats }
        stats = mapOf("quotes" to 5, "orders" to 1)

        val byKind = registry.get("tickguard.inbox.queued").gauges().associate { it.id.getTag("kind") to it.value() }
        assertThat(byKind).containsExactlyInAnyOrderEntriesOf(mapOf("quotes" to 5.0, "orders" to 1.0))
    }
}
