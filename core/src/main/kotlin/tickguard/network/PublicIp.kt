package tickguard.network

/**
 * The IPv4 address Toss sees us as, for an alert that has to say which
 * address to register.
 *
 * Two sources, because the worst outcome here is not a failed lookup but a
 * wrong one: telling someone to register an address that is not theirs.
 */
data class PublicIp(
    /** Set only when every source that answered gave the same address. */
    val ip: String?,
    /** Distinct addresses seen. More than one means the sources disagree. */
    val answers: List<String>,
    val failed: Int,
)

/** One line for an alert or the status page. Never guesses between answers. */
fun describePublicIp(result: PublicIp): String =
    when {
        result.ip != null -> if (result.failed == 0) result.ip else "${result.ip} (조회처 한 곳만 응답)"
        result.answers.size > 1 -> "${result.answers.joinToString(" 또는 ")} (조회 결과 불일치, 둘 다 확인 필요)"
        else -> "조회 실패"
    }

/**
 * What a recovery alert says. Whether the address was registered or the
 * address itself changed cannot be told from the reconnect alone, only by
 * comparing the address now with the one that was refused. A first live
 * block ended when the laptop left a phone hotspot, and the alert claimed a
 * registration that never happened, showing the hotspot's address.
 */
fun describeRecovery(
    refused: String?,
    now: String?,
): String =
    when {
        refused != null && now != null && refused != now -> "접속 IP가 바뀌어 연결되었습니다 ($refused → $now)."
        now != null && refused == now -> "허용 IP 등록이 반영되었습니다 ($now)."
        else -> "연결이 다시 열렸습니다. 현재 IP는 조회하지 못했습니다."
    }

/**
 * IPv4 only. Toss publishes no AAAA records, so it only ever sees our IPv4
 * address, and an IPv6 answer would name something the allow list cannot use.
 */
fun isIpv4(text: String): Boolean {
    val parts = text.split(".")
    return parts.size == IPV4_OCTETS && parts.all { part -> OCTET.matches(part) && part.toInt() <= OCTET_MAX }
}

/** A dotted quad has four parts; anything else is not an IPv4 address. */
private const val IPV4_OCTETS = 4

/** An octet is one byte. */
private const val OCTET_MAX = 255

/** One to three digits; the range is checked separately. */
private val OCTET = Regex("\\d{1,3}")
