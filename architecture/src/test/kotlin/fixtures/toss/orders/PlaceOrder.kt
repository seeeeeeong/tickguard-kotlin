package fixtures.toss.orders

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaceOrder {
    fun request(): Request =
        Request
            .Builder()
            .url("https://example.invalid/api/v1/orders")
            .post("{}".toRequestBody())
            .build()
}
