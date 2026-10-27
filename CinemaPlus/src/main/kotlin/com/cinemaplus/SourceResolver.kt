package com.cinemaplus

class SourceResolver(private val repository: CinemaPlusRepository) {
    suspend fun resolve(rawData: String): List<String> {
        val data = CinemaPlusParser.canonicalUrl(rawData) ?: return emptyList()
        val uri = runCatching { java.net.URI(data) }.getOrNull() ?: return emptyList()
        if (uri.scheme != "https" || uri.host != "ffrrx-2000.github.io" || !uri.path.orEmpty().startsWith("/cinema-plas-bot/")) return emptyList()
        val ids = if (uri.path.endsWith("/movie.html")) {
            CinemaPlusParser.sameMovieSources(repository.catalogue(refresh = true), data)
        } else {
            val key = uri.fragment ?: return emptyList()
            if (!key.matches(Regex("[0-9]+-[0-9]+"))) return emptyList()
            val page = data.substringBefore('#')
            val html = repository.get(page) ?: return emptyList()
            listOfNotNull(CinemaPlusParser.episodes(html).firstOrNull { "${it.season}-${it.episode}" == key }?.source)
        }
        val validator = LinkValidator(repository)
        return ids.distinct().mapNotNull { id ->
            val url = MuxExtractor.manifest(id) ?: return@mapNotNull null
            runCatching { url.takeIf { validator.hls(url) } }.getOrNull()
        }
    }
}
