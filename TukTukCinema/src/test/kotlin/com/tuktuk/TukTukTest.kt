package com.tuktuk

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.jsoup.Jsoup
import kotlinx.coroutines.runBlocking
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class TukTukTest {
    private fun fixture(name: String)=Jsoup.parse(javaClass.getResource("/$name.html")!!.readText(),TukTukParser.BASE+"/")
    @Test fun realCardsHaveUrlsAndPosters() {
        val cards=TukTukParser.cards(fixture("recent"))
        assertTrue(cards.size>20)
        assertTrue(cards.all { it.url.startsWith(TukTukParser.BASE) && !it.poster.isNullOrBlank() })
        assertTrue(cards.any { it.series })
    }
    @Test fun realFilmAndEpisodeSourcesAgree() {
        for(n in listOf("film","episode")) {
            val sources=TukTukParser.sources(fixture(n))
            assertEquals(1,sources.size)
            assertTrue(sources.single().url.startsWith("https://megatuktuk.store/iframe/"))
        }
        assertNotEquals(TukTukParser.sources(fixture("film")),TukTukParser.sources(fixture("episode")))
    }
    @Test fun seasonsAndEpisodesStayBoundToTheirShow() {
        val seasons=TukTukParser.seasons(fixture("episode"))
        assertEquals(listOf(1,2,3,4),seasons.map { it.second }.sorted())
        val eps=TukTukParser.episodes(fixture("season"),1)
        assertEquals(15,eps.size)
        assertTrue(eps.all { it.season==1 && it.url.contains("mehmed") })
        val latest=TukTukParser.episodes(fixture("episode"),4)
        assertTrue(latest.all { it.season==4 })
        assertTrue(latest.any { it.number==7 })
    }
    @Test fun malformedPagesAndMirrorsAreHandled() {
        assertTrue(TukTukParser.cards(Jsoup.parse("changed")).isEmpty())
        assertTrue(TukTukParser.mirrors("broken",TukTukParser.BASE).isEmpty())
        assertTrue(TukTukParser.sources(Jsoup.parse("<iframe id='main-video-frame' data-crypt='invalid'></iframe>")).isEmpty())
        val json="""{"props":{"streams":{"status":"success","data":[{"resolution":"1616x1072","mirrors":[{"driver":"earnvids","link":"//video.example/embed/one"}]}]}}}"""
        val source=TukTukParser.mirrors(json,"https://megatuktuk.store").single()
        assertEquals(1072,source.quality)
        assertEquals("https://video.example/embed/one",source.url)
    }
    @Test fun liveCatalogueDetailsAndSeries() = runBlocking {
        assumeTrue(System.getenv("TUKTUK_LIVE_TEST")=="1")
        val api=TukTukProvider()
        val page=api.getMainPage(1,MainPageRequest(name="Latest",data="/recent/",horizontalImages=false))
        assertTrue(page.items.flatMap { it.list }.size>20)
        val movie=api.load(TukTukParser.BASE+"/all-of-a-sudden-2026/")
        assertTrue(movie is MovieLoadResponse)
        assertFalse(movie.posterUrl.isNullOrBlank())
        val series=api.load(TukTukParser.BASE+"/mehmed-sultan-of-conquests-s04-e07/") as TvSeriesLoadResponse
        assertEquals(setOf(1,2,3,4),series.episodes.map { it.season }.toSet())
        assertTrue(series.episodes.size>22)
        val search=api.search("Mehmed")
        assertTrue(search.isNotEmpty())
        println("LIVE_CATALOGUE_PASS cards="+page.items.sumOf { it.list.size }+" episodes="+series.episodes.size+" search="+search.size)
    }
    @Test fun liveMovieLinks(): Unit = runBlocking {
        assumeTrue(System.getenv("TUKTUK_LIVE_TEST")=="1")
        val links=mutableListOf<ExtractorLink>()
        assertTrue(TukTukResolver().resolve(TukTukParser.BASE+"/all-of-a-sudden-2026/",{}, { links.add(it) }))
        assertTrue(links.isNotEmpty())
        println("LIVE_VIDEO_PASS "+links.map { it.name+" "+it.quality }.distinct().joinToString(", "))
        System.getenv("TUKTUK_PROBE_EXPORT")?.let { path ->
            java.io.File(path).writeText(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(links.map { mapOf("name" to it.name,"url" to it.url,"referer" to it.referer,"headers" to it.headers,"type" to it.type.name) }))
        }

    }
    @Test fun liveEpisodeLinks() = runBlocking {
        assumeTrue(System.getenv("TUKTUK_LIVE_TEST")=="1")
        val links=mutableListOf<ExtractorLink>()
        assertTrue(TukTukResolver().resolve(TukTukParser.BASE+"/mehmed-sultan-of-conquests-s04-e07/",{}, { links.add(it) }))
        assertTrue(links.isNotEmpty())
        println("LIVE_EPISODE_PASS "+links.map { it.name+" "+it.quality }.distinct().joinToString(", "))
    }

    @Test fun contentLinksNormalizeWithoutAcceptingOtherSites() {
        val expected="https://tuktukcinemas.com/line-of-fire-s01-e06/"
        for (url in listOf("/line-of-fire-s01-e06/", "line-of-fire-s01-e06/", "https://www.tuktukcinemas.com/line-of-fire-s01-e06/", "http://TUKTUKCINEMAS.COM/line-of-fire-s01-e06/", " https://tuktukcinemas.com:443/line-of-fire-s01-e06/ ", java.net.URLEncoder.encode(expected,"UTF-8"))) {
            assertEquals(url,expected,TukTukParser.contentUrl(url))
        }
        for(url in listOf("https://tuktukcinemas.com.evil.example/film", "https://other.example/film", "https://user@tuktukcinemas.com/film", "https://tuktukcinemas.com:8080/film")) assertNull(TukTukParser.contentUrl(url))
    }
    @Test fun liveReportedLineOfFireOpensThroughWwwAlias() = runBlocking {
        assumeTrue(System.getenv("TUKTUK_LIVE_TEST")=="1")
        val result=TukTukProvider().load("https://www.tuktukcinemas.com/line-of-fire-s01-e06/") as TvSeriesLoadResponse
        assertTrue(result.episodes.size >= 6)
        assertTrue(result.episodes.any { it.episode==6 })
        assertTrue(result.episodes.all { it.data.startsWith(TukTukParser.BASE+"/line-of-fire-") })
        println("LIVE_LINE_OF_FIRE_PASS episodes="+result.episodes.size)
    }

    @Test fun liveSearchCardsOpenUsingTheirOwnReturnedUrls() = runBlocking {
        assumeTrue(System.getenv("TUKTUK_LIVE_TEST")=="1")
        val api=TukTukProvider()
        val seriesCards=api.search("Line of Fire")
        val movieCards=api.search("All of a Sudden")
        for(card in seriesCards+movieCards) {
            assertEquals(api.name,card.apiName)
            assertEquals(card.url,TukTukParser.contentUrl(card.url))
        }
        val seriesCard=seriesCards.first { it.name.contains("Line of Fire") && it.type==TvType.TvSeries }
        val movieCard=movieCards.first { it.name.contains("All of a Sudden") && it.type==TvType.Movie }
        val series=api.load(seriesCard.url) as TvSeriesLoadResponse
        val movie=api.load(movieCard.url) as MovieLoadResponse
        assertTrue(series.episodes.size>=6)
        assertFalse(movie.dataUrl.isBlank())
        println("LIVE_CARD_TO_DETAILS_PASS series="+series.episodes.size+" movie="+movie.name)
    }

}
