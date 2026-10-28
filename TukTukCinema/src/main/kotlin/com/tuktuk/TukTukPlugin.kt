package com.tuktuk
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class TukTukPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(TukTukProvider()) }
}
