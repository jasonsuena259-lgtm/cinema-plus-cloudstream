package com.ohavod

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

/**
 * OHA's VOD resolver is a client-driven state machine, not a single URL lookup.
 * /vod/resolve returns { clientFetch: { id, url, method, headers } };
 * fetch the requested web page and send its status, headers and base64 body back
 * to /vod/client-fetch until OHA replies with an actual media URL.
 *
 * This resolver is deliberately bounded. It does not treat a hoster webpage
 * or a clientFetch descriptor as a playable video.
 */
internal class OhaVodResolver {
    private val mapper = jacksonObjectMapper()
    private val hosts = listOf("https://oha.to", "https://www.oha.to")
    private val agent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
    private val maxHops = 6
    private val maxBytes = 1_000_000

    data class Resolved(val url: String, val format: String?,
                        val headers: Map<String, String>, val referer: String?)

    private fun text(n: JsonNode?, key: String): String? =
        n?.path(key)?.takeUnless { it.isMissingNode || it.isNull }?.asText()
            ?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun node(s: String): JsonNode? = runCatching { mapper.readTree(s) }.getOrNull()

    private fun host(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    // Do not let remote descriptors access the device's loopback or LAN.
    private fun publicHttp(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme !in listOf("https", "http")) return false
        if (uri.rawUserInfo != null || uri.port !in -1..65535) return false
        val h = uri.host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".internal") || h == "0.0.0.0" || h == "::1" ||
            h.startsWith("fc:") || h.startsWith("fd:") || h.startsWith("fe80:")) return false
        val ipv4 = h.split(".")
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4[0].toInt()
            val b = ipv4[1].toInt()
            if (a == 0 || a == 10 || a == 127 || a >= 224 ||
                a == 169 && b == 254 || a == 192 && b == 168 ||
                a == 172 && b in 16..31 || a == 100 && b in 64..127 ||
                a == 198 && (b == 18 || b == 19)) return false
        }
        return true
    }

    private fun requestHeaders(node: JsonNode, cookie: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val allowed = setOf("accept", "accept-language", "referer", "origin",
            "user-agent", "cookie", "content-type", "authorization", "range")
        val fields = node.path("headers")
        if (fields.isObject) {
            val iter = fields.fields()
            while (iter.hasNext()) {
                val entry = iter.next()
                val key = entry.key.lowercase()
                if (key !in allowed) continue
                val value = if (entry.value.isArray) entry.value.joinToString(", ") { it.asText() }
                    else entry.value.asText()
                if (value.isNotBlank() && !value.contains('\r') && !value.contains('\n'))
                    out[key] = value
            }
        }
        if (!out.containsKey("user-agent")) out["user-agent"] = agent
        out["accept-encoding"] = "identity"
        if (cookie != null) out["cookie"] = cookie
        return out
    }

    private fun resolveHeaders(n: JsonNode, defaultRef: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val fields = n.path("headers")
        val allowed = setOf("referer", "origin", "authorization", "cookie", "user-agent")
        if (fields.isObject) {
            val iter = fields.fields()
            while (iter.hasNext()) {
                val entry = iter.next()
                val key = entry.key.lowercase()
                val value = entry.value.asText("")
                if (key in allowed && value.isNotBlank() && !value.contains('\n') && !value.contains('\r'))
                    out[key] = value
            }
        }
        if (!out.containsKey("referer")) out["referer"] = defaultRef
        return out
    }

    private fun safeResult(n: JsonNode, embed: String): Resolved? {
        val url = text(n, "url") ?: return null
        if (!publicHttp(url) || n.path("live").asBoolean(false)) return null
        val headers = resolveHeaders(n, embed)
        return Resolved(url, text(n, "format"), headers, headers["referer"])
    }

    private suspend fun start(embed: String): JsonNode? {
        for (base in hosts) {
            try {
                val reply = app.get(base + "/vod/resolve?url=" +
                    URLEncoder.encode(embed, "UTF-8"),
                    timeout = 8, headers = mapOf("Accept" to "application/json",
                        "Referer" to base + "/", "User-Agent" to agent))
                if (reply.code == 200) {
                    val result = node(reply.text)
                    if (result?.isObject == true) return result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        return null
    }

    private fun content(input: java.io.InputStream): ByteArray {
        input.use { stream ->
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (out.size() < maxBytes) {
                val length = stream.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                if (length < 0) break
                if (length == 0) continue
                out.write(buffer, 0, length)
            }
            return out.toByteArray()
        }
    }

    /** Uses one cookie jar per embed, never shared between unrelated sources. */
    suspend fun resolve(embed: String): Resolved? {
        if (!publicHttp(embed)) return null
        var state = start(embed) ?: return null
        val cookies = mutableMapOf<String, MutableMap<String, String>>()
        val seen = mutableSetOf<String>()

        for (hop in 0 until maxHops) {
            safeResult(state, embed)?.let { return it }
            val descriptor = state.path("clientFetch")
            val id = text(descriptor, "id") ?: return null
            val target = text(descriptor, "url") ?: return null
            if (!publicHttp(target) || !seen.add("$id|$target")) return null
            val domain = host(target) ?: return null
            val existingCookies = cookies[domain]?.entries
                ?.joinToString("; ") { it.key + "=" + it.value }
            val requestHeaders = requestHeaders(descriptor, existingCookies)
            val method = (text(descriptor, "method") ?: "GET").uppercase()
            if (method !in listOf("GET", "HEAD", "POST")) return null

            val response = try {
                when (method) {
                    "POST" -> app.post(target, data = emptyMap(),
                        headers = requestHeaders, timeout = 8)
                    else -> app.get(target, headers = requestHeaders, timeout = 8)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }

            val finalUrl = response.url
            if (!publicHttp(finalUrl)) return null
            val resultHost = host(finalUrl) ?: return null
            val headers = response.okhttpResponse.headers
            val setCookies = headers.values("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                val jar = cookies.getOrPut(resultHost) { linkedMapOf() }
                for (header in setCookies) {
                    val pair = header.substringBefore(";")
                    val pos = pair.indexOf('=')
                    if (pos > 0) jar[pair.substring(0, pos).trim()] =
                        pair.substring(pos + 1).trim()
                }
            }
            val responseBody = try {
                content(response.okhttpResponse.body.byteStream())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            val reportedHeaders = linkedMapOf<String, String>()
            for (key in listOf("Content-Type", "Content-Length", "Location",
                "Cache-Control", "Last-Modified", "ETag")) {
                val value = headers[key]
                if (!value.isNullOrEmpty()) reportedHeaders[key.lowercase()] = value
            }
            if (setCookies.isNotEmpty()) reportedHeaders["set-cookie"] =
                setCookies.joinToString(", ")

            val reply = try {
                app.post(hosts[0] + "/vod/client-fetch", timeout = 8,
                    headers = mapOf("Accept" to "application/json",
                        "User-Agent" to agent),
                    json = mapOf(
                        "id" to id,
                        "status" to response.code,
                        "url" to finalUrl,
                        "headers" to reportedHeaders,
                        "body" to Base64.encodeToString(responseBody, Base64.NO_WRAP),
                        "encoding" to "base64"
                    ))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            state = node(reply.text) ?: return null
        }
        return null
    }
}
