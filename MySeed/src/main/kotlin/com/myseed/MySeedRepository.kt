package com.myseed
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
class MySeedRepository {
    companion object {val headers=mapOf("user-agent" to "Mozilla/5.0")}
    suspend fun page(url:String):org.jsoup.nodes.Document {
        val valid=MySeedParser.siteUrl(url) ?: throw ErrorLoadingException("ماي سيد: رابط غير صالح")
        var reason="تعذر الاتصال"
        repeat(2){attempt->
            try{
                val r=app.get(valid,headers=headers,timeout=15)
                if(r.code in 200..299)return MySeedParser.doc(r.text,r.url)
                reason="HTTP ${r.code}"
                if(r.code<500)throw ErrorLoadingException("ماي سيد: $reason")
            }catch(e:CancellationException){throw e}catch(e:ErrorLoadingException){throw e}catch(e:Exception){reason=e.javaClass.simpleName}
            if(attempt==0)delay(400)
        }
        throw ErrorLoadingException("ماي سيد: $reason")
    }
}
