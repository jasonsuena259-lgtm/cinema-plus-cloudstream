package com.myseed
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
object MySeedLinkValidator {
        suspend fun validate(link:ExtractorLink):Boolean {
            if(check(link))return true
            delay(500)
            return check(link)
        }
        private suspend fun check(link:ExtractorLink):Boolean=withContext(Dispatchers.IO){
            try {
                fun get(url:String,range:Boolean=false):ByteArray? {
                    val builder=Request.Builder().url(url)
                    link.headers.forEach{(k,v)->builder.header(k,v)}
                    if(link.referer.isNotBlank())builder.header("Referer",link.referer)
                    if(range)builder.header("Range","bytes=0-1023")
                    return app.baseClient.newBuilder().callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build().newCall(builder.build()).execute().use{r->
                        if(!r.isSuccessful)null else if(range){r.body?.byteStream()?.readNBytesCompat(1024)} else r.body?.bytes()
                    }
                }
                if(link.type==ExtractorLinkType.VIDEO){
                    val prefix=get(link.url,true) ?: return@withContext false
                    return@withContext prefix.size>=12 && prefix.copyOfRange(4,8).toString(Charsets.US_ASCII)=="ftyp"
                }
                if(link.type!=ExtractorLinkType.M3U8)return@withContext false
                var url=link.url;var manifest=get(url)?.toString(Charsets.UTF_8) ?: return@withContext false
                repeat(3){
                    if(!manifest.trimStart().startsWith("#EXTM3U") || manifest.contains("SAMPLE-AES") || manifest.contains("KEYFORMAT="))return@withContext false
                    if(!manifest.contains("#EXT-X-STREAM-INF:"))return@repeat
                    val next=manifest.lineSequence().map{it.trim()}.firstOrNull{it.isNotEmpty()&&!it.startsWith("#")} ?: return@withContext false
                    url=url.toHttpUrlOrNull()?.resolve(next)?.toString() ?: return@withContext false
                    manifest=get(url)?.toString(Charsets.UTF_8) ?: return@withContext false
                }
                if(!manifest.contains("#EXTINF:"))return@withContext false
                var key:ByteArray?=null
                val sequence=Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(manifest)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var ivHex=java.lang.Long.toHexString(sequence).padStart(32,'0')
                for(tag in manifest.lines().takeWhile{it.isBlank() || it.startsWith("#")}.filter{it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-MAP:")}){
                    if(tag.contains("METHOD=NONE")){key=null;continue}
                    if(tag.startsWith("#EXT-X-KEY:") && !tag.contains("METHOD=AES-128"))return@withContext false
                    val resource=Regex("""URI="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@withContext false
                    val resourceUrl=url.toHttpUrlOrNull()?.resolve(resource)?.toString() ?: return@withContext false
                    val content=get(resourceUrl,tag.startsWith("#EXT-X-MAP:")) ?: return@withContext false
                    if(tag.startsWith("#EXT-X-KEY:")){
                        if(content.size!=16)return@withContext false
                        key=content
                        Regex("IV=0x([0-9a-fA-F]+)").find(tag)?.groupValues?.get(1)?.let{ivHex=it.padStart(32,'0')}
                    }
                    if(tag.startsWith("#EXT-X-MAP:") && content.size<8)return@withContext false
                }
                val next=manifest.lineSequence().map{it.trim()}.firstOrNull{it.isNotEmpty()&&!it.startsWith("#")} ?: return@withContext false
                val segment=url.toHttpUrlOrNull()?.resolve(next)?.toString() ?: return@withContext false
                val bytes=get(segment,true) ?: return@withContext false
                val plain=if(key!=null){
                    if(ivHex.length!=32 || bytes.size<16)return@withContext false
                    val iv=ivHex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
                    javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run{
                        init(javax.crypto.Cipher.DECRYPT_MODE,javax.crypto.spec.SecretKeySpec(key,"AES"),javax.crypto.spec.IvParameterSpec(iv))
                        doFinal(bytes.copyOf(bytes.size/16*16))
                    }
                }else bytes
                validMediaPrefix(plain)
            }catch(e:CancellationException){throw e}catch(_:Exception){false}
        }
        fun validMediaPrefix(bytes:ByteArray):Boolean {
            // MPEG-TS sniffing tolerates short CDN prefixes, like Android's TS extractor.
            if(bytes.size>=5*188 && (0..minOf(187,bytes.size-753)).any{i->(0..4).all{j->bytes[i+j*188]==0x47.toByte()}})return true
            if(bytes.size>=8 && bytes.copyOfRange(4,8).toString(Charsets.US_ASCII) in listOf("ftyp","styp","moof","sidx"))return true
            return false
        }
        private fun java.io.InputStream.readNBytesCompat(n:Int):ByteArray {
            val b=ByteArray(n);var total=0
            while(total<n){val k=read(b,total,n-total);if(k<0)break;total+=k}
            return b.copyOf(total)
        }
}
