package com.arabseed

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Never expose a host landing page as a playable video. */
internal object ArabSeedMediaCandidate {
    fun canTry(raw:String,source:String,type:ExtractorLinkType):Boolean {
        val u=raw.toHttpUrlOrNull() ?: return false
        val origin=source.toHttpUrlOrNull() ?: return false
        if(u.scheme!="https" || u.host in setOf("localhost","127.0.0.1","0.0.0.0")
            || u.host=="arabsed.cam" || u.username.isNotBlank() || u.password.isNotBlank())return false
        if(type !in setOf(ExtractorLinkType.VIDEO,ExtractorLinkType.M3U8))return false
        if(u.encodedPath.startsWith("/e/") || u.encodedPath.startsWith("/d/") ||
            u.encodedPath.startsWith("/f/") || u.encodedPath.startsWith("/embed/"))return false
        val path=u.encodedPath.lowercase()
        val obvious=path.endsWith(".mp4")||path.endsWith(".m3u8")||
            path.endsWith(".m4v")||path.endsWith(".ts")||path.contains("/pass_md5/")
        // Other streams are only acceptable when emitted by a host extractor
        // onto a different CDN domain rather than the original embed page.
        return obvious || (u.host!=origin.host && !path.endsWith(".html"))
    }
}
