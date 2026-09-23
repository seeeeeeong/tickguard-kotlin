package tickguard.network

import java.io.IOException

/**
 * A failure's message, with the kind of network failure beneath it named.
 *
 * On the JVM the message alone can hide what happened: an UnknownHostException
 * says only the host's name, and a wrapped one says less. No DNS yet (the
 * router is still booting), a refused connection and a TLS failure call for
 * different responses from whoever is reading the status page, so the class of
 * the innermost I/O failure is appended, as the original appended undici's code.
 */
fun reasonOf(error: Throwable): String {
    val message = error.message ?: error.javaClass.simpleName
    val root = generateSequence(error) { it.cause }.last()
    val named = root is IOException && root !is StatusFailure && root.javaClass != IOException::class.java
    return if (named) "$message (${root.javaClass.simpleName})" else message
}
