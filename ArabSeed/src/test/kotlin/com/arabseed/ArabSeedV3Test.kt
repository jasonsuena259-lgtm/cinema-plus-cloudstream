package com.arabseed

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.*
import org.junit.Test

class ArabSeedV3Test {
    @Test fun m3u8MasterAndSegmentListAcceptedWithoutFetchingEncryptedSegment() {
        val master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\n1080/index.m3u8\n"
        assertTrue(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.M3U8,200,"application/vnd.apple.mpegurl",master.toByteArray()))
        assertTrue(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.M3U8,200,"", "#EXTM3U\n#EXTINF:5.0,\nchunk.ts".toByteArray()))
        assertFalse(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.M3U8,200,"text/html","<html>no video</html>".toByteArray()))
        assertFalse(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.M3U8,403,""," #EXTM3U\n#EXTINF:5,\n".toByteArray()))
    }

    @Test fun rangeVideoIsAcceptedByVideoMimeWhenFtypHeaderMissing() {
        assertTrue(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.VIDEO,206,"video/mp4",ByteArray(30){0x09}))
        assertFalse(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.VIDEO,200,"text/html","<html>unavailable</html>".toByteArray()))
        val mp4=byteArrayOf(0,0,0,32,'f'.code.toByte(),'t'.code.toByte(),'y'.code.toByte(),'p'.code.toByte())
        assertTrue(ArabSeedLinkValidator.plausibleMedia(ExtractorLinkType.VIDEO,206,"application/octet-stream",mp4))
    }

    @Test fun experimentalFallbackNeverExposesHostEmbedOrRandomPage() {
        val source="https://doodstream.com/e/abc"
        assertFalse(ArabSeedMediaCandidate.canTry(source,source,ExtractorLinkType.VIDEO))
        assertFalse(ArabSeedMediaCandidate.canTry("https://mixdrop.ps/f/abc",source,ExtractorLinkType.VIDEO))
        assertFalse(ArabSeedMediaCandidate.canTry("https://arabsed.cam/movie/",source,ExtractorLinkType.VIDEO))
        assertFalse(ArabSeedMediaCandidate.canTry("http://cdn.example/movie.mp4",source,ExtractorLinkType.VIDEO))
        assertTrue(ArabSeedMediaCandidate.canTry("https://cdn.example/movie.mp4?token=1",source,ExtractorLinkType.VIDEO))
        assertTrue(ArabSeedMediaCandidate.canTry("https://cdn.example/hls/master.m3u8",source,ExtractorLinkType.M3U8))
        assertTrue(ArabSeedMediaCandidate.canTry("https://cdn.example/stream?token=1",source,ExtractorLinkType.VIDEO))
        assertFalse(ArabSeedMediaCandidate.canTry("https://cdn.example/error.html",source,ExtractorLinkType.VIDEO))
    }
}
