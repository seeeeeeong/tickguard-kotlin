package tickguard.subscribe

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TopicSourcesTest {
    private val aapl = Topic("trade:us", "AAPL")
    private val nvda = Topic("trade:us", "NVDA")

    @Test
    fun `keeps a sold symbol subscribed while it is configured as an extra`() {
        val sources = TopicSources()
        sources.add(TopicSource.EXTRA, listOf(aapl))
        sources.add(TopicSource.HOLDINGS, listOf(aapl, nvda))

        assertThat(sources.remove(TopicSource.HOLDINGS, listOf(aapl, nvda))).containsExactly(nvda)
    }

    @Test
    fun `reports only topics nobody wanted before as new`() {
        val sources = TopicSources()

        assertThat(sources.add(TopicSource.EXTRA, listOf(aapl))).containsExactly(aapl)
        assertThat(sources.add(TopicSource.HOLDINGS, listOf(aapl, nvda))).containsExactly(nvda)
    }

    @Test
    fun `lets a topic go once its last source does`() {
        val sources = TopicSources()
        sources.add(TopicSource.EXTRA, listOf(aapl))
        sources.add(TopicSource.HOLDINGS, listOf(aapl))

        assertThat(sources.remove(TopicSource.EXTRA, listOf(aapl))).isEmpty()
        assertThat(sources.remove(TopicSource.HOLDINGS, listOf(aapl))).containsExactly(aapl)
    }

    @Test
    fun `ignores a removal for a topic it never had`() {
        assertThat(TopicSources().remove(TopicSource.HOLDINGS, listOf(aapl))).isEmpty()
    }
}
