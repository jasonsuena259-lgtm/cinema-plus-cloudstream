package com.cimanow

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** Isolated preferences. Existing CimaNow VIP credentials and other plugins are untouched. */
internal class CimaNowSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cimanow_regular_v1", Context.MODE_PRIVATE)
    companion object {
        const val DEFAULT_BASE = "https://cimanow.cc"
        val AUTO_BASES = listOf(DEFAULT_BASE, "https://cimanow.net")

        fun normalizeDomain(raw: String): String? {
            val input = raw.trim().takeIf { it.isNotEmpty() } ?: return null
            val parsed = (if (input.contains("://")) input else "https://$input").toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            if (parsed.host.isBlank() || parsed.host == "localhost" || parsed.host.endsWith(".local")) return null
            return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        }

        fun parseProxy(raw: String): Proxy? {
            val text = raw.trim().takeIf { it.isNotBlank() } ?: return null
            val uri = runCatching { URI(if (text.contains("://")) text else "http://$text") }.getOrNull() ?: return null
            if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.path != null && uri.path !in listOf("", "/"))) return null
            val type = when (uri.scheme?.lowercase()) {
                "socks5", "socks5h", "socks" -> Proxy.Type.SOCKS
                "http", "https" -> Proxy.Type.HTTP
                else -> return null
            }
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            // Resolve the proxy endpoint locally; SOCKS can still resolve target hosts remotely.
            return Proxy(type, InetSocketAddress(host, port))
        }
    }
    var customDomain: String?
        get() = prefs.getString("custom_domain", null)?.let(::normalizeDomain)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("custom_domain")
                else putString("custom_domain", normalizeDomain(value) ?: return)
            }.apply()
        }
    var discoveredDomain: String?
        get() = prefs.getString("discovered_domain", null)?.let(::normalizeDomain)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("discovered_domain")
                else putString("discovered_domain", normalizeDomain(value) ?: return)
            }.apply()
        }
    var useProxy: Boolean
        get() = prefs.getBoolean("use_proxy", false)
        set(value) { prefs.edit().putBoolean("use_proxy", value).apply() }
    var customProxy: String
        get() = prefs.getString("custom_proxy", "").orEmpty()
        set(value) { prefs.edit().putString("custom_proxy", value.trim()).apply() }
    val visibleBase: String get() = customDomain ?: discoveredDomain ?: DEFAULT_BASE
    fun reset() {
        prefs.edit().remove("custom_domain").remove("discovered_domain")
            .remove("use_proxy").remove("custom_proxy").apply()
    }
}
