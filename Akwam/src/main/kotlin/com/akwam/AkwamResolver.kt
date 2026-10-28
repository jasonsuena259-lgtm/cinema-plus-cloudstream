package com.akwam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

class AkwamResolver(private val base: String) {
    suspend fun resolve(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val uri = runCatching { URI(data) }.getOrNull() ?: return false
        if (uri.host != "akwam.day" || uri.scheme != "https" || !(uri.path.startsWith("/film/") || uri.path.startsWith("/episode/"))) return false
        // The site's public watch route shares the film/episode slug. Request it
        // directly: playback does not require reloading the details page first.
        val watchUrl = AkwamParser.watchUrl(data) ?: return false
        val watch = try { app.get(watchUrl, referer = uri.toASCIIString(), timeout = 15) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw ErrorLoadingException("أكوام v2: تعذر الاتصال بصفحة المشاهدة (${e.javaClass.simpleName}).") }
        if (watch.code !in 200..299) throw ErrorLoadingException("أكوام v2: صفحة المشاهدة أعادت HTTP ${watch.code}.")
        val keys = AkwamParser.videoParameters(watch.text) ?: throw ErrorLoadingException("أكوام v2: تعذر قراءة بيانات صفحة المشاهدة.")
        val sessionCookies = watch.cookies
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val serverTokens = watch.document.select(".btn-server[data-token]").map { it.text().trim() to it.attr("data-token") }.filter { it.second.isNotBlank() }.distinctBy { it.second }
        val results = coroutineScope {
            serverTokens.map { (serverName, token) -> async {
                withTimeoutOrNull(30000L) {
                    val links = mutableListOf<ExtractorLink>()
                    val subtitles = mutableListOf<SubtitleFile>()
                    try {
                        val response = app.post("$base/watch/resolve", referer = watchUrl, cookies = sessionCookies,
                            headers = mapOf("Accept" to "application/json", "X-CSRF-TOKEN" to keys.second, "X-Requested-With" to "XMLHttpRequest"),
                            json = mapOf("key" to keys.first, "token" to token, "human_signal" to null), timeout = 12)
                        if (response.code !in 200..299) { failures.add("$serverName: HTTP ${response.code}"); return@withTimeoutOrNull links to subtitles }
                        val node = jacksonObjectMapper().readTree(response.text)
                        if (!node.path("ok").asBoolean()) return@withTimeoutOrNull links to subtitles
                        val wrapper = URI(base).resolve(node.path("url").asText()).toString()
                        if (!wrapper.startsWith("$base/m/")) return@withTimeoutOrNull links to subtitles
                        val hostPage = app.get(wrapper, referer = watchUrl, cookies = sessionCookies, timeout = 12)
                        val hostUrl = hostPage.url
                        if (!hostUrl.startsWith("https://") || hostUrl.startsWith(base + "/")) return@withTimeoutOrNull links to subtitles
                        // Do not interact with CAPTCHA or protection challenges.
                        if (Regex("(?i)recaptcha|hcaptcha|cf-turnstile").containsMatchIn(hostPage.text)) { failures.add("$serverName: يتطلب تحققًا"); return@withTimeoutOrNull links to subtitles }
                        val hostname = runCatching { URI(hostUrl).host.orEmpty().lowercase() }.getOrDefault("")
                        if (hostname == "luluvido.com" || hostname == "fastvid.cam" || hostname == "fastved.cam") {
                            for (script in hostPage.document.select("script")) {
                                val packed = script.data()
                                val unpacked = if (packed.contains("eval(function(p,a,c,k,e")) JsUnpacker(packed).unpack() ?: continue else packed
                                for (media in AkwamParser.mediaUrls(unpacked, hostUrl)) {
                                    links.add(newExtractorLink("Akwam", serverName, media, ExtractorLinkType.M3U8) {
                                        referer = "https://$hostname/"
                                    })
                                }
                            }
                        } else loadExtractor(hostUrl, watchUrl, { subtitles.add(it) }, { links.add(it) })
                    } catch (e: CancellationException) { throw e }
                    catch (e: LinkageError) { failures.add("$serverName: مستخرج غير متوافق مع نسخة التطبيق (${e.javaClass.simpleName}: ${e.message})") }
                    catch (e: Exception) { failures.add("$serverName: ${e.javaClass.simpleName}") }
                    links to subtitles
                } ?: (emptyList<ExtractorLink>() to emptyList<SubtitleFile>())
            } }.awaitAll()
        }
        val links = results.flatMap { it.first }
        val subtitles = results.flatMap { it.second }
        var emitted = false
        for (link in links.distinctBy { it.url to it.headers }) {
            val valid = validate(link)
            if (valid) { callback(link); emitted = true }
        }
        if (emitted) subtitles.distinctBy { it.url }.forEach(subtitleCallback)
        if (!emitted) throw ErrorLoadingException("أكوام v2: لم يُتحقق من رابط فيديو صالح من ${serverTokens.size} سيرفر. " + failures.take(5).joinToString("؛ "))
        return emitted
    }
    private suspend fun validate(link: ExtractorLink): Boolean {
      return try {
        if (link.type == ExtractorLinkType.M3U8) {
            var url = link.url
            var response = app.get(url, referer = link.referer, headers = link.headers, timeout = 15)
            if (response.code !in 200..299) return false
            var manifest = response.text
            if (!manifest.trimStart().startsWith("#EXTM3U") || manifest.contains("#EXT-X-KEY:METHOD=SAMPLE-AES")) return false
            if (manifest.contains("#EXT-X-STREAM-INF:")) {
                val lines = manifest.lines()
                val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
                val variant = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
                url = URI(url).resolve(variant.trim()).toString()
                response = app.get(url, referer = link.referer, headers = link.headers, timeout = 15)
                if (response.code !in 200..299) return false
                manifest = response.text
            }
            if (!manifest.trimStart().startsWith("#EXTM3U") || !manifest.contains("#EXTINF:") || manifest.contains("#EXT-X-KEY:METHOD=SAMPLE-AES")) return false
            val segment = manifest.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
            val part = app.get(URI(url).resolve(segment.trim()).toString(), referer = link.referer, headers = link.headers + mapOf("Range" to "bytes=0-1023"), timeout = 15)
            part.code in 200..299
        } else {
            val response = app.get(link.url, referer = link.referer, headers = link.headers + mapOf("Range" to "bytes=0-1023"), timeout = 15)
            response.code in 200..299 && response.headers["content-type"]?.contains("text/html", true) != true
        }
      } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }
}
