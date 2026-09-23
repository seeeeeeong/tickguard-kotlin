package tickguard.testing

import kotlinx.coroutines.CancellationException

/**
 * What [block] threw, or null. For asserting on a failure from a suspend call.
 *
 * Not runCatching: that would also swallow the cancellation of the test itself.
 */
@Suppress("TooGenericExceptionCaught") // Catching everything is the point: the test asserts on what it was.
suspend fun failureOf(block: suspend () -> Any?): Throwable? =
    try {
        block()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failure
    }
