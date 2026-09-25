package tickguard.stream

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DecimalTest {
    private fun d(text: String) = Decimal.parse(text, "v")

    @Test
    fun `holds a decimal price exactly, which a binary float cannot`() {
        // A 7% drawdown from a live TSLA print. The float answer is off in the
        // last places, which is enough to decide a boundary comparison wrongly.
        assertThat((376.35 * 0.93).toString()).isEqualTo("350.00550000000004")
        assertThat((d("376.35") * d("0.93")).toPlainString()).isEqualTo("350.0055")
    }

    @Test
    fun `treats scale as presentation, not data`() {
        // 339.20 and 339.2 are the same quantity; places are chosen by format.
        assertThat(d("339.20").toPlainString()).isEqualTo("339.2")
        assertThat(d("339.20").format(2)).isEqualTo("339.20")
        assertThat(d("339.20")).isEqualTo(d("339.2"))
    }

    @Test
    fun `holds large volumes exactly`() {
        assertThat(d("9007199254740993").toPlainString()).isEqualTo("9007199254740993")
    }

    @Test
    fun `names the field when the value is not a number`() {
        assertThatThrownBy { Decimal.parse("N/A", "price") }.isInstanceOf(NotANumberError::class.java)
        assertThatThrownBy { Decimal.parse("", "price") }.hasMessageContaining("price")
    }

    @Test
    fun `accepts exactly what big js accepts`() {
        // Checked against big.js 7.0.1 itself.
        val accepted = mapOf("1." to "1", ".5" to "0.5", "1e5" to "100000", "1E-7" to "0.0000001", "00.10" to "0.1")
        accepted.forEach { (text, plain) -> assertThat(d(text).toPlainString()).describedAs(text).isEqualTo(plain) }
        listOf("+1", " 1", "1 ", "0x10", "1_000", "", "-", "Infinity", "NaN")
            .forEach { text ->
                assertThatThrownBy { d(text) }.describedAs(text).isInstanceOf(NotANumberError::class.java)
            }
    }

    @Test
    fun `compares at the boundary where a float would decide wrong`() {
        val threshold = d("376.35") * d("0.93")

        assertThat(threshold.toPlainString()).isEqualTo("350.0055")
        // The float threshold is 350.00550000000004, so this tick compares the
        // other way round under floats than it does here.
        assertThat(d("350.0055") < threshold).isFalse()
        assertThat(350.0055 < 376.35 * 0.93).isTrue()
    }

    @Test
    fun `reports a change as a ratio`() {
        assertThat(changeRatio(d("100"), d("103")).toPlainString()).isEqualTo("0.03")
        assertThat(changeRatio(d("100"), d("93")).toPlainString()).isEqualTo("-0.07")
    }

    @Test
    fun `refuses a change ratio from zero rather than returning Infinity`() {
        assertThatThrownBy { changeRatio(d("0"), d("1")) }.isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `rounds half up, the convention prices are quoted in`() {
        assertThat((d("1") / d("8")).format(2)).isEqualTo("0.13")
        assertThat((d("1") / d("16")).format(2)).isEqualTo("0.06")
        assertThat(d("-0.125").format(2)).isEqualTo("-0.13")
    }

    @Test
    fun `divides to twenty places, as big js does`() {
        assertThat((d("2") / d("3")).toPlainString()).isEqualTo("0.66666666666666666667")
        assertThat((d("100") / d("7") * d("7")).toPlainString()).isEqualTo("100.00000000000000000003")
    }

    @Test
    fun `floors toward negative infinity, counting whole steps`() {
        assertThat((d("0.019") / d("0.02")).floor()).isEqualTo(0L)
        assertThat((d("0.02") / d("0.02")).floor()).isEqualTo(1L)
        assertThat(d("-0.5").floor()).isEqualTo(-1L)
    }

    @Test
    fun `keeps the sign of a negative value that rounds to zero, as big js does`() {
        assertThat(d("-0.04").format(1)).isEqualTo("-0.0")
        assertThat(d("-0").format(1)).isEqualTo("0.0")
        assertThat((d("1") - d("1")).format(1)).isEqualTo("0.0")
    }

    @Test
    fun `renders a tiny fractional quantity in plain notation`() {
        // A holding here is 0.02497 shares; smaller lots would render as 1E-7.
        assertThat(d("0.0000001").toPlainString()).isEqualTo("0.0000001")
        assertThat(d("0.02497").toString()).isEqualTo("0.02497")
        assertThat("${d("1e21")}").isEqualTo("1000000000000000000000")
    }

    @Test
    fun `round-trips through parse without losing a digit`() {
        for (value in listOf("376.35", "0.062555", "350.0055", "0.0000001", "123456789012345678901234.5")) {
            assertThat(d(d(value).toPlainString()).toPlainString()).isEqualTo(value)
        }
    }
}
