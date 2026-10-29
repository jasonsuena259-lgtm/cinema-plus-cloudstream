package com.cinewave
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
class CineWaveRepository {
    suspend fun text(url: String): String {
        try {
            val r=app.get(url,timeout=20)
            if(r.code !in 200..299) throw ErrorLoadingException("CineWave: HTTP ${r.code}")
            return r.text
        } catch(e: CancellationException) { throw e }
        catch(e: ErrorLoadingException) { throw e }
        catch(e: Exception) { throw ErrorLoadingException("CineWave: تعذر الاتصال (${e.javaClass.simpleName})") }
    }
    suspend fun json(path: String)=try { CineWaveParser.json(text(CineWaveParser.BASE+path)) }
        catch(e: CancellationException){throw e} catch(e:Exception){throw ErrorLoadingException("CineWave: تعذر قراءة بيانات الموقع: ${e.message}")}
    suspend fun sources(id: WaveId): List<WaveSource> {
        val url="${CineWaveParser.BASE}/watch/${if(id.type=="movie") "movies" else "tv"}/${id.id}"
        val doc=Jsoup.parse(text(url),url)
        val results=mutableListOf<WaveSource>()
        for(el in doc.select("script[src]")) {
            val src=el.absUrl("src")
            if(!src.startsWith("${CineWaveParser.BASE}/_next/static/chunks/")) continue
            try { results+=CineWaveParser.sources(text(src),id) }
            catch(e:CancellationException){throw e} catch(_:Exception){}
        }
        return results.distinctBy { it.url }
    }
}
