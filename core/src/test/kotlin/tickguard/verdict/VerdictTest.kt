package tickguard.verdict

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private val valid =
    mapOf<String, JsonElement>(
        "relevant" to JsonPrimitive(true),
        "direction" to JsonPrimitive("down"),
        "impact" to JsonPrimitive(0.72),
        "summary" to JsonPrimitive(" FTC가 아마존을 제소했다. "),
    )

private fun with(
    key: String,
    value: JsonElement,
) = JsonObject(valid + (key to value))

class VerdictTest {
    @Test
    fun `accepts a well-formed answer and trims the summary`() {
        assertThat(parseVerdict(JsonObject(valid)))
            .isEqualTo(Verdict(relevant = true, direction = Direction.DOWN, impact = 0.72, summary = "FTC가 아마존을 제소했다."))
    }

    @ParameterizedTest
    @MethodSource("garbage")
    fun `refuses an answer that would reach a threshold comparison as garbage`(
        value: JsonElement,
        message: String,
    ) {
        assertThatThrownBy {
            parseVerdict(
                value,
            )
        }.isInstanceOf(VerdictFormatError::class.java).hasMessageContaining(message)
    }

    @Test
    fun `caps a runaway summary`() {
        assertThat(parseVerdict(with("summary", JsonPrimitive("가".repeat(500)))).summary).hasSize(200)
    }

    @Test
    fun `quotes a bad value as JSON, as the original's message did`() {
        assertThatThrownBy { parseVerdict(with("direction", JsonPrimitive("sideways"))) }
            .hasMessage("direction \"sideways\" is not up, down or neutral")
        assertThatThrownBy { parseVerdict(JsonObject(valid - "impact")) }
            .hasMessage("impact undefined is not a number from 0 to 1")
    }

    companion object {
        @JvmStatic
        fun garbage() =
            listOf(
                Arguments.of(with("relevant", JsonPrimitive("yes")), "relevant"),
                Arguments.of(with("direction", JsonPrimitive("sideways")), "direction"),
                Arguments.of(with("impact", JsonPrimitive("high")), "impact"),
                Arguments.of(with("impact", JsonPrimitive(7)), "impact"),
                Arguments.of(with("summary", JsonPrimitive("  ")), "summary"),
                Arguments.of(JsonPrimitive("{\"relevant\": true}"), "not an object"),
            )
    }
}
