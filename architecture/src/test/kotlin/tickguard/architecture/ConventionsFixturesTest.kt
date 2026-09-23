package tickguard.architecture

import com.lemonappdev.konsist.api.Konsist
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Each convention, against source files that break it and files that keep it. */
class ConventionsFixturesTest {
    private fun scope(dir: String) = Konsist.scopeFromDirectory("architecture/src/test/resources/konsist/$dir")

    @Test
    fun `an unexplained constant is caught`() {
        assertThatThrownBy { Conventions.constantsExplained(scope("unexplained")) }
            .hasMessageContaining("PING_INTERVAL_MS")
    }

    @Test
    fun `an explained constant passes`() {
        assertThatCode { Conventions.constantsExplained(scope("explained")) }.doesNotThrowAnyException()
    }

    @Test
    fun `a test named after no production file is caught, and only that one`() {
        assertThatThrownBy {
            Conventions.testsMirrorProduction(tests = scope("mirror/test"), production = scope("mirror/main"))
        }.hasMessageContaining("OrphanTest")
            .message()
            .doesNotContain("KeepaliveTest")
    }
}
