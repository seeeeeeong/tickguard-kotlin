package tickguard.gateway

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FramesTest {
    @Test
    fun `reads a subscription ack with its rejected entries`() {
        val frame =
            parseFrame(
                """{"type":"subscriptions","id":"req-1","subscribed":["trade:us:AAPL"],""" +
                    """"rejected":[{"target":"trade:us:NOPE","code":"stock-not-found",""" +
                    """"message":"해당 종목을 찾을 수 없습니다."}]}""",
            )

        assertThat(frame).isEqualTo(
            ServerFrame.Subscriptions(
                id = "req-1",
                subscribed = listOf("trade:us:AAPL"),
                rejected = listOf(RejectedTopic("trade:us:NOPE", "stock-not-found", "해당 종목을 찾을 수 없습니다.")),
            ),
        )
    }

    @Test
    fun `leaves id null when the declaration carried none`() {
        val frame = parseFrame("""{"type":"subscriptions","subscribed":[],"rejected":[]}""")

        assertThat((frame as ServerFrame.Subscriptions).id).isNull()
    }

    @Test
    fun `drops malformed rejected entries but keeps the ack usable`() {
        val frame =
            parseFrame(
                """{"type":"subscriptions","subscribed":["trade:kr:005930"],""" +
                    """"rejected":[{"code":"stock-not-found"},null]}""",
            )

        assertThat(frame).isEqualTo(ServerFrame.Subscriptions(null, listOf("trade:kr:005930"), emptyList()))
    }

    @Test
    fun `keeps message data undecoded for the channel to decode`() {
        val frame = parseFrame("""{"type":"message","topic":"trade:us:AAPL","data":{"price":"338.98"}}""")

        assertThat(frame).isEqualTo(
            ServerFrame.Message("trade:us:AAPL", buildJsonObject { put("price", JsonPrimitive("338.98")) }),
        )
    }

    @Test
    fun `surfaces server-shutdown as an ordinary error frame`() {
        val frame = parseFrame("""{"type":"error","error":{"code":"$SERVER_SHUTDOWN","message":"bye"}}""")

        assertThat(frame).isEqualTo(ServerFrame.Error(SERVER_SHUTDOWN, "bye", null))
    }

    @Test
    fun `reads a pong`() {
        assertThat(parseFrame("""{"type":"pong"}""")).isEqualTo(ServerFrame.Pong)
    }

    // A socket carrying live quotes must not die because one frame was odd.
    @ParameterizedTest
    @ValueSource(
        strings = [
            "not json at all",
            "[]",
            """{"type":"something-new","payload":1}""",
            """{"type":"message","data":{}}""",
            """{"type":"error","error":{"message":"x"}}""",
            """{"type":"subscriptions","subscribed":"x","rejected":[]}""",
            """{"type":"subscriptions","subscribed":[1],"rejected":[]}""",
        ],
    )
    fun `returns an unknown frame rather than throwing`(raw: String) {
        assertThat(parseFrame(raw)).isEqualTo(ServerFrame.Unknown(raw))
    }
}
