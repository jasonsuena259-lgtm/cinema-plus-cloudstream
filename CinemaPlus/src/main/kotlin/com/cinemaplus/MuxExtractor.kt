package com.cinemaplus

object MuxExtractor {
    fun manifest(id: String): String? = id.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }?.let { "https://stream.mux.com/$it.m3u8" }
}
