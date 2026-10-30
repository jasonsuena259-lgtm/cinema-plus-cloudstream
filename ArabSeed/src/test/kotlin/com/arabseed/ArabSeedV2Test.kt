package com.arabseed

import org.junit.Assert.*
import org.junit.Test

class ArabSeedV2Test {
    @Test fun doodDownloadHasPublicEmbedAlias() {
        assertEquals(
            listOf("https://doodstream.com/d/fy9siwyhmhze",
                "https://doodstream.com/e/fy9siwyhmhze"),
            ArabSeedHostCandidates.variants("https://doodstream.com/d/fy9siwyhmhze")
        )
    }

    @Test fun mixdropDownloadHasPublicEmbedAliasButOtherHostsUnchanged() {
        assertEquals(
            listOf("https://mixdrop.ps/f/a1","https://mixdrop.ps/e/a1"),
            ArabSeedHostCandidates.variants("https://mixdrop.ps/f/a1"))
        val normal="https://cybervynx.com/e/a1"
        assertEquals(listOf(normal),ArabSeedHostCandidates.variants(normal))
        assertEquals(listOf("https://doodstream.com/e/a1"),
            ArabSeedHostCandidates.variants("https://doodstream.com/e/a1"))
    }

    @Test fun movieAndSeriesMetadataStaySeparate() {
        val html="""
            <div class="item__contents"><a class="movie__block" href="/انمي-movie-2026-مترجم/">
                <div class="post__info"><h3>انمي Movie 2026 مترجم</h3></div>
            </a></div>
            <div class="item__contents"><a class="movie__block" href="/انمي-show-الموسم-الاول-الحلقة-2/">
                <div class="post__info"><h3>انمي Show الموسم الاول الحلقة 2 مترجمة</h3></div>
            </a></div>
        """
        val list=ArabSeedParser.cards(ArabSeedParser.doc(html,"https://arabsed.cam/recently/"))
        assertEquals(2,list.size)
        assertFalse(list[0].series)
        assertTrue(list[0].anime)
        assertTrue(list[1].series)
    }

    @Test fun seasonNumberNeverReadsReleaseYearAsSeason() {
        assertEquals(2,ArabSeedParser.seasonNumber("مسلسل 2026 الموسم الثاني"))
        assertEquals(1,ArabSeedParser.seasonNumber("مسلسل 2026 الموسم الاول"))
        assertEquals(3,ArabSeedParser.seasonNumber("Series 2025 Season 3"))
        assertEquals(1,ArabSeedParser.seasonNumber("مسلسل 2026"))
    }

    @Test fun realWatchButtonsKeepAllDifferentMirrors() {
        val names=listOf("doodstream","doodstream","doodstream",
            "cybervynx","cybervynx","mixdrop","mixdrop","mixdrop","mixdrop",
            "1vid","vidmoly","voe")
        val html="<nav class='arabsed-watch-servers'>" +
            names.mapIndexed{index,name->
                "<button class='arabsed-watch-server' data-src='https://host.example/e/" +
                    index + "'>" + name + "</button>"
            }.joinToString("")+"</nav>"
        val doc=ArabSeedParser.doc(html,"https://arabsed.cam/test/watch/")
        val result=ArabSeedParser.watchSources(doc)
        assertEquals(12,result.size)
        assertEquals(4,result.count{it.name=="mixdrop"})
    }

    @Test fun conciseHomeSectionsExcludeSparseCategories() {
        assertFalse(ArabSeedParser.sections.any{it.first.contains("اهواك")||it.first.contains("غير-مصنف")})
        assertTrue(ArabSeedParser.sections.first().first=="/recently/")
        assertTrue(ArabSeedParser.sections.map{it.first}.distinct().size==ArabSeedParser.sections.size)
    }
}
