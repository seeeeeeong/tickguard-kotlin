package tickguard.auth

import tickguard.network.StatusFailure

/**
 * Both failures below are configuration mistakes, not transient faults, so the
 * messages name the fix. Retrying either one without changing something will
 * fail the same way.
 */
class TokenIssueError(
    override val status: Int,
    message: String,
) : RuntimeException(message),
    StatusFailure {
    companion object {
        fun fromResponse(
            status: Int,
            body: String,
        ): TokenIssueError =
            when (status) {
                401 -> {
                    TokenIssueError(
                        status,
                        "Toss rejected the client credentials. Check TOSS_CLIENT_ID and " +
                            "TOSS_CLIENT_SECRET against WTS > Settings > Open API.",
                    )
                }

                403 -> {
                    TokenIssueError(
                        status,
                        "Toss refused this source IP. Register it under WTS > Settings > Open API > " +
                            "allowed IPs. The same list applies to REST and WebSocket.",
                    )
                }

                else -> {
                    TokenIssueError(status, "Toss returned $status while issuing a token: $body")
                }
            }
    }
}

class MalformedTokenResponseError(
    detail: String,
) : RuntimeException("Token response was not in the expected shape: $detail")
