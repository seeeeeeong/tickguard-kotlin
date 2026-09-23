package tickguard.testing

import kotlinx.serialization.json.JsonElement
import tickguard.json.StrictJson
import tickguard.rest.RateLimiter
import tickguard.rest.RequestSpec
import tickguard.rest.RestClient

/**
 * Answers each call with the next body in turn, repeating the last. A body that
 * is an exception is thrown instead. Records what was asked.
 */
class StubRest(
    private vararg val answers: Any,
) : RestClient {
    override val limiter = RateLimiter(sleep = {})
    val asked = mutableListOf<RequestSpec>()

    override suspend fun get(spec: RequestSpec): JsonElement {
        val answer = answers[minOf(asked.size, answers.size - 1)]
        asked += spec
        if (answer is Exception) throw answer
        return StrictJson.parse(answer.toString()) ?: error("fixture is not JSON: $answer")
    }
}
