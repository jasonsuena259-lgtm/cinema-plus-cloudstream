package com.arabseed

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document

internal class ArabSeedRepository {
    companion object {
        val headers = mapOf("user-agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125 Safari/537.36")
    }
    suspend fun page(url: String): Document {
        val safe=ArabSeedParser.siteUrl(url) ?: throw ErrorLoadingException("ArabSeed: عنوان غير مسموح")
        var why="تعذر الاتصال"
        repeat(2){i->
            try {
                val r=app.get(safe,headers=headers,timeout=15)
                if(r.code in 200..299) return ArabSeedParser.doc(r.text,r.url)
                why="HTTP "+r.code
                if(r.code<500)throw ErrorLoadingException("ArabSeed: "+why)
            } catch(e:CancellationException){throw e}
              catch(e:ErrorLoadingException){throw e}
              catch(e:Exception){why=e.javaClass.simpleName}
            if(i==0)delay(350)
        }
        throw ErrorLoadingException("ArabSeed: "+why)
    }
}
