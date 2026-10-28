package com.akwam

import kotlinx.coroutines.runBlocking
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AkwamLiveTest {
    @Test fun actualHomeAndReportedMovieDetails() = runBlocking {
        assumeTrue(System.getenv("AKWAM_LIVE_TEST") == "1")
        val responsePage = com.lagradost.cloudstream3.app.get("https://akwam.day", timeout=15)
        println("DEFAULT_UA=" + com.lagradost.cloudstream3.app.defaultHeaders["user-agent"] + "; server=" + responsePage.headers["server"] + "; type=" + responsePage.headers["content-type"])
        println("HOME_HTTP=" + responsePage.code + "; bytes=" + responsePage.text.length + "; cards=" + AkwamParser.cards(responsePage.text,"https://akwam.day/").size)
        val provider = AkwamProvider()
        val home = provider.getMainPage(1, com.lagradost.cloudstream3.MainPageRequest(name="Home", data="/", horizontalImages=false))
        assertTrue("Home returned no cards", home.items.flatMap { it.list }.isNotEmpty())
        val response = provider.load("https://akwam.day/film/فيلم-صقر-وكناريا-2026")
        assertNotNull("Reported film returned null", response)
        assertTrue(response is com.lagradost.cloudstream3.MovieLoadResponse)
        println("LIVE_DETAILS_PASS cards=" + home.items.sumOf { it.list.size } + "; movie=" + response!!.name)
    }
    @Test fun mehmedEpisodeResolvesUsingProductionCode() = runBlocking {
        assumeTrue(System.getenv("AKWAM_LIVE_TEST") == "1")
        val links = mutableListOf<ExtractorLink>()
        val url = "https://akwam.day/episode/مسلسل-محمد-الفاتح-سلطان-الفتوحات-الموسم-الرابع-الحلقة-6-مترجمة"
        val ok = AkwamResolver("https://akwam.day").resolve(url, {}, { links.add(it) })
        assertTrue(ok)
        assertTrue(links.isNotEmpty())
        assertTrue(links.any { it.name == "luluvido" || it.name == "earnvids" })
        for (link in links.filter { it.source == "Akwam" }) assertTrue(link.referer.endsWith("/"))
        println("LIVE_PASS verified sources=" + links.map { it.name }.distinct().joinToString(","))
    }
}
