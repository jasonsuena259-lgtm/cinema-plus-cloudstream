package com.myseed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class MySeedOfflineTest {

    private fun readHtml(fileName: String): org.jsoup.nodes.Document {
        val resource = this::class.java.classLoader?.getResource(fileName)
            ?: throw IllegalArgumentException("File not found: \$fileName")
        val content = resource.readText(Charsets.UTF_8)
        return Jsoup.parse(content, "https://myseed.baby")
    }

    @Test
    fun testParseSearch() {
        val doc = readHtml("search.html")
        val results = doc.select("ul#pm-grid li.col-xs-6").mapNotNull {
            val titleElement = it.selectFirst("div.caption h3 a") ?: return@mapNotNull null
            val title = titleElement.text()
            title
        }
        assertTrue(results.isNotEmpty())
        assertEquals("فيلم The Mother's Monster 2026 مترجم", results.first())
    }

    @Test
    fun testParseSeriesDetails() {
        val doc = readHtml("series-details.html")
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val isSeries = title.contains("مسلسل") || title.contains("برنامج") || doc.selectFirst("dt:contains(الموسم)") != null

        assertTrue(isSeries)
        assertEquals("مسلسل Monster The Lizzie Borden Story مترجم", title)

        val episodesList = doc.select("ul.pm-ul-browse-videos li.col-xs-6")
        assertTrue(episodesList.isNotEmpty())

        val epTitles = episodesList.map { it.selectFirst("div.caption h3 a")?.text() ?: "" }
        assertTrue(epTitles.contains("مسلسل Monster The Lizzie Borden Story الحلقة 1 الاولى مترجمة"))
    }

    @Test
    fun testParsePlayLinks() {
        val doc = readHtml("movie-play.html")
        var hasLinks = false
        doc.select("ul.list_servers li").forEach {
            val embedData = it.attr("data-embed")
            val iframeSrc = Regex("""src='([^']+)'""").find(embedData)?.groupValues?.get(1)
                ?: Regex("""src="([^"]+)"""").find(embedData)?.groupValues?.get(1)

            if (iframeSrc != null) {
                hasLinks = true
                assertTrue(iframeSrc.startsWith("http"))
            }
        }
        assertTrue("No links were extracted", hasLinks)
    }
}
