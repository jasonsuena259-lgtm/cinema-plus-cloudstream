package com.cinelite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CineLiteHlsInspectorTest {
    @Test fun videoMasterAndAudioOnlyMasterAreNotEquivalent() {
        val actualVideo = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3600000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio"
            /proxy/hls?t=TEST&ext=.m3u8
        """.trimIndent()
        val audioOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            /proxy/audio.m3u8
        """.trimIndent()
        assertEquals(CineLiteHlsInspector.Kind.VIDEO_MASTER,
            CineLiteHlsInspector.kind(actualVideo))
        assertEquals(CineLiteHlsInspector.Kind.AUDIO_MASTER,
            CineLiteHlsInspector.kind(audioOnly))
        assertEquals(CineLiteHlsInspector.Kind.INVALID,
            CineLiteHlsInspector.kind("<html>not a video</html>"))
    }

    private fun packet(pid: Int, table: IntArray): ByteArray {
        val packet = ByteArray(188) { 0xff.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1f)).toByte()
        packet[2] = (pid and 0xff).toByte()
        packet[3] = 0x10
        packet[4] = 0
        table.forEachIndexed { index, value ->
            packet[5 + index] = value.toByte()
        }
        return packet
    }

    private fun transportStream(type: Int): ByteArray {
        val pat = intArrayOf(
            0x00,0xB0,0x0D, 0x00,0x01,0xC1,0x00,0x00,
            0x00,0x01,0xE1,0x00, 0x00,0x00,0x00,0x00
        )
        val pmt = intArrayOf(
            0x02,0xB0,0x12, 0x00,0x01,0xC1,0x00,0x00,
            0xE1,0x01,0xF0,0x00,
            type,0xE1,0x01,0xF0,0x00,
            0x00,0x00,0x00,0x00
        )
        return packet(0,pat) + packet(0x100,pmt)
    }

    @Test fun realTsVideoPacketIsRequiredForMediaOnlyPlaylist() {
        val list = "#EXTM3U\n#EXTINF:4.1,\nchunk-000.ts\n"
        assertEquals(CineLiteHlsInspector.Kind.MEDIA,
            CineLiteHlsInspector.kind(list))
        assertEquals("chunk-000.ts",CineLiteHlsInspector.firstSegment(list))
        assertEquals(true,CineLiteHlsInspector.tsHasVideo(transportStream(0x1b)))
        assertEquals(false,CineLiteHlsInspector.tsHasVideo(transportStream(0x0f)))
        assertEquals(null,CineLiteHlsInspector.tsHasVideo(ByteArray(188)))
    }

    @Test fun playlistDeclaredSubtitleIsNotInventedAndHasRealUrl() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="ar",NAME="Arabic",URI="/proxy/sub?ext=.vtt&t=TEST"
            #EXT-X-STREAM-INF:BANDWIDTH=5400000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",SUBTITLES="subs"
            variant.m3u8
        """.trimIndent()
        val tracks=CineLiteParser.hlsSubtitleTracks(
            text,"https://media.example.com/proxy/hls?t=TEST&ext=.m3u8")
        assertEquals(1,tracks.size)
        assertEquals("Arabic",tracks.first().first)
        assertTrue(tracks.first().second.startsWith("https://media.example.com/"))
        assertFalse(tracks.first().second.contains("example.org"))
    }
}
