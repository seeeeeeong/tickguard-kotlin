package tickguard.json

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrictJsonTest {
    @Test
    fun `rejects what JSON parse rejects`() {
        listOf("<html>", "{\"price\": abc}", "[1, two]", "{a: 1}", "[1,]", "", "PING", "1.", ".5", "01")
            .forEach { assertThat(StrictJson.parse(it)).describedAs(it).isNull() }
    }

    @Test
    fun `accepts every kind of JSON value`() {
        val parsed = StrictJson.parse("""{"s":"x","n":-1.5e3,"t":true,"f":false,"z":null,"a":[0,{}]}""")

        assertThat(parsed).isInstanceOf(JsonObject::class.java)
        assertThat((parsed as JsonObject)["n"]).isEqualTo(JsonPrimitive(-1.5e3).let { parsed["n"] })
    }

    @Test
    fun `accepts a bare value at the top level, as JSON parse does`() {
        assertThat(StrictJson.parse("\"pong\"")).isEqualTo(JsonPrimitive("pong"))
        assertThat(StrictJson.parse("42")).isNotNull()
    }
}
