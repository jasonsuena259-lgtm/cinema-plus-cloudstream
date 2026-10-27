package com.cinemaplus

class SourceResolver(private val repository: CinemaPlusRepository) {
    suspend fun resolve(data: String): List<String> {
        val uri = runCatching { java.net.URI(data) }.getOrNull() ?: return emptyList()
        val id = if (uri.path.endsWith("/movie.html")) CinemaPlusParser.movieId(data)?.second else {
            val key = uri.fragment ?: return emptyList()
            if (!key.matches(Regex("[0-9]+-[0-9]+"))) return emptyList()
            val page = data.substringBefore('#')
            val html = repository.get(page) ?: return emptyList()
            CinemaPlusParser.episodes(html).firstOrNull { "${it.season}-${it.episode}" == key }?.source
        }
        val url = id?.let { MuxExtractor.manifest(it) } ?: return emptyList()
        return if (LinkValidator(repository).hls(url)) listOf(url) else emptyList()
    }
}
