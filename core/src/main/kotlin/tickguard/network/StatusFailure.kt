package tickguard.network

/**
 * A failure that carries the HTTP status the server answered with.
 *
 * The token endpoint, the socket handshake and every REST call can each refuse
 * an unregistered source IP with 403, and what follows depends on the status
 * rather than on which of them said it.
 */
interface StatusFailure {
    val status: Int
}

/**
 * A 403 from anything on the path to Toss means the source IP is not on the
 * allow list. The docs say the list covers REST and WebSocket alike and do not
 * say whether token issuance is included, so the status is what is read.
 */
fun isSourceIpRejected(error: Throwable): Boolean = (error as? StatusFailure)?.status == 403

/**
 * How much of a refusal's body an error message quotes. Enough for the reason a
 * server gives, short of an HTML error page that would bury the log line.
 */
const val ERROR_BODY_EXCERPT = 200
