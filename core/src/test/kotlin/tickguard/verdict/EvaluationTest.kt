package tickguard.verdict

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EvaluationTest {
    @Nested
    inner class ParseCsv {
        @Test
        fun `reads what spreadsheets write - quoted commas, newlines and doubled quotes`() {
            val csv = "a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n"

            assertThat(parseCsv(csv)).containsExactly(listOf("a", "b", "c"), listOf("x, y", "say \"hi\"", "two\nlines"))
        }

        @Test
        fun `drops the byte-order mark written for Excel`() {
            assertThat(parseCsv("key,title\nk1,t1")).containsExactly(listOf("key", "title"), listOf("k1", "t1"))
        }

        @Test
        fun `round-trips headlines that need quoting`() {
            val rows = listOf(listOf("key", "title"), listOf("k", "AT&T \"beats\", then slips\nafter hours"))

            assertThat(toCsv(rows)).startsWith("﻿")
            assertThat(parseCsv(toCsv(rows))).isEqualTo(rows)
        }

        @Test
        fun `refuses an unterminated quote rather than guessing`() {
            assertThatThrownBy { parseCsv("a,\"broken\n") }.hasMessageContaining("malformed")
        }
    }

    @Nested
    inner class ParseLabels {
        private val header = "key,code,publisher,published_kst,title,relevant,direction,alert\n"

        @Test
        fun `skips rows not labelled yet, and accepts any case`() {
            val labels = parseLabels("${header}k1,AMZN,CNBC,t,FTC sues,Y,Down,y\nk2,AMZN,MB,t,Fund buys,,,\n")

            assertThat(labels).containsExactly(Label("k1", relevant = true, direction = Direction.DOWN, alert = true))
        }

        @Test
        fun `names every malformed line instead of scoring half a file`() {
            assertThatThrownBy { parseLabels("${header}k1,A,P,t,x,maybe,down,y\nk2,A,P,t,x,y,sideways,n\n") }
                .hasMessageMatching("(?s).*line 2: relevant.*\n.*line 3: direction.*")
        }
    }

    @Nested
    inner class Evaluate {
        private fun label(
            key: String,
            relevant: Boolean,
            alert: Boolean,
            direction: Direction = Direction.DOWN,
        ) = Label(key, relevant, direction, alert)

        private fun verdict(
            relevant: Boolean,
            impact: Double,
            direction: Direction = Direction.DOWN,
        ) = Verdict(relevant, direction, impact, "s")

        private val labels =
            listOf(
                label("ftc", relevant = true, alert = true),
                label("sale", relevant = true, alert = false),
                label("fund", relevant = false, alert = false),
                label("recap", relevant = false, alert = false),
                label("missed", relevant = true, alert = true),
                label("unjudged", relevant = true, alert = true),
            )
        private val verdicts =
            mapOf(
                "ftc" to verdict(true, 0.72),
                "sale" to verdict(true, 0.4, Direction.UP),
                "fund" to verdict(false, 0.0),
                "recap" to verdict(true, 0.55),
                "missed" to verdict(false, 0.1),
            )

        @Test
        fun `separates noise called news from news called noise`() {
            val report = evaluate(labels, verdicts)

            assertThat(report.scored).isEqualTo(5)
            assertThat(report.missing).isEqualTo(1)
            assertThat(report.relevance).isEqualTo(Relevance(3.0 / 5, 2, 1, 1, 1))
        }

        @Test
        fun `compares direction only where both called the story relevant`() {
            assertThat(evaluate(labels, verdicts).direction).isEqualTo(DirectionAgreement(agreed = 1, compared = 2))
        }

        @Test
        fun `scores alerting per threshold, so the threshold can be chosen from data`() {
            val alerts = evaluate(labels, verdicts).alerts.associateBy { it.threshold }
            val atHalf = alerts.getValue(0.5)
            val atSeven = alerts.getValue(0.7)
            val atEight = alerts.getValue(0.8)

            assertThat(
                atHalf,
            ).isEqualTo(AlertScore(0.5, fired = 2, wanted = 2, hits = 1, precision = 0.5, recall = 0.5))
            assertThat(
                atSeven,
            ).isEqualTo(AlertScore(0.7, fired = 1, wanted = 2, hits = 1, precision = 1.0, recall = 0.5))
            // Nothing fired: there is no precision to report, not a precision of zero.
            assertThat(
                atEight,
            ).isEqualTo(AlertScore(0.8, fired = 0, wanted = 2, hits = 0, precision = null, recall = 0.0))
        }

        @Test
        fun `formats a report a person can read in a terminal`() {
            val text = formatReport(evaluate(labels, verdicts), "deepseek-flash")

            assertThat(text).contains("채점 5건 (판정 없음 1건)").contains("관련 여부 정확도  60%")
            assertThat(text).containsPattern("≥ 0\\.7\\s+1\\s+2\\s+1\\s+100%\\s+50%")
        }
    }
}
