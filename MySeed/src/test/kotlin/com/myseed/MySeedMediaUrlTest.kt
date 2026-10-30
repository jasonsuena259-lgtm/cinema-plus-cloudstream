package com.myseed

import org.junit.Assert.*
import org.junit.Test

class MySeedMediaUrlTest {
    @Test fun acceptsStreamUrlsAndQueryStrings() {
        assertTrue(MySeedMediaUrl.isHls("https://cdn.example/video.m3u8"))
        assertTrue(MySeedMediaUrl.isHls("https://cdn.example/hls/master.M3U8?token=abc"))
        assertTrue(MySeedMediaUrl.isMp4("https://cdn.example/film.mp4?expires=1"))
        assertTrue(MySeedMediaUrl.isMp4("https://cdn.example/film.MP4#play"))
        assertFalse(MySeedMediaUrl.isMp4("https://cdn.example/film.m3u8"))
        assertFalse(MySeedMediaUrl.isHls("https://cdn.example/film.mp4"))
    }

    @Test fun doesNotTreatLandingPagesOrPostersAsStreams() {
        assertFalse(MySeedMediaUrl.isHls("https://cdn.example/watch?id=video.m3u8.png"))
        assertFalse(MySeedMediaUrl.isMp4("https://cdn.example/embed/123"))
        assertFalse(MySeedMediaUrl.isMp4("https://cdn.example/poster.mp4.jpg"))
        assertFalse(MySeedMediaUrl.isHls("https://cdn.example/index.html"))
    }
}
