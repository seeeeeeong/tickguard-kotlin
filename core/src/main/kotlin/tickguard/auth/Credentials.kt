package tickguard.auth

import java.time.Instant

/**
 * A data class would print the secret in every log line and exception message
 * that happens to include it. Neither class here does.
 */
class TossCredentials(
    val clientId: String,
    val clientSecret: String,
) {
    override fun toString(): String = "TossCredentials(clientId=$clientId, clientSecret=***)"
}

class IssuedToken(
    val accessToken: String,
    /** Absolute, so callers never have to track when it was issued. */
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedToken(accessToken=***, expiresAt=$expiresAt)"
}

/** The one place a token is requested. TokenManager owns when; this owns how. */
fun interface TokenIssuer {
    suspend fun issue(): IssuedToken
}

/**
 * Credentials are read from the environment only, never from a checked-in file,
 * because these authenticate against a live brokerage account.
 */
class MissingEnvError(
    key: String,
) : IllegalStateException("$key is not set. Copy .env.example to .env and fill it in.")

private fun Map<String, String>.require(key: String): String {
    val value = this[key]
    if (value.isNullOrEmpty()) throw MissingEnvError(key)
    return value
}

/** The environment is a parameter so the whole config path is testable. */
fun loadCredentials(env: Map<String, String> = System.getenv()): TossCredentials =
    TossCredentials(
        clientId = env.require("TOSS_CLIENT_ID"),
        clientSecret = env.require("TOSS_CLIENT_SECRET"),
    )
