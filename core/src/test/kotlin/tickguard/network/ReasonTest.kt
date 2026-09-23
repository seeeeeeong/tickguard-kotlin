package tickguard.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.rest.RestError
import java.io.IOException
import java.net.UnknownHostException

class ReasonTest {
    @Test
    fun `names the kind of network failure beneath the message`() {
        // No DNS yet: the router is still booting. The host name alone would not say so.
        assertThat(reasonOf(UnknownHostException("openapi.tossinvest.com")))
            .isEqualTo("openapi.tossinvest.com (UnknownHostException)")
        assertThat(reasonOf(IOException("call failed", UnknownHostException("openapi.tossinvest.com"))))
            .isEqualTo("call failed (UnknownHostException)")
    }

    @Test
    fun `leaves a plain failure's message alone`() {
        assertThat(reasonOf(IllegalStateException("holdings did not load"))).isEqualTo("holdings did not load")
        assertThat(reasonOf(IOException("Connection silent for 45s"))).isEqualTo("Connection silent for 45s")
    }

    @Test
    fun `does not decorate a refusal that already carries its status`() {
        assertThat(reasonOf(RestError(403, "forbidden", retryable = false))).isEqualTo("Toss returned 403: forbidden")
    }
}
