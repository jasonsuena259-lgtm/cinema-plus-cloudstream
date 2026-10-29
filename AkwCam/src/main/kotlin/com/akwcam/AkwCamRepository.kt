package com.akwcam
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
class AkwCamRepository {
 companion object {val headers=mapOf("User-Agent" to "Mozilla/5.0")}
 suspend fun page(url:String):org.jsoup.nodes.Document {
  val target=AkwCamParser.site(url) ?: throw ErrorLoadingException("أكوام: رابط خارج الموقع")
  repeat(2){attempt->
   try {
    val r=app.get(target,headers=headers,timeout=15)
    if(r.code in 200..299)return AkwCamParser.doc(r.text,r.url)
    if(r.code<500||attempt==1)throw ErrorLoadingException("أكوام: HTTP ${r.code}")
   }catch(e:CancellationException){throw e}catch(e:ErrorLoadingException){throw e}catch(e:Exception){if(attempt==1)throw ErrorLoadingException("أكوام: تعذر الاتصال")}
   delay(300)
  }
  throw ErrorLoadingException("أكوام: تعذر الاتصال")
 }
}
