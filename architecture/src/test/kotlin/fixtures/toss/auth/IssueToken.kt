package fixtures.toss.auth

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class IssueToken {
    fun request(): Request =
        Request
            .Builder()
            .url("https://example.invalid/oauth2/token")
            .post("grant_type=client_credentials".toRequestBody())
            .build()
}
