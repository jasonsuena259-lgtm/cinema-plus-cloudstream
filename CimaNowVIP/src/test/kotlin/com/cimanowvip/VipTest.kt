package com.cimanowvip
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.jsoup.Jsoup
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
class VipTest {
    @Test fun credentialsStayOnExactHttpsOrigin(){
        assertNotNull(VipParser.site("/home/"))
        for(u in listOf("https://vip.cimanowinc.com.evil.test/","http://vip.cimanowinc.com/","https://user@vip.cimanowinc.com/","https://vip.cimanowinc.com:8443/","https://forafile.com/"))assertNull(u,VipParser.site(u))
    }
    @Test fun tokensNeverBecomeStableEpisodeIds(){assertEquals(VipParser.BASE+"/series/",VipParser.stable(VipParser.BASE+"/series/watching/?token=temporary#x"))}
    @Test fun unwrapAndDeduplicateDownloadWrappers(){
        val u="https://forafile.com/abc/file.mp4.html"
        val doc=Jsoup.parse("<a href='https://href.li/?$u'>Forafile</a><a href='$u'>Forafile again</a>",VipParser.BASE)
        assertEquals(listOf(u),VipParser.sources(doc));assertNull(VipParser.unwrap("javascript:alert(1)"))
    }
    @Test fun jetloadDecodedTargetIsRestricted(){
        val path="https://jetload.top/Jetloadon4/?link="
        assertEquals("https://cdn2.jetload.top/test.mp4",VipParser.jetload(path+"https://cdn2.jetload.top/test.mp4".encodeUtf8().base64()))
        assertNull(VipParser.jetload(path+"https://evil.test/test.mp4".encodeUtf8().base64()))
        assertNull(VipParser.jetload(path+"broken"))
    }
    @Test fun relatedLogoIsNotCardTitleOrPoster(){
        val doc=Jsoup.parse("""<article><a href="/film/"><img src="/logo.png" alt="logo"><img data-src="/cover.jpg" alt="فيلم الاختبار"></a></article>""",VipParser.BASE)
        val item=VipParser.cards(doc).single();assertEquals("فيلم الاختبار",item.title);assertEquals(VipParser.BASE+"/cover.jpg",item.poster)
    }
    @Test fun episodesAreScopedAndRetainSeason(){
        val doc=Jsoup.parse("""<ul id="eps"><li><a href="/مسلسل-lupin-ج4-ح1-مترجمة/"><em>1</em></a></li></ul><div id="related"><a href="/other/"><em>9</em></a></div>""",VipParser.BASE)
        val e=VipParser.episodes(doc).single();assertEquals(4,e.season);assertEquals(1,e.episode)
    }
    @Test fun changedHtmlDoesNotInventContent(){val d=Jsoup.parse("<p>Sign in</p>",VipParser.BASE);assertTrue(VipParser.cards(d).isEmpty());assertTrue(VipParser.sources(d).isEmpty());assertTrue(VipParser.episodes(d).isEmpty())}
    @Test fun youtubeAndAdsAreNeverEpisodeSources(){
        val doc=Jsoup.parse("""<iframe src="https://www.youtube.com/embed/trailer"></iframe><iframe src="https://www.youtube-nocookie.com/embed/trailer"></iframe><iframe src="https://ad.invalid/embed/advert"></iframe><iframe src="https://ok.ru/videoembed/123"></iframe><a href="https://forafile.com/id/video.mp4.html">Download</a>""",VipParser.BASE)
        assertEquals(listOf("https://ok.ru/videoembed/123","https://forafile.com/id/video.mp4.html"),VipParser.sources(doc))
        assertTrue(VipParser.trailer("https://r1.googlevideo.com/video.mp4"));assertFalse(VipParser.hostSource("https://vk.com.evil.test/embed"))
    }
    @Test fun watchingLinkBelongsToSelectedEpisode(){
        val doc=Jsoup.parse("""<a href="/other/watching/?token=wrong">Related</a><a href="/selected/watching/?token=fresh">Watch</a>""",VipParser.BASE)
        assertEquals(VipParser.BASE+"/selected/watching/?token=fresh",VipParser.watchLink(doc,VipParser.BASE+"/selected/"))
    }
    @Test fun recognizesRealGatewayButtonWithoutInventingToken(){
        val doc=Jsoup.parse("""<a class="shine" href="https://href.li/?https://gateway.example/watch/123">شاهد وحمل</a>""",VipParser.BASE)
        assertEquals("https://gateway.example/watch/123",VipParser.watchLink(doc,VipParser.BASE+"/selected/"))
        assertNull(VipParser.watchLink(Jsoup.parse("<iframe src='https://youtube.com/embed/x'></iframe>",VipParser.BASE),VipParser.BASE+"/selected/"))
    }
    @Test fun catalogUrlsComeFromSiteNavigation(){
        val doc=Jsoup.parse("""<nav><a href="/real-films/">الأفلام</a><a href="/real-shows/">المسلسلات</a><a href="/real-programs/">البرامج</a><a href="/real-latest/">الأحدث</a><a href="/account/">حسابي</a><a href="https://external.example/movies">الأفلام</a></nav>""",VipParser.BASE)
        assertEquals(VipParser.BASE+"/real-films/",VipParser.section(doc,"movies"));assertEquals(VipParser.BASE+"/real-shows/",VipParser.section(doc,"series"));assertEquals(4,VipParser.sections(doc).size)
        assertNull(VipParser.section(Jsoup.parse("<nav></nav>",VipParser.BASE),"movies"))
    }
    @Test fun paginationUsesActualNextLink(){
        val doc=Jsoup.parse("""<ul aria-label="pagination"><li><a href="/real-films/page/2/?sort=new">2</a></li></ul>""",VipParser.BASE+"/real-films/")
        assertEquals(VipParser.BASE+"/real-films/page/2/?sort=new",VipParser.next(doc,1));assertNull(VipParser.next(doc,2))
    }
    @Test fun resolverRejectsTrailerBeforeNetwork()=runBlocking{assertTrue(VipResolver().native("https://www.youtube.com/embed/trailer",{}).isEmpty())}
    @Test fun liveForafileExtractsActualMp4()=runBlocking {
        assumeTrue(System.getenv("CIMANOW_VIP_LIVE")=="1")
        val url="https://forafile.com/rtavj6sadg62/%5BCima-Now.CoM%5D_Lupin.S04E01.HD-720p.mp4.html"
        val links=VipResolver().native(url,{})
        assertEquals(1,links.size);assertTrue(VipLinkValidator.validate(links.first()))
    }
}
