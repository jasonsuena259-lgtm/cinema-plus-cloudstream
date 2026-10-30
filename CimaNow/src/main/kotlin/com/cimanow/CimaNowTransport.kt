package com.cimanow

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CimaNow regular transport. HTTPS verification stays enabled.
 * Proxy is applied to real OkHttp requests, not merely shown in settings.
 * No proxy is fetched from public lists; no automatic CAPTCHA solving.
 */
internal class CimaNowTransport(
    private val context: Context,
    private val settings: CimaNowSettings
) {
    private val discoveryMutex = Mutex()
    @Volatile private var cachedClient: OkHttpClient? = null
    @Volatile private var cachedProxyKey: String? = null
    @Volatile private var workingDomain: String? = null

    fun resetCache() {
        cachedClient = null
        cachedProxyKey = null
        workingDomain = null
    }

    private fun client(): OkHttpClient {
        val key = if (settings.useProxy) settings.customProxy else "DIRECT"
        cachedClient?.let { if (key == cachedProxyKey) return it }
        synchronized(this) {
            cachedClient?.let { if (key == cachedProxyKey) return it }
            val builder = app.baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(18, TimeUnit.SECONDS)
            if (settings.useProxy) {
                val proxy = CimaNowSettings.parseProxy(settings.customProxy)
                    ?: throw ErrorLoadingException("CimaNow: إعداد البروكسي غير صالح؛ راجع ⚙")
                builder.proxy(proxy)
            }
            return builder.build().also { cachedClient = it; cachedProxyKey = key }
        }
    }

    private fun permitted(host: String): Boolean {
        val defaultHosts = CimaNowSettings.AUTO_BASES.mapNotNull { it.toHttpUrlOrNull()?.host }
        return host in defaultHosts || host == settings.customDomain?.toHttpUrlOrNull()?.host
    }

    private data class Result(val status: Int, val html: String, val url: String)

    /** Follow at most four HTTPS redirects and never send a site cookie to an unapproved host. */
    private fun fetch(initial: String): Result {
        var url = initial
        repeat(5) {
            val parsed = url.toHttpUrlOrNull()
                ?: throw IOException("CimaNow: رابط غير صالح")
            if (parsed.scheme != "https" || !permitted(parsed.host))
                throw IOException("CimaNow: تحويل خارج دومين الموقع — ${parsed.host}")
            val userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0")
            val request = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "ar,en-US;q=0.8")
                .header("Referer", "${parsed.scheme}://${parsed.host}/")
                .apply { CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
                .build()
            client().newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val redirect = response.header("Location")?.let { response.request.url.resolve(it) }
                        ?: throw IOException("CimaNow: إعادة توجيه بلا عنوان")
                    if (redirect.scheme != "https" || !permitted(redirect.host))
                        throw IOException("CimaNow: رفض إعادة توجيه إلى موقع غير موثوق")
                    url = redirect.toString()
                } else {
                    return Result(response.code, response.peekBody(2_000_000).string(), url)
                }
            }
        }
        throw IOException("CimaNow: عمليات إعادة توجيه كثيرة")
    }

    private fun appearsToBeCatalog(data: Result): Boolean {
        if (data.status !in 200..299) return false
        val html = data.html.lowercase()
        if (html.contains("cf-chl") || html.contains("just a moment") ||
            html.contains("challenge-platform")) return false
        return html.contains("aria-label=\"title\"") ||
            html.contains("aria-label='title'") ||
            (html.contains("<article") && html.contains("<section"))
    }

    private fun verify(candidate: String): String? {
        return try {
            val response = fetch(candidate.trimEnd('/') + "/home")
            if (!appearsToBeCatalog(response)) null
            else {
                val final = response.url.toHttpUrlOrNull() ?: return null
                "${final.scheme}://${final.host}" +
                    (if (final.port == 443) "" else ":${final.port}")
            }
        } catch (_: Exception) { null }
    }

    suspend fun base(): String {
        settings.customDomain?.let { return it }
        workingDomain?.let { return it }
        return discoveryMutex.withLock {
            workingDomain?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val attempts = (listOfNotNull(settings.discoveredDomain) + CimaNowSettings.AUTO_BASES).distinct()
                for (candidate in attempts) {
                    val verified = verify(candidate) ?: continue
                    settings.discoveredDomain = verified
                    workingDomain = verified
                    return@withContext verified
                }
                throw ErrorLoadingException(
                    "CimaNow: تعذر الوصول للموقع العادي cimanow.cc أو قراءة كتالوجه. " +
                    "جرّب التحقق من الموقع في المتصفح، ثم غيّر الدومين من ⚙ إذا ظهر عنوان رسمي جديد. " +
                    "البروكسي لا يصلح موقعاً متوقفاً."
                )
            }
        }
    }

    private fun redirectToBase(raw: String, base: String): String {
        val url = base.toHttpUrlOrNull()?.resolve(raw)
            ?: throw ErrorLoadingException("CimaNow: رابط محتوى غير صالح")
        if (!permitted(url.host) || url.scheme != "https")
            throw ErrorLoadingException("CimaNow: رابط لا يتبع الموقع")
        val baseUrl = base.toHttpUrlOrNull()
            ?: throw ErrorLoadingException("CimaNow: دومين غير صالح")
        return baseUrl.newBuilder()
            .encodedPath(url.encodedPath)
            .encodedQuery(url.encodedQuery)
            .build().toString()
    }

    suspend fun page(raw: String): Document {
        val base = base()
        val target = redirectToBase(raw, base)
        return withContext(Dispatchers.IO) {
            val response = try { fetch(target) }
            catch (e: Exception) {
                throw ErrorLoadingException("CimaNow: فشل الاتصال بـ ${base}. ${e.message.orEmpty()}")
            }
            if (response.status == 403 || response.status == 429 || response.status == 503 ||
                response.html.contains("cf-chl", ignoreCase = true) ||
                response.html.contains("Just a moment", ignoreCase = true)) {
                throw ErrorLoadingException(
                    "CimaNow: حماية Cloudflare أو رفض HTTP ${response.status}. " +
                    "افتح ⚙ ← فتح الموقع للتحقق يدوياً، ثم جرّب مجدداً."
                )
            }
            if (response.status !in 200..299)
                throw ErrorLoadingException("CimaNow: السيرفر أعاد HTTP ${response.status}")
            Jsoup.parse(response.html, response.url)
        }
    }
}
