package com.cimanowvip

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** User-supplied final watch links only. Memory-only, bounded, short-lived and episode-specific. */
internal class VipWatchLinks(private val now:()->Long={System.nanoTime()/1000000},private val ttl:Long=10*60*1000){
    private val entries=linkedMapOf<String,Pair<Long,String>>()
    private fun key(raw:String):String?=VipParser.stable(raw)?.toHttpUrlOrNull()?.let{it.host+it.pathSegments.joinToString("/")}
    @Synchronized fun put(raw:String,expectedItem:String?=null):Boolean {
        val url=VipParser.site(raw.trim())?.toHttpUrlOrNull() ?: return false
        if(raw.length>8192||!url.encodedPath.endsWith("/watching/")||url.queryParameter("token").isNullOrBlank())return false
        val item=key(url.toString()) ?: return false
        if(expectedItem!=null&&key(expectedItem)!=item)return false
        entries.entries.removeAll{now()-it.value.first>=ttl}
        entries.remove(item);entries[item]=now() to url.newBuilder().fragment(null).build().toString()
        while(entries.size>16)entries.remove(entries.keys.first())
        return true
    }
    @Synchronized fun get(item:String):String? {
        val key=key(item) ?: return null;val value=entries[key] ?: return null
        if(now()-value.first>=ttl){entries.remove(key);return null}
        return value.second
    }
}
