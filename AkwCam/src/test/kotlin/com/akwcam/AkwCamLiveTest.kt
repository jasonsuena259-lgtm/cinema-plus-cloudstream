package com.akwcam
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
class AkwCamLiveTest {
 @Test fun realSources()=runBlocking {
  assumeTrue(System.getenv("AKWCAM_LIVE")=="1")
  val repo=AkwCamRepository()
  val urls=listOf("https://akw.cam/akdrama-lizzie-mcguire-e2-s15/","https://akw.cam/akfilm-blue-heron/")
  val report=java.util.Collections.synchronizedList(mutableListOf<String>())
  for(url in urls){
   val detail=repo.page(url)
   val routes=detail.select(".br-single-actions a[href]").map{it.absUrl("href")}
   File("/workspace/toolchain/akwcam/live-detail-"+url.trimEnd('/').substringAfterLast('/')+".html").writeText(detail.outerHtml())
   assertTrue(routes.isNotEmpty())
   for(route in routes){
    val discovered=AkwCamParser.sources(repo.page(route));assertTrue(discovered.isNotEmpty())
    val sources=discovered.flatMap{try{AkwCamResolver().expand(it,route)}catch(_:Exception){listOf(it)}}
    SourceDispatcher.run(sources,totalMillis=180000,sourceMillis=22000,concurrency=5){source->
     val links=withTimeoutOrNull(18000){try{AkwCamResolver().native(source,route,mutableListOf())}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}}.orEmpty()
     var good=0
     for(l in links){File("/workspace/toolchain/akwcam/candidates-private.tsv").appendText(l.url+"\t"+l.referer+"\n");if(AkwCamLinkValidator.validate(l)){good++;File("/workspace/toolchain/akwcam/media-private.tsv").appendText(l.url+"\t"+l.referer+"\n")}}
     report+="${url.trimEnd('/').substringAfterLast('/')} | ${if(route.contains("/download/")) "download" else "watch"} | ${source.name} | extracted=${links.size} | validated=$good"
    }
   }
  }
  File("/workspace/toolchain/akwcam/live-results.txt").writeText(report.joinToString("\n"))
 }
}
