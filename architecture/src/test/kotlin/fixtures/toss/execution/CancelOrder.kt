package fixtures.toss.execution

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CancelOrder {
    fun request(): Request =
        Request
            .Builder()
            .url("https://example.invalid/api/v1/orders/x/cancel")
            .method("POST", "{}".toRequestBody())
            .build()
}
