package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.execution.OrderRequest
import tickguard.stream.Decimal
import tickguard.trading.Side
import java.nio.file.Files

class PlacementFilesTest {
    private val request = OrderRequest("tg-20260925-D-B-SPY", "D", "SPY", Side.BUY, Decimal.ONE, null)

    @Test
    fun `keeps an order begun and never done across a new instance, as a restart would find it`() {
        val dir = Files.createTempDirectory("placements-").toString()

        PlacementFiles(dir).begin(request)
        assertThat(PlacementFiles(dir).pending()).containsExactly("tg-20260925-D-B-SPY")

        PlacementFiles(dir).done(request)
        assertThat(PlacementFiles(dir).pending()).isEmpty()
    }
}
