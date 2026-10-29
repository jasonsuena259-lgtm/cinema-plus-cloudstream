package com.cimanowvip

import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class VipCatalogTest {
    private val base=VipParser.BASE
    private fun fixture(menu:String)=Jsoup.parse("""<nav>$menu</nav><article><a href="/film/"><img src="/poster.jpg" alt="فيلم اختبار"></a></article>""",base+"/home/")
    private class Pages(var doc:Document):VipAccess {
        val requested=mutableListOf<String>()
        override fun headers(url:String)=emptyMap<String,String>()
        override suspend fun home()=doc.clone()
        override suspend fun page(raw:String,render:Boolean,playback:Boolean):Document {requested+=raw;return Jsoup.parse(doc.outerHtml(),raw)}
        override suspend fun capturePlayback(item:String):VipPlaybackCapture=error("Playback must not be called for catalogs")
    }
    @Test fun missingProgramsCannotBreakHomeOrInventAUrl()=runBlocking {
        val pages=Pages(fixture("<a href='/films/'>الأفلام</a>"));val provider=CimaNowVIPProvider(pages)
        val home=provider.getMainPage(1,MainPageRequest("الرئيسية","/home/",false))
        assertEquals(1,home.items.first().list.size)
        assertEquals(listOf("/home/",base+"/films/"),provider.mainPage.map{it.data})
        val missing=provider.getMainPage(1,MainPageRequest("البرامج","menu:programs",false))
        assertEquals("الرئيسية",missing.items.first().name);assertEquals(1,missing.items.first().list.size);assertTrue(pages.requested.isEmpty())
        assertEquals(1,provider.getMainPage(1,MainPageRequest("الرئيسية","/home/",false)).items.first().list.size)
    }
    @Test fun navigationIconsAndAccessibleLabelsDoNotHidePrograms(){
        val doc=fixture("""<a href="/actual-shows/" aria-label="البرامج التلفزيونية"><svg><title>television</title></svg></a><a href="/films/"><i class="material-icons">movie</i>الأفلام</a>""")
        assertEquals(base+"/actual-shows/",VipParser.section(doc,"programs"))
        assertEquals(base+"/films/",VipParser.section(doc,"movies"))
        assertEquals(2,VipParser.catalogSections(doc).size)
    }
    @Test fun whitespaceAndDirectionMarksAreNormalized(){
        val doc=fixture("<a href='/shows/'>\u200fالبرامج\u00a0  التلفزيونية\u200e</a>")
        assertEquals(base+"/shows/",VipParser.section(doc,"programs"))
    }
    @Test fun obsoleteSectionsDisappearOnRefresh()=runBlocking {
        val pages=Pages(fixture("<a href='/shows/'>البرامج</a>"));val provider=CimaNowVIPProvider(pages)
        provider.getMainPage(1,MainPageRequest("الرئيسية","/home/",false))
        assertTrue(provider.mainPage.any{it.data==base+"/shows/"})
        pages.doc=fixture("<a href='/films/'>الأفلام</a>")
        provider.getMainPage(1,MainPageRequest("الرئيسية","/home/",false))
        assertFalse(provider.mainPage.any{it.data==base+"/shows/"})
        assertTrue(provider.mainPage.any{it.data==base+"/films/"})
    }
}
