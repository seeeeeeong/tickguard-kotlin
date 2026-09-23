package tickguard.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope

/**
 * A scope whose children may fail without failing the test, cancelled when the
 * test ends. For work that is expected to fail, or that production runs in a
 * supervised scope of its own.
 */
fun TestScope.supervisedScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
