package com.cinewave
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import com.lagradost.cloudstream3.*
class CineWaveTest {
    private fun fixture(name:String)=javaClass.getResource("/$name")!!.readText()
    @Test fun realCatalogue(){val n=CineWaveParser.json(fixture("movies.json"));val items=CineWaveParser.items(n,"movies");assertTrue(items.size>=20);assertEquals(1001,n.path("totalPages").asInt());assertTrue(items.all{it.path("playerUrl").asText().startsWith("https://")})}
    @Test fun realDetails(){val n=CineWaveParser.items(CineWaveParser.json(fixture("movie.json")),"movies").single();assertEquals("Fight Club",n.path("title").asText());assertEquals(550,n.path("id").asInt())}
    @Test fun seasonAndEpisodeIdentity(){val n=CineWaveParser.items(CineWaveParser.json(fixture("tv.json")),"tvShows").single();val eps=CineWaveParser.episodes(n);assertEquals(73,eps.count{it.season!!>0});assertEquals(10,eps.count{it.season==1});assertTrue(eps.all{CineWaveParser.id(it.url())==it});assertNotEquals(eps.first().url(),eps.first().copy(id=999).url())}
    @Test fun allLiteralSources(){val s=CineWaveParser.sources(fixture("servers.js"),WaveId("movie",550));assertEquals(13,s.size);assertTrue(s.any{it.name=="VidLink"&&it.url=="https://vidlink.pro/movie/550"});assertEquals(s,s.distinctBy{it.url})}
    @Test fun excludeAmbiguousTvLinks(){val s=CineWaveParser.sources(fixture("servers.js"),WaveId("tv",1399,2,4));assertEquals(10,s.size);assertFalse(s.any{it.name in listOf("MoviesAPI","VidLink","HNEmbed")});assertTrue(s.any{it.url=="https://player.videasy.net/tv/1399/2/4"})}
    @Test fun doNotMixAnimeIdentifiers(){
        val script=fixture("servers.js")+"""{key:"videasy",label:"VidEasy Anime",buildMovieUrl:e=>`https://player.videasy.net/anime/${'$'}{e}`,buildTvUrl:(e,t,l)=>`https://player.videasy.net/anime/${'$'}{e}/${'$'}{l}`}"""
        assertEquals(13,CineWaveParser.sources(script,WaveId("movie",550)).size)
        assertEquals(10,CineWaveParser.sources(script,WaveId("tv",1399,1,1)).size)
    }
    @Test fun malformedAndExternal(){assertNull(CineWaveParser.id("https://evil.test/tv/1399"));assertNull(CineWaveParser.id("/tv/1399?season=1"));assertNull(CineWaveParser.id("/tv/nope"));assertEquals(WaveId("movie",550),CineWaveParser.id("/movies/550"));assertTrue(CineWaveParser.sources("changed script",WaveId("movie",1)).isEmpty());assertTrue(CineWaveParser.items(CineWaveParser.json("{}"),"movies").isEmpty());assertTrue(runCatching{CineWaveParser.json("bad-json")}.isFailure)}
    @Test fun liveProviderMetadata()=runBlocking {
        assumeTrue(System.getenv("CINEWAVE_LIVE_TEST")=="1")
        val p=CineWaveProvider();val home=p.getMainPage(1,MainPageRequest("Movies","movies",false));assertTrue(home.items.first().list.isNotEmpty())
        val film=p.load("https://cinewave.org.lk/movies/550");assertEquals("Fight Club",film.name)
        val tv=p.load("https://cinewave.org.lk/tv/1399") as TvSeriesLoadResponse;assertEquals(73,tv.episodes.count{(it.season ?: 0)>0})
        val search=p.search("Fight Club");assertTrue(search.any{it.url.endsWith("/movies/550")})
    }
    @Test fun liveFreshServerDiscovery()=runBlocking {
        assumeTrue(System.getenv("CINEWAVE_LIVE_TEST")=="1")
        val sources=CineWaveRepository().sources(WaveId("movie",550));assertEquals(13,sources.size)
    }
    @Test fun liveCapturedMediaUsesProductionValidator()=runBlocking {
        val file=System.getenv("CINEWAVE_LINK_CAPTURE")
        assumeTrue(file!=null)
        val capture=CineWaveParser.json(java.io.File(file!!).readText())
        var count=0
        for(host in capture.filter{it.path("name").asText() in listOf("MoviesAPI","XPass")}){
            val request=host.path("found").firstOrNull() ?: continue
            val link=com.lagradost.cloudstream3.utils.newExtractorLink("CineWave",host.path("name").asText(),request.path("url").asText(),com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8){
                headers=request.path("headers").fields().asSequence().associate{it.key to it.value.asText()}
                referer=headers.entries.firstOrNull{it.key.equals("referer",true)}?.value ?: ""
            }
            assertTrue("Validation failed: "+host.path("name").asText(),CineWaveResolver.validate(link));count++
        }
        assertEquals(2,count)
    }
    @Test fun rejectImagesAndHtmlAcceptTsPrefix(){
        assertFalse(CineWaveResolver.validMediaPrefix("<html>Error</html>".toByteArray()))
        assertFalse(CineWaveResolver.validMediaPrefix(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)))
        val ts=ByteArray(1024);(0..4).forEach{ts[67+188*it]=0x47};assertTrue(CineWaveResolver.validMediaPrefix(ts))
    }
}
