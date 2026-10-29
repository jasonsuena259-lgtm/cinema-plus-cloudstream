package com.akwcam
import android.content.Context
import com.lagradost.cloudstream3.plugins.*
@CloudstreamPlugin
class AkwCamPlugin:Plugin(){override fun load(context:Context){registerMainAPI(AkwCamProvider(context.applicationContext))}}
