package tickguard.news

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/** Shaped like the live feed: headline with a publisher suffix, a source tag, escaped HTML. */
private fun item(
    title: String,
    publisher: String,
    pubDate: String = "Tue, 22 Sep 2026 17:10:22 GMT",
) = "<item><title>$title - $publisher</title><link>https://news.google.com/rss/articles/abc?oc=5</link>" +
    "<guid isPermaLink=\"false\">abc</guid><pubDate>$pubDate</pubDate>" +
    "<description>&lt;a href=\"x\"&gt;$title&lt;/a&gt;</description>" +
    "<source url=\"https://example.com\">$publisher</source></item>"

private fun feed(vararg items: String) =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
        "<title>\"AMZN stock\" - Google News</title>${items.joinToString("")}</channel></rss>"

class GoogleNewsTest {
    @Nested
    inner class ParseGoogleNews {
        @Test
        fun `separates the headline from the publisher the feed appends to it`() {
            val parsed = parseGoogleNews(feed(item("FTC sues Amazon over ad fees", "CNBC")), "AMZN").single()

            assertThat(parsed.source).isEqualTo(NewsSourceName.GOOGLE_NEWS)
            assertThat(parsed.code).isEqualTo("AMZN")
            assertThat(parsed.title).isEqualTo("FTC sues Amazon over ad fees")
            assertThat(parsed.publisher).isEqualTo("CNBC")
            assertThat(parsed.url).isEqualTo("https://news.google.com/rss/articles/abc?oc=5")
            assertThat(parsed.publishedAt).isEqualTo(Instant.parse("2026-09-22T17:10:22Z"))
        }

        @Test
        fun `gives syndicated copies of one headline the same id`() {
            // The same story ran on Motley Fool and Yahoo; it should be judged once.
            val (first, second) =
                parseGoogleNews(
                    feed(
                        item("What \$5,000 in Amazon Could Be Worth", "The Motley Fool"),
                        item("What \$5,000 in Amazon Could Be Worth", "Yahoo Finance"),
                    ),
                    "AMZN",
                )

            assertThat(first.id).isEqualTo(second.id)
        }

        @Test
        fun `decodes entities, and ampersand last so an escaped entity stays escaped`() {
            val parsed =
                parseGoogleNews(feed(item("AT&amp;T &#39;beats&#39; &amp;lt;estimates&amp;gt;", "Reuters")), "T")

            assertThat(parsed.single().title).isEqualTo("AT&T 'beats' &lt;estimates&gt;")
        }

        @Test
        fun `reads a CDATA title without decoding it`() {
            val xml =
                feed(
                    "<item><title><![CDATA[Q3 beat & raise - Barron's]]></title><link>https://x</link>" +
                        "<pubDate>Tue, 22 Sep 2026 17:10:22 GMT</pubDate>" +
                        "<source url=\"https://b\">Barron's</source></item>",
                )

            assertThat(parseGoogleNews(xml, "AMZN").single().title).isEqualTo("Q3 beat & raise")
        }

        @Test
        fun `skips an item whose date will not parse rather than inventing one`() {
            assertThat(parseGoogleNews(feed(item("Undated", "X", "not a date")), "AMZN")).isEmpty()
        }
    }

    @Nested
    inner class HeadlineId {
        @Test
        fun `hashes exactly as the original did, so stored stories stay recognised`() {
            // Computed by the original: sha1("ftc sues amazon"), first sixteen hex digits.
            assertThat(headlineId("  FTC  sues\n Amazon ")).isEqualTo(headlineId("ftc sues amazon"))
            assertThat(headlineId("ftc sues amazon")).isEqualTo("4b6e42d3a4ad8d10")
        }
    }
}
