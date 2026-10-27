package com.cinemaplus

data class CinemaItem(val title: String, val url: String, val poster: String?, val series: Boolean, val year: Int?, val genres: List<String>, val sections: Set<String> = emptySet())
data class EpisodeKey(val season: Int, val episode: Int, val source: String)
