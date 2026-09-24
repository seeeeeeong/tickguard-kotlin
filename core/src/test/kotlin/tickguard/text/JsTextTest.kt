package tickguard.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsTextTest {
    @Test
    fun `trims what trim() trims, including a no-break space`() {
        assertThat("  Amazon ﻿".jsTrim()).isEqualTo("Amazon")
    }

    @Test
    fun `leaves alone what trim() leaves alone`() {
        // The ASCII separator controls: Kotlin's trim() strips them, JavaScript's does not.
        assertThat("\u001FAmazon".jsTrim()).isEqualTo("\u001FAmazon")
    }

    @Test
    fun `collapses runs of any JavaScript whitespace`() {
        assertThat("FTC  sues　Amazon".replace(JS_SPACES, " ")).isEqualTo("FTC sues Amazon")
    }

    @Test
    fun `rounds the binary value as toFixed() does, not the shortest decimal`() {
        // Reference values from node: (0.725).toFixed(2) and so on.
        assertThat(0.725.toFixed(2)).isEqualTo("0.72")
        assertThat(1.005.toFixed(2)).isEqualTo("1.00")
        assertThat(0.125.toFixed(2)).isEqualTo("0.13")
        assertThat(2.5.toFixed(0)).isEqualTo("3")
    }

    @Test
    fun `keeps the sign of a negative that rounds to zero, and drops negative zero's`() {
        assertThat((-0.0001).toFixed(3)).isEqualTo("-0.000")
        assertThat((-0.0).toFixed(2)).isEqualTo("0.00")
    }
}
