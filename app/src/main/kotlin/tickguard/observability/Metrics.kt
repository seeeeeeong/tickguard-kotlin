package tickguard.observability

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/**
 * Every number here already lives in a `stats()` on the module that owns it,
 * as it did in the original. Meters are registered to read that source when
 * scraped, never to hold a copy, so an exported value cannot drift from what
 * the module itself reports.
 *
 * Names follow the original's: a counter `tickguard.ticks` is scraped as
 * `tickguard_ticks_total`, a gauge keeps its name.
 */
class Metrics(
    private val registry: MeterRegistry,
) {
    fun counter(
        name: String,
        help: String,
        read: () -> Number,
    ) {
        FunctionCounter
            .builder(name, read) { it().toDouble() }
            .description(help)
            .register(registry)
    }

    fun gauge(
        name: String,
        help: String,
        read: () -> Number,
    ) {
        Gauge
            .builder(name, read) { it().toDouble() }
            .description(help)
            .register(registry)
    }

    /**
     * One labelled series per field of a stats map, which is the shape they all
     * have. The fields are read once, at registration, to name the series;
     * after that each is read from the source on scrape.
     */
    fun fromStats(
        name: String,
        help: String,
        counter: Boolean,
        labelName: String = "kind",
        read: () -> Map<String, Number>,
    ) {
        for (field in read().keys) {
            val value = { read()[field] ?: 0 }
            if (counter) {
                FunctionCounter
                    .builder(name, value) { it().toDouble() }
                    .description(help)
                    .tag(labelName, field)
                    .register(registry)
            } else {
                Gauge
                    .builder(name, value) { it().toDouble() }
                    .description(help)
                    .tag(labelName, field)
                    .register(registry)
            }
        }
    }
}
