package com.dexter

import org.jsoup.nodes.Document
import java.net.URI

/**
 * Dexter show pages contain links such as "Watch Miniseries (8 episodes)"
 * which point to S01E01. The link itself is NOT one episode.
 *
 * Generate the episode index only for a season/count actually advertised by
 * the show's HTML. Media is never fabricated; loadLinks still obtains streams
 * for each selected episode from Dexter's individual API endpoint.
 */
data class DexterSeasonIndex(val season:Int,val count:Int,val firstUrl:String)

object DexterSeasons {
    private val episodePath=Regex("""/watch/show/(\d+)-[^/]+/(\d+)/(\d+)(?:/|$)""")
    private val listedCount=Regex("""(?i)\((\d{1,4})\s+episodes?\)""")
    private val seasonLabel=Regex("""(?i)\bseason\s*(\d{1,3})\b|الموسم\s*(\d{1,3})""")
    private val ordinal=Regex("""(?i)\b(?:s\d+\s*e\d+|episode\s*\d+|الحلقة\s*\d+)\b""")
    private val seasonShortcut=Regex("""(?i)^\s*watch\s+.*\(\s*\d+\s+episodes?\s*\)""")

    fun isSeasonLink(label:String):Boolean =
        seasonShortcut.containsMatchIn(label.trim())

    fun starts(doc:Document):List<DexterSeasonIndex> {
        val indexes=linkedMapOf<Int,DexterSeasonIndex>()
        for(anchor in doc.select("a[href]")) {
            val text=anchor.text().trim()
            if(!isSeasonLink(text))continue
            val count=listedCount.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?:continue
            if(count !in 1..300)continue
            val url=DexterParser.site(anchor.attr("href"),doc.baseUri())?:continue
            val match=episodePath.find(URI(url).path) ?:continue
            val season=match.groupValues[2].toIntOrNull()?:continue
            val first=match.groupValues[3].toIntOrNull()?:continue
            if(first!=1 || season !in 0..100)continue
            // The route is site evidence; the count is displayed by Dexter.
            val named=seasonLabel.find(text)
            val number=named?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                ?.toIntOrNull()
            if(number!=null && number!=season)continue
            indexes.putIfAbsent(season,DexterSeasonIndex(season,count,url))
        }
        return indexes.values.sortedBy{it.season}
    }

    fun merge(
        explicit:List<DexterEpisode>,
        seasons:List<DexterSeasonIndex>,
        metadata:List<DexterEpisode>
    ):List<DexterEpisode> {
        val known=linkedMapOf<Pair<Int,Int>,DexterEpisode>()
        // The known metadata supplies human-readable episode titles. It is
        // not a guarantee of Dexter playability for future releases.
        for(item in metadata) {
            if(item.season in 0..100 && item.number in 1..300)
                known[item.season to item.number]=item
        }
        val output=linkedMapOf<Pair<Int,Int>,DexterEpisode>()
        for(info in seasons) {
            val match=episodePath.find(URI(info.firstUrl).path)?:continue
            val prefix=info.firstUrl.substringBefore('?').substringBeforeLast('/')+"/"
            for(n in 1..info.count) {
                val key=info.season to n
                val title=known[key]?.title?.takeIf { !isSeasonLink(it) }
                output[key]=DexterEpisode(prefix+n,info.season,n,title)
            }
        }
        for(item in explicit) {
            if(item.season !in 0..100 || item.number !in 1..300)continue
            val key=item.season to item.number
            val siteCount=seasons.find{it.season==item.season}?.count
            if(siteCount!=null && item.number>siteCount)continue
            val prior=output[key]
            val goodTitle=item.title?.takeIf {
                !isSeasonLink(it) && (ordinal.containsMatchIn(it) || it.length>3)
            }
            output[key]=item.copy(title=goodTitle?:prior?.title?:known[key]?.title)
        }
        // For unavailable/blocked show HTML, the structured, independent
        // episode index is a fallback; never replace observed Dexter season
        // counts with a conflicting external count.
        for((key,item) in known) {
            if(seasons.any { it.season==item.season })continue
            output.putIfAbsent(key,item)
        }
        return output.values.sortedWith(
            compareBy<DexterEpisode>{it.season}.thenBy{it.number}
        )
    }
}
