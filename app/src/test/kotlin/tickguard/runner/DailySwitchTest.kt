package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DailySwitchTest {
    @Test
    fun `keeps the switched sleeves for a restart to find, and forgets them when switched off`() {
        val path = Files.createTempDirectory("switch-").resolve("daily-live")

        DailySwitchFile(path).save(setOf("D"))
        assertThat(DailySwitchFile(path).load()).containsExactly("D")

        DailySwitchFile(path).save(emptySet())
        assertThat(DailySwitchFile(path).load()).isEmpty()
        assertThat(Files.exists(path)).isFalse()
    }
}
