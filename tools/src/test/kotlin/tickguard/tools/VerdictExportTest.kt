package tickguard.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VerdictExportTest {
    private fun row(
        key: String,
        relevant: Boolean,
    ) = ExportRow(key, "AMZN", "CNBC", key, Instant.EPOCH, relevant)

    @Test
    fun `puts every relevant story in before any filler, the same way on every run`() {
        val judged = listOf(row("a", false), row("b", true), row("c", false), row("d", true), row("e", false))

        val chosen = choose(judged, 3)

        assertThat(chosen.map { it.key }).contains("b", "d").hasSize(3)
        assertThat(choose(judged.reversed(), 3)).isEqualTo(chosen)
    }

    @Test
    fun `refuses to overwrite a file that already holds a label`() {
        val header = "key,code,publisher,published_kst,title,title_ko,relevant,direction,alert\n"

        assertThat(holdsLabels("${header}k1,A,P,t,x,,,,\n")).isFalse()
        assertThat(holdsLabels("${header}k1,A,P,t,x,,y,,\n")).isTrue()
    }
}
