package tickguard.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PublicIpTest {
    @Test
    fun `names an address the sources agree on`() {
        assertThat(describePublicIp(PublicIp("203.0.113.7", listOf("203.0.113.7"), 0))).isEqualTo("203.0.113.7")
    }

    @Test
    fun `refuses to pick when the sources disagree`() {
        // Registering the wrong address is worse than registering none.
        val result = PublicIp(null, listOf("203.0.113.7", "198.51.100.9"), 0)

        assertThat(describePublicIp(result)).isEqualTo("203.0.113.7 또는 198.51.100.9 (조회 결과 불일치, 둘 다 확인 필요)")
    }

    @Test
    fun `uses a single answer but says only one source replied`() {
        assertThat(describePublicIp(PublicIp("203.0.113.7", listOf("203.0.113.7"), 1)))
            .isEqualTo("203.0.113.7 (조회처 한 곳만 응답)")
    }

    @Test
    fun `reports a failed lookup`() {
        assertThat(describePublicIp(PublicIp(null, emptyList(), 2))).isEqualTo("조회 실패")
    }

    @Test
    fun `accepts only a dotted quad`() {
        assertThat(isIpv4("203.0.113.7")).isTrue()
        assertThat(listOf("2606:4700::6810:b9f1", "<html>captive portal</html>", "999.1.1.1", "1.2.3", "1.2.3.4.5"))
            .noneMatch(::isIpv4)
    }

    @Test
    fun `says the address changed when it did, rather than claiming a registration`() {
        // The first live block: a phone hotspot was refused, then the laptop went
        // back to the registered network. Nothing was registered.
        assertThat(describeRecovery("198.51.100.9", "203.0.113.7"))
            .isEqualTo("접속 IP가 바뀌어 연결되었습니다 (198.51.100.9 → 203.0.113.7).")
    }

    @Test
    fun `credits the registration when the refused address is the one now connected`() {
        assertThat(describeRecovery("203.0.113.7", "203.0.113.7")).isEqualTo("허용 IP 등록이 반영되었습니다 (203.0.113.7).")
    }

    @Test
    fun `claims neither when the address could not be looked up`() {
        val neither = "연결이 다시 열렸습니다. 현재 IP는 조회하지 못했습니다."
        assertThat(describeRecovery("203.0.113.7", null)).isEqualTo(neither)
        assertThat(describeRecovery(null, "203.0.113.7")).isEqualTo(neither)
    }
}
