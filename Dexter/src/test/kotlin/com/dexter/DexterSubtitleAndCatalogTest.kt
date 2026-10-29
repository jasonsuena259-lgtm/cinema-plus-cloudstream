package com.dexter

import org.junit.Assert.*
import org.junit.Test

class DexterSubtitleAndCatalogTest {
    @Test fun apiExtractsArabicAndEnglishFromAllRealScopes() {
        val data="""{
          "sources":[{
            "kind":"hls","url":"https://video.example.org/master.m3u8",
            "subtitles":[{"file":"https://subs.example.org/movie.ar.vtt?token=x","lang":"ar"}],
            "tracks":[{"src":"https://subs.example.org/movie.en.srt","srclang":"en"}]
          }],
          "episode":{
            "captions":[{"url":"https://subs.example.org/episode.fr.vtt","language":"fr"}]
          },
          "subtitles":[{"url":"https://subs.example.org/movie.ar.vtt?token=x","lang":"Arabic"}],
          "tracks":[{"url":"https://video.example.org/thumbs.vtt","lang":"en"}]
        }"""
        val result=DexterApi.parse(data)
        assertEquals(3,result.subtitles.size)
        assertEquals(listOf("Arabic","en","fr"),result.subtitles.map { it.language })
        assertTrue(result.subtitles[0].url.contains("token=x"))
        assertFalse(result.subtitles.any { it.url.contains("thumbs") })
    }

    @Test fun hlsMediaGroupRecognizesActualCaptionsButNotThumbnailOrM3u8Tracks() {
        val playlist="""#EXTM3U
          #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="ar",NAME="Arabic",DEFAULT=YES,URI="subs/arabic.vtt?token=123"
          #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio.m3u8"
          #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="en",NAME="English",URI="en.m3u8"
          #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="de",NAME="German",URI="thumbs.vtt"
          #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1920x1080,SUBTITLES="subs"
          1080/index.m3u8""".trimIndent()
        val tracks=DexterApi.playlistSubtitles(playlist,"https://media.example.org/film/master.m3u8")
        assertEquals(1,tracks.size)
        assertEquals("ar",tracks.single().language)
        assertEquals("https://media.example.org/film/subs/arabic.vtt?token=123",tracks.single().url)
    }

    @Test fun cinemetaMetadataIsMappedToOriginalTmdbIDsWithoutFabrication() {
        val input="""{
            "metas":[
              {"name":"Spider-Man: Far From Home","moviedb_id":429617,
               "id":"tt6320628","poster":"https://images.example.org/spider.jpg"},
              {"name":"No TMDB","id":"tt1234567","poster":"https://images.example.org/bad.jpg"}
            ]
        }"""
        val cards=DexterCatalog.entries(input,false)
        assertEquals(1,cards.size)
        assertTrue(cards.single().url.startsWith("https://dexter.pw/watch/429617-"))
        assertTrue(cards.single().url.contains("?imdb=tt6320628"))
        assertNotNull(cards.single().poster)
    }

    @Test fun metadataSeasonEpisodesPreserveRealTvIdentifiers() {
        val input="""{"meta":{
            "name":"Dexter: Resurrection",
            "poster":"https://images.example.org/poster.jpg",
            "videos":[{"season":1,"episode":1,"title":"First"},
                      {"season":1,"episode":2,"title":"Second"}]
        }}"""
        val details=DexterCatalog.details(input,true,"https://dexter.pw/show/259909-dexter-resurrection?imdb=tt33043892")
        assertNotNull(details)
        assertEquals(2,details!!.episodes.size)
        assertEquals(
            "https://dexter.pw/watch/show/259909-dexter-resurrection/1/2",
            details.episodes.last().url
        )
    }

    @Test fun cinemetaSearchFallsBackToDetailWhenOnlyImdbIDPresent() {
        val source="""{"metas":[{"id":"tt6320628","name":"Spider-Man"},
                          {"id":"tt6320628","name":"Duplicate"},
                          {"id":"missing"}]}"""
        assertEquals(listOf("tt6320628"),DexterCatalog.searchImdbIds(source))
        assertEquals("https://v3-cinemeta.strem.io/meta/movie/tt6320628.json",
            DexterCatalog.metaUrl("tt6320628",false))
    }
}
