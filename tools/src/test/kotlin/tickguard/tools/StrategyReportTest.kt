package tickguard.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.trading.Bar
import tickguard.trading.CostModel
import java.time.LocalDate

class StrategyReportTest {
    private fun d(text: String) = Decimal.parse(text, "test")

    private fun rising(
        code: String,
        days: Int,
    ) = (0 until days).map {
        val price = d("${100 + it}")
        Bar(code, LocalDate.parse("2020-01-01").plusDays(it.toLong()), price, price, price, price, d("1"))
    }

    @Test
    fun `reads costs as three fractions, and refuses anything else`() {
        assertThat(parseCosts("0.001, 0.0018,0.0005")).isEqualTo(CostModel(d("0.001"), d("0.0018"), d("0.0005")))
        assertThat(parseCosts("0.001,0.0018")).isNull()
        assertThat(parseCosts("0.1%,0,0")).isNull()
    }

    @Test
    fun `reports every candidate over the whole span and each half, and warns about survivors`() {
        val text =
            report(
                mapOf("A" to rising("A", 300)),
                CostModel(Decimal.ZERO, Decimal.ZERO, Decimal.ZERO),
                d("100000"),
                candidates(),
            )

        assertThat(text).contains("생존 편향")
        assertThat(text.lines().filter { it.startsWith("buy-and-hold") }.map { it.split(Regex("\\s+"))[1] })
            .containsExactly("전체", "전반", "후반")
        assertThat(text).contains("trend-sma200").contains("momentum-126d-top2")
    }
}
