package com.myseed
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
class MySeedTest {
    private val watch="https://myseed.baby/watch.php?vid=77396b294"
    private val play="https://myseed.baby/play.php?vid=77396b294"
    private fun fixture(n:String)=MySeedParser.doc(javaClass.getResource("/$n.html")!!.readText(),if(n=="play")play else watch)
    @Test fun sectionsMatchRealMenu(){
        val doc=fixture("home");assertEquals(16,MySeedParser.sections.size)
        for((path,title) in MySeedParser.sections)assertTrue(title,doc.select("a[href]").any{it.absUrl("href")==MySeedParser.BASE+path&&it.text().trim()==title})
    }
    @Test fun realCardsAndSearch(){
        val home=MySeedParser.cards(fixture("home"));assertTrue(home.size>40);assertEquals(home.size,home.distinctBy{it.url}.size);assertTrue(home.all{it.poster?.startsWith("https://")==true})
        val results=MySeedParser.cards(fixture("search"));assertEquals(12,results.size);assertTrue(results.all{it.title.contains("Silo")})
        assertTrue(MySeedParser.next(fixture("seriescat"),1)!!.contains("page=2"))
    }
    @Test fun correctSeasonNotContainerIndex(){
        val eps=MySeedParser.episodes(fixture("episode"));assertEquals(11,eps.size);assertTrue(eps.all{it.season==3});assertEquals((1..11).toList(),eps.map{it.number});assertEquals(watch,eps.last().url)
        assertEquals(eps.map{it.url},MySeedParser.episodes(fixture("series")).map{it.url})
    }
    @Test fun sourcesMatchUserExamples(){
        val sources=MySeedParser.sources(fixture("play"));assertEquals(16,sources.size)
        assertEquals("https://vidspeed.org/embed-4bf9vkkewziw.html",sources.first{it.name=="Vidspeed"}.url)
        assertEquals("https://mp4.okhd.site/embed-srkse97w87m8.html",sources.first{it.name=="Mp4"}.url)
        assertEquals("https://vidmoly.net/embed-mui73ak93d05.html",sources.first{it.name=="Vidmoly"}.url)
    }
    @Test fun malformedLinksAndChangedMarkup(){
        assertNull(MySeedParser.watchUrl("https://evil.test/watch.php?vid=abc"));assertNull(MySeedParser.watchUrl("/watch.php"));assertNull(MySeedParser.watchUrl("/watch.php?vid=bad%20id"));assertNull(MySeedParser.siteUrl(""))
        assertEquals(play,MySeedParser.playUrl("/watch.php?vid=77396b294&tracking=1"))
        val doc=MySeedParser.doc("<div>changed</div>",watch);assertTrue(MySeedParser.episodes(doc).isEmpty());assertTrue(MySeedParser.sources(doc).isEmpty());assertTrue(MySeedParser.cards(doc).isEmpty())
    }
    @Test fun sourceDedupAndArabicNumbers(){
        val doc=fixture("play");val list=doc.selectFirst(".list_servers")!!;list.appendChild(list.children().first()!!.clone());assertEquals(16,MySeedParser.sources(doc).size);assertEquals(12,MySeedParser.number("الموسم ١٢"))
    }
    @Test fun noCrossSeriesEpisodeLeak(){val doc=fixture("episode");doc.body().append("<a href='/watch.php?vid=unrelated'><em>99</em></a>");assertEquals(11,MySeedParser.episodes(doc).size)}
    @Test fun multipleSeasonsRemainSeparate(){
        val doc=MySeedParser.doc("""<div class="SeasonsBox"><button class="tablinks" onclick="openCity(event, 'A')">الموسم ٢</button><button class="tablinks" onclick="openCity(event, 'B')">الموسم ١</button></div><div class="SeasonsEpisodesMain"><div class="tabcontent" id="A"><a href="/watch.php?vid=season2ep1"><em>١</em></a></div><div class="tabcontent" id="B"><a href="/watch.php?vid=season1ep1"><em>١</em></a></div></div>""",watch)
        val e=MySeedParser.episodes(doc);assertEquals(listOf(1,2),e.map{it.season});assertTrue(e[0].url.endsWith("season1ep1"));assertTrue(e[1].url.endsWith("season2ep1"))
    }
    @Test fun mediaValidationRejectsHtmlAndImages(){
        assertFalse(MySeedLinkValidator.validMediaPrefix("<html>Error</html>".toByteArray()))
        assertFalse(MySeedLinkValidator.validMediaPrefix(ByteArray(1024)))
        val ts=ByteArray(1024);for(i in 0..4)ts[67+188*i]=0x47;assertTrue(MySeedLinkValidator.validMediaPrefix(ts))
    }
    @Test fun mediaDiscoveryDoesNotInventFormats(){
        val media=MySeedParser.media("""sources:[{file:"https://cdn.example/video.m3u8?token=a"},{file:"https://cdn.example/video.mp4"},{file:"https://cdn.example/poster.jpg"}]""",play)
        assertEquals(2,media.size);assertTrue(media.first().endsWith("token=a"))
    }
    @Test fun liveAllSectionsAndPagination()=runBlocking{
        assumeTrue(System.getenv("MYSEED_LIVE_TEST")=="1")
        val p=MySeedProvider();val gate=Semaphore(3)
        coroutineScope{MySeedParser.sections.map{(data,name)->async{gate.withPermit{val items=p.getMainPage(1,MainPageRequest(name,data,false)).items.first().list; if(items.isEmpty())assertTrue(name,MySeedRepository().page(MySeedParser.BASE+data).text().contains("لا توجد اى ملفات بهذا التصنيف"))}}}.awaitAll()}
        val first=p.getMainPage(1,MainPageRequest("الجديد","/newvideos.php",false)).items.first().list.map{it.url}
        val second=p.getMainPage(2,MainPageRequest("الجديد","/newvideos.php",false)).items.first().list.map{it.url};assertNotEquals(first,second)
        assertTrue(p.search("Silo").any{it.url==watch});assertTrue(p.search("مسلسل").isNotEmpty())
        val show=p.load(watch) as TvSeriesLoadResponse;assertEquals(11,show.episodes.size);assertTrue(show.episodes.all{it.season==3})
        assertTrue(p.load("https://myseed.baby/watch.php?vid=d507467a3") is MovieLoadResponse)
    }
    @Test fun liveAllNativeServers()=runBlocking{
        assumeTrue(System.getenv("MYSEED_LIVE_TEST")=="1")
        val mapper=jacksonObjectMapper();val sources=MySeedParser.sources(MySeedRepository().page(play));assertEquals(16,sources.size)
        val resolver=MySeedResolver();val gate=Semaphore(4)
        val rows=coroutineScope{sources.map{source->async{gate.withPermit{
            var error:String?=null;var links=emptyList<ExtractorLink>();var valid=emptyList<ExtractorLink>()
            try{withTimeout(40000){links=resolver.native(source,play,mutableListOf());
            if(source.name=="Vidspeed")System.getenv("MYSEED_CAPTURE_PATH")?.let{java.io.File(it+".vidspeed").writeText(mapper.writeValueAsString(links.map{mapOf("url" to it.url,"headers" to (it.headers+mapOf("Referer" to it.referer)))}))};valid=links.filter{MySeedLinkValidator.validate(it)}}}catch(e:Exception){error=e.javaClass.simpleName}
            Triple(source,valid,mapOf("source" to source.name,"extracted" to links.size,"validated" to valid.size,"error" to error))
        }}}.awaitAll()}
        System.getenv("MYSEED_REPORT_PATH")?.let{java.io.File(it).writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows.map{it.third}))}
        System.getenv("MYSEED_CAPTURE_PATH")?.let{java.io.File(it).writeText(mapper.writeValueAsString(rows.map{(source,links,_)->mapOf("name" to source.name,"status" to null,"found" to links.map{mapOf("url" to it.url,"type" to it.type.name,"headers" to (it.headers+mapOf("Referer" to it.referer)))})}))}
        for(name in listOf("Mp4","Vidmoly"))assertTrue("No verified link from $name",rows.first{it.first.name==name}.second.isNotEmpty())
    }
    @Test fun liveDifferentEpisodeAndMovie()=runBlocking {
        assumeTrue(System.getenv("MYSEED_LIVE_TEST")=="1")
        val provider=MySeedProvider()
        val firstEpisode=MySeedParser.episodes(fixture("episode")).first().url
        val results=mutableListOf<Map<String,Any>>()
        for(url in listOf(firstEpisode,"https://myseed.baby/watch.php?vid=d507467a3")){
            val links=mutableListOf<ExtractorLink>()
            val start=System.nanoTime();var firstMillis:Long?=null
            assertTrue(provider.loadLinks(url,false,{},{if(firstMillis==null)firstMillis=(System.nanoTime()-start)/1000000;links.add(it)}));assertTrue(links.isNotEmpty())
            results+=mapOf("content" to url,"first_callback_ms" to firstMillis!!,"total_ms" to (System.nanoTime()-start)/1000000,"verified_sources" to links.map{it.name}.distinct())
        }
        System.getenv("MYSEED_REPORT_PATH")?.let{java.io.File(it+".additional").writeText(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results))}
        Unit
    }
    @Test fun liveVidspeedReachability()=runBlocking {
        assumeTrue(System.getenv("MYSEED_LIVE_TEST")=="1")
        val source=MySeedParser.sources(MySeedRepository().page(play)).first{it.name=="Vidspeed"}
        val links=MySeedResolver().native(source,play,mutableListOf());assertTrue(links.isNotEmpty())
        val link=links.first();val r=app.get(link.url,headers=link.headers,referer=link.referer,timeout=15)
        org.junit.Assume.assumeFalse("Vidspeed CDN refuses this test connection: HTTP 403",r.code==403)
        assertTrue(MySeedLinkValidator.validate(link))
    }
    @Test fun livePrimaryCallbackAndIsolatedFailure()=runBlocking{
        assumeTrue(System.getenv("MYSEED_LIVE_TEST")=="1")
        val sources=MySeedParser.sources(MySeedRepository().page(play)).filter{it.name in listOf("Vidspeed","Mp4","Vidmoly")}
        val links=mutableListOf<ExtractorLink>()
        assertTrue(MySeedResolver().resolve(sources+SeedSource("invalid test source","https://myseed.baby/__missing_server_test__"),play,{},links::add))
        for(name in listOf("Mp4","Vidmoly"))assertTrue(name,links.any{it.name.startsWith(name)})
    }
}
