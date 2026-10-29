package com.cinelite

/**
 * Classifies HLS playlists from their BYTES, not just the URL suffix.
 * A successful HTTP request or an #EXTM3U header does not prove video exists.
 * This parser never prints or persists expiring signed URLs.
 */
object CineLiteHlsInspector {
    enum class Kind { INVALID, VIDEO_MASTER, AUDIO_MASTER, MEDIA }
    fun kind(text: String): Kind {
        val lines = text.lineSequence().map { it.trim() }.toList()
        if (lines.none { it == "#EXTM3U" }) return Kind.INVALID
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
        if (variants.isNotEmpty()) {
            val knownVideo = Regex("(?i)(?:avc1|avc3|hvc1|hev1|vp0[89]|av01|dvh1|dvhe|mp4v)")
            val knownAudio = Regex("(?i)(?:mp4a|ac-3|ec-3|opus|flac)")
            val audioOnly = variants.all { line ->
                val codecs = Regex("(?i)CODECS=\\\"([^\\\"]+)\\\"")
                    .find(line)?.groupValues?.get(1)
                codecs != null && knownAudio.containsMatchIn(codecs) &&
                    !knownVideo.containsMatchIn(codecs) &&
                    !line.contains("RESOLUTION=", ignoreCase = true)
            }
            return if (audioOnly) Kind.AUDIO_MASTER else Kind.VIDEO_MASTER
        }
        return if (lines.any { it.startsWith("#EXTINF:") }) Kind.MEDIA else Kind.INVALID
    }

    fun firstSegment(text: String): String? {
        if (kind(text) != Kind.MEDIA) return null
        return text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * Inspect PAT/PMT in a few TS packets to distinguish AAC-only segments
     * from packets carrying a genuine video elementary stream.
     * null means unknown (e.g. encrypted TS, fMP4 or insufficient bytes).
     */
    fun tsHasVideo(data: ByteArray): Boolean? {
        fun u(i: Int) = data[i].toInt() and 255
        var pmt = -1
        var sawPmt = false
        val max = minOf(data.size, 188 * 500)
        for (packet in 0 until max - 187 step 188) {
            if (u(packet) != 0x47) continue
            val pid = ((u(packet + 1) and 0x1f) shl 8) or u(packet + 2)
            val pusi = (u(packet + 1) and 0x40) != 0
            val mode = (u(packet + 3) shr 4) and 3
            if (mode == 0 || mode == 2) continue
            var offset = packet + 4
            if (mode == 3) offset += 1 + u(offset)
            if (offset >= packet + 188 || !pusi) continue
            offset += 1 + u(offset)
            if (offset + 12 >= packet + 188) continue
            val length = ((u(offset + 1) and 15) shl 8) or u(offset + 2)
            val end = minOf(offset + 3 + length - 4, packet + 188)
            if (pid == 0 && u(offset) == 0x00) {
                var i = offset + 8
                while (i + 4 <= end) {
                    val program = (u(i) shl 8) or u(i + 1)
                    if (program > 0) {
                        pmt = ((u(i + 2) and 31) shl 8) or u(i + 3)
                        break
                    }
                    i += 4
                }
            } else if (pid == pmt && pmt >= 0 && u(offset) == 0x02) {
                sawPmt = true
                val infoLength = ((u(offset + 10) and 15) shl 8) or u(offset + 11)
                var i = offset + 12 + infoLength
                while (i + 5 <= end) {
                    val type = u(i)
                    if (type in setOf(0x01, 0x02, 0x10, 0x1b, 0x24, 0x27, 0x33))
                        return true
                    val descriptorLength = ((u(i + 3) and 15) shl 8) or u(i + 4)
                    i += 5 + descriptorLength
                }
            }
        }
        return if (sawPmt) false else null
    }
}
