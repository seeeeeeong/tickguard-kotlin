package tickguard.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Conventions from CLAUDE.md that live in source text rather than bytecode,
 * which ArchUnit cannot see: comments, and where tests are.
 */
class ConventionsTest {
    @Test
    fun `constants say why they have their value`() {
        Conventions.constantsExplained(Konsist.scopeFromProduction())
    }

    @Test
    fun `tests mirror the file they test`() {
        Conventions.testsMirrorProduction(
            // This module's tests check rules, not a production file of their own.
            tests = Konsist.scopeFromTest() - Konsist.scopeFromModule("architecture"),
            production = Konsist.scopeFromProduction(),
        )
    }
}

internal object Conventions {
    private val UPPER_SNAKE = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")

    /**
     * A constant is usually derived from a constraint of the Toss API, and the
     * number alone does not say which. `PING_INTERVAL = 20.seconds` means
     * nothing without the 180s idle close next to it.
     */
    fun constantsExplained(scope: KoScope) {
        scope
            .properties()
            .filter { UPPER_SNAKE.matches(it.name) }
            .assertTrue(additionalMessage = "A constant needs a KDoc saying why it has this value.") { it.hasKDoc }
    }

    /**
     * `Keepalive.kt` is tested in `KeepaliveTest.kt`, in the same package.
     * A test named after nothing is a test nobody finds when the code changes.
     */
    fun testsMirrorProduction(
        tests: KoScope,
        production: KoScope,
    ) {
        val sources = production.files.map { "${it.packagee?.name}.${it.name}" }.toSet()

        tests.files
            .filter { it.name.endsWith("Test") }
            .assertTrue(additionalMessage = "No production file matches this test's package and name.") {
                val subject = it.name.removeSuffix("Test").removeSuffix("Integration")
                "${it.packagee?.name}.$subject" in sources
            }
    }
}
