package com.akwam

import org.junit.Assert.*
import org.junit.Test

class AkwamParserTest {
    @Test fun filmAndEpisodeCards() {
        val html = """<a href="https://akwam.day/film/فيلم-star-one-2026-مترجم" class="show-card"><img src="https://akwam.day/photos/1.jpg"><p class="title">فيلم Star One 2026 مترجم</p></a><a href="https://akwam.day/episode/مسلسل-tulsa-king-الموسم-الرابع-الحلقة-2-مترجمة" class="show-card"><p class="title">Tulsa King الحلقة 2</p></a>"""
        val cards = AkwamParser.cards(html, "https://akwam.day/")
        assertEquals(2,cards.size); assertFalse(cards[0].series); assertTrue(cards[1].series); assertEquals("https://akwam.day/photos/1.jpg",cards[0].poster)
    }
    @Test fun seasonAndEpisodeNumbers() {
        val series = """<a class="epss" href="/season/tulsa-king-1"><h3><span class="fs-6">الموسم</span><span class="fs-2">1</span></h3></a><a class="epss" href="/season/tulsa-king-4"><h3><span class="fs-2">4</span></h3></a>"""
        val seasons = AkwamParser.seasons(AkwamParser.document(series, "https://akwam.day/series/tulsa-king"))
        assertEquals(listOf(1,4),seasons.map { it.second })
        val page = """<a class="epss" href="/episode/tulsa-s4e1"><span class="fs-2">1</span></a><a class="epss" href="/episode/tulsa-s4e2"><span class="fs-2">2</span></a>"""
        val episodes = AkwamParser.episodes(AkwamParser.document(page,seasons.last().first),4)
        assertEquals(listOf(1,2),episodes.map { it.number }); assertTrue(episodes.all { it.season==4 })
        assertTrue(AkwamParser.seasons(AkwamParser.document("<html></html>","https://akwam.day/")).isEmpty())
    }
    @Test fun paginationFromRealButtonShape() {
        val doc = AkwamParser.document("""<ul class="pagination"><li><button onclick="updateQuery('page', 2)">2</button></li></ul>""", "https://akwam.day/search?s=x")
        assertTrue(AkwamParser.hasNext(doc, 1)); assertFalse(AkwamParser.hasNext(doc, 2))
    }
    @Test fun resolutionParametersDoNotCrashOnMissingData() {
        val html = """<script>const watchResolveKey = "key123"; const csrfToken = "csrf123";</script>"""
        assertEquals("key123" to "csrf123", AkwamParser.videoParameters(html))
        assertNull(AkwamParser.videoParameters("<script>const csrfToken = \"x\"</script>"))
    }
}
