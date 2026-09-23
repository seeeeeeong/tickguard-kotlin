package tickguard.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DotEnvTest {
    @Test
    fun `reads pairs, skipping comments and blanks, and unquotes values`() {
        val parsed =
            parseDotEnv(
                """
                # tossinvest.com > 설정 > Open API 에서 발급
                TOSS_CLIENT_ID=abc

                TOSS_CLIENT_SECRET="s e c"
                export TOSS_ACCOUNT_SEQ='3'
                # TICKGUARD_SLACK_WEBHOOK=
                """.trimIndent(),
            )

        assertThat(parsed).containsExactlyEntriesOf(
            mapOf("TOSS_CLIENT_ID" to "abc", "TOSS_CLIENT_SECRET" to "s e c", "TOSS_ACCOUNT_SEQ" to "3"),
        )
    }

    @Test
    fun `lets the process environment win over the file, as node's env-file does`() {
        val file = Files.createTempFile("env", "").also { Files.writeString(it, "A=file\nB=file\n") }

        val merged = environment(file, mapOf("A" to "process"))

        assertThat(merged).containsEntry("A", "process").containsEntry("B", "file")
    }

    @Test
    fun `is just the process environment when there is no file`() {
        assertThat(environment(Files.createTempDirectory("none").resolve(".env"), mapOf("A" to "1")))
            .containsExactlyEntriesOf(mapOf("A" to "1"))
    }
}
