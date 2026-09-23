package tickguard.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class CredentialsTest {
    @Test
    fun `credentials never print the secret`() {
        val printed = TossCredentials("id", "s3cr3t").toString()

        assertThat(printed).contains("id").doesNotContain("s3cr3t")
    }

    @Test
    fun `a token never prints itself`() {
        val printed = IssuedToken("eyJhbGciOi", Instant.EPOCH).toString()

        assertThat(printed).doesNotContain("eyJhbGciOi")
    }

    @Test
    fun `reads both credentials from the environment it is given`() {
        val credentials = loadCredentials(mapOf("TOSS_CLIENT_ID" to "id", "TOSS_CLIENT_SECRET" to "secret"))

        assertThat(credentials.clientId).isEqualTo("id")
        assertThat(credentials.clientSecret).isEqualTo("secret")
    }

    @Test
    fun `rejects an empty value rather than passing it on as a credential`() {
        assertThatThrownBy { loadCredentials(mapOf("TOSS_CLIENT_ID" to "", "TOSS_CLIENT_SECRET" to "secret")) }
            .isInstanceOf(MissingEnvError::class.java)
    }

    @Test
    fun `names the missing key and how to fix it`() {
        assertThatThrownBy { loadCredentials(mapOf("TOSS_CLIENT_ID" to "id")) }
            .hasMessageContaining("TOSS_CLIENT_SECRET")
            .hasMessageContaining(".env.example")
    }
}
