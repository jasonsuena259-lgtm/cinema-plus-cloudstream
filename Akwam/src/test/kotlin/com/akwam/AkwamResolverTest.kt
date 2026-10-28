package com.akwam

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AkwamResolverTest {
    @Test fun arabicEpisodeTraversesWatchSessionAndSurvivesBrokenServer() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val original = app.baseClient
        app.baseClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            seen.add(request.url.host + path)
            assertTrue(request.header("Referer").orEmpty().all { it.code < 128 })
            var code = 200
            var actualRequest = request
            val body = when {
                request.url.host == "akwam.day" && path.startsWith("/watch/") && path != "/watch/resolve" -> """
                    <script>const watchResolveKey = "fixture-key"; const csrfToken = "fixture-csrf";</script>
                    <button class="btn-server" data-token="good">luluvido</button>
                    <button class="btn-server" data-token="bad">broken</button>
                """.trimIndent()
                path == "/watch/resolve" -> {
                    assertEquals("fixture-csrf",request.header("X-CSRF-TOKEN"))
                    assertTrue(request.header("Cookie").orEmpty().contains("session=fixture"))
                    val buffer = okio.Buffer();request.body!!.writeTo(buffer)
                    if (buffer.readUtf8().contains("good")) """{"ok":true,"url":"/m/good"}"""
                    else {code = 503; "temporarily unavailable"}
                }
                path == "/m/good" -> {
                    actualRequest = request.newBuilder().url("https://luluvido.com/e/fixture").build()
                    """<script>jwplayer("vplayer").setup({sources:[{file:"https://cdn.example/master.m3u8"}]});</script>"""
                }
                path == "/master.m3u8" -> {
                    assertEquals("https://luluvido.com/",request.header("Referer"))
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nmedia.m3u8\n"
                }
                path == "/media.m3u8" -> "#EXTM3U\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n"
                path == "/part.ts" -> "fixture segment bytes"
                else -> error("Unexpected request: $path")
            }
            Response.Builder().request(actualRequest).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Set-Cookie","session=fixture; Path=/; Secure")
                .body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        try {
            val links = mutableListOf<ExtractorLink>()
            assertTrue(AkwamResolver("https://akwam.day").resolve("https://akwam.day/episode/الحلقة-6", {}, { links.add(it) }))
            assertEquals(1,links.size)
            assertEquals("luluvido",links.single().name)
            assertEquals("https://luluvido.com/",links.single().referer)
            assertFalse(seen.any { it.contains("/episode/") })
            assertTrue(seen.contains("cdn.example/part.ts"))
        } finally { app.baseClient = original }
    }
}
