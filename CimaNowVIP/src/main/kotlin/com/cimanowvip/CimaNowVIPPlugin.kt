package com.cimanowvip
import android.content.Context
import com.lagradost.cloudstream3.plugins.*
@CloudstreamPlugin
class CimaNowVIPPlugin:Plugin(){
    override fun load(context:Context){
        val session=VipSession(context.applicationContext)
        registerMainAPI(CimaNowVIPProvider(session))
        openSettings={ctx->session.login(ctx)}
    }
}
