package com.myseed

/**
 * Classify public player network requests before converting them into
 * CloudStream links. Do not treat posters, landing pages or HTML as video.
 */
internal object MySeedMediaUrl {
    private val hls = Regex("""(?i)\.m3u8(?:[?#]|$)""")
    private val mp4 = Regex("""(?i)\.mp4(?:[?#]|$)""")

    fun isHls(url: String) = hls.containsMatchIn(url)
    fun isMp4(url: String) = mp4.containsMatchIn(url)
}
