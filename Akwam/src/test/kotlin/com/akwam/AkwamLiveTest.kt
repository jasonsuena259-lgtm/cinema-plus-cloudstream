package com.akwam

import kotlinx.coroutines.runBlocking
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AkwamLiveTest {
    @Test fun mehmedEpisodeResolvesUsingProductionCode() = runBlocking {
        assumeTrue(System.getenv("AKWAM_LIVE_TEST") == "1")
        val links = mutableListOf<ExtractorLink>()
        val url = "https://akwam.day/episode/مسلسل-محمد-الفاتح-سلطان-الفتوحات-الموسم-الرابع-الحلقة-6-مترجمة"
        val ok = AkwamResolver("https://akwam.day").resolve(url, {}, { links.add(it) })
        assertTrue(ok)
        assertTrue(links.isNotEmpty())
        assertTrue(links.any { it.name == "luluvido" || it.name == "earnvids" })
        for (link in links.filter { it.source == "Akwam" }) assertTrue(link.referer.endsWith("/"))
        println("LIVE_PASS verified sources=" + links.map { it.name }.distinct().joinToString(","))
    }
}
