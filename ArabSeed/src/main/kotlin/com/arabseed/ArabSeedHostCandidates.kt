package com.arabseed

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Public embed aliases for supported hosts, not video URLs.
 * The player extractor must still resolve a real media resource.
 */
internal object ArabSeedHostCandidates {
    fun variants(raw:String):List<String> {
        val u=raw.toHttpUrlOrNull() ?: return listOf(raw)
        if(u.scheme !in listOf("https","http")) return listOf(raw)
        val host=u.host.lowercase().removePrefix("www.")
        val path=u.encodedPath
        val result=mutableListOf(raw)
        val alternate=when {
            (host=="doodstream.com" || host.startsWith("dood.") || host.startsWith("doodstream.") ||
                host=="dood.watch" || host=="dood.re") && (path.startsWith("/d/") || path.startsWith("/download/")) ->
                    "/e/" + path.substringAfterLast('/')
            (host=="mixdrop.ps" || host=="mixdrop.top" || host=="mixdrop.co" || host=="mixdrop.to") &&
                path.startsWith("/f/") -> "/e/" + path.substringAfterLast('/')
            // The live ArabSeed download cards use /d/ while watch buttons
            // use /e/ for the same Cybervynx identifier.
            host=="cybervynx.com" && path.startsWith("/d/") ->
                "/e/" + path.substringAfterLast('/')
            else -> null
        }
        if(alternate!=null)result+=u.newBuilder().encodedPath(alternate).build().toString()
        return result.distinct()
    }
}
