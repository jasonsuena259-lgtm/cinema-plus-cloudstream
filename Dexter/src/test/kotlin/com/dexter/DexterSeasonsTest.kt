package com.dexter

import org.junit.Assert.*
import org.junit.Test

class DexterSeasonsTest {
    private val link="https://dexter.pw/show/213375-visionquest"

    @Test fun visionQuestSeasonLinkIsNotAOneEpisodeSeries() {
        val doc=DexterParser.document("""
            <h1>VisionQuest (2026)</h1>
            <h2>Seasons</h2>
            <a href="/watch/show/213375-visionquest/1/1">
                Watch Miniseries (8 episodes)</a>
        """.trimIndent(),link)
        assertEquals(0,DexterParser.episodes(doc).size)
        val season=DexterSeasons.starts(doc)
        assertEquals(1,season.size)
        assertEquals(8,season[0].count)
        assertEquals(1,season[0].season)
        val indexed=DexterSeasons.merge(emptyList(),season,emptyList())
        assertEquals(8,indexed.size)
        assertEquals((1..8).toList(),indexed.map{it.number})
        assertEquals("https://dexter.pw/watch/show/213375-visionquest/1/8",
            indexed.last().url)
        assertTrue(indexed.none { it.title?.contains("Watch Miniseries")==true })
    }

    @Test fun actualEpisodeTitlesAndLiveSiteCountsWinOverStaleMetadata() {
        val doc=DexterParser.document("""
            <a href="/watch/show/213375-visionquest/1/1">Watch Miniseries (8 episodes)</a>
            <a href="/watch/show/213375-visionquest/1/3" title="S1 E3: Reboot">S1 E3</a>
        """.trimIndent(),link)
        val stale=(1..10).map { n ->
            DexterEpisode("https://dexter.pw/watch/show/213375-visionquest/1/"+n,
                1,n,"Episode "+n)
        }
        val episodeList=DexterSeasons.merge(DexterParser.episodes(doc),
            DexterSeasons.starts(doc),stale)
        assertEquals(8,episodeList.size)
        assertEquals(listOf(1,2,3,4,5,6,7,8),episodeList.map { it.number })
        assertEquals("S1 E3: Reboot",episodeList[2].title)
        assertFalse(episodeList.any { it.number==10 })
    }

    @Test fun differentSeasonsRemainSeparatedAndChronological() {
        val doc=DexterParser.document("""
            <a href="/watch/show/4607-lost/2/1">Watch Season 2 (24 episodes)</a>
            <a href="/watch/show/4607-lost/1/1">Watch Season 1 (24 episodes)</a>
            <a href="/watch/show/4607-lost/3/1">Watch Season 3 (23 episodes)</a>
        ""","https://dexter.pw/show/4607-lost")
        val out=DexterSeasons.merge(emptyList(),DexterSeasons.starts(doc),emptyList())
        assertEquals(71,out.size)
        assertEquals((1..24).toList(),out.filter{it.season==1}.map{it.number})
        assertEquals((1..24).toList(),out.filter{it.season==2}.map{it.number})
        assertEquals((1..23).toList(),out.filter{it.season==3}.map{it.number})
        assertEquals(1,out.first().season)
        assertEquals(3,out.last().season)
    }

    @Test fun metadataFallbackPreservesEpisodesWhenWebsiteIsBlocked() {
        val videos=(1..8).map{
            DexterEpisode("https://dexter.pw/watch/show/213375-visionquest/1/"+it,
                1,it, "Chapter "+it)
        }
        val result=DexterSeasons.merge(emptyList(),emptyList(),videos)
        assertEquals(8,result.size)
        assertEquals("Chapter 8",result.last().title)
    }

    @Test fun sourcePosterSrcsetAndDataAttributesAreRecognized() {
        val cards=DexterParser.cards("""
            <div class="card"><a href="/show/213375-visionquest">
              <picture><source srcset="https://img.example.org/vision.webp 1x,
                                        https://img.example.org/vision2.webp 2x">
                <img src="" alt="VisionQuest"></picture></a></div>
            <div class="card"><a href="/show/1405-dexter">
                <img data-src="https://img.example.org/dexter.webp" alt="Dexter">
            </a></div>
        """)
        assertEquals(2,cards.size)
        assertTrue(cards.first().poster?.contains("vision.webp")==true)
        assertTrue(cards.last().poster?.contains("dexter.webp")==true)
    }

    @Test fun rejectUnadvertisedOrBogusSeasons() {
        val doc=DexterParser.document("""
            <a href="/watch/show/213375-visionquest/1/1">Watch Miniseries (9999 episodes)</a>
            <a href="/watch/show/213375-visionquest/1/1">Watch Miniseries (8 episodes)</a>
            <a href="/watch/show/213375-visionquest/1/1">Watch Season 2 (8 episodes)</a>
            <a href="https://outside.example.org/episode">Watch Season 3 (8 episodes)</a>
        """,link)
        assertEquals(1,DexterSeasons.starts(doc).size)
        assertEquals(8,DexterSeasons.starts(doc)[0].count)
    }
}
