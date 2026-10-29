package com.cinewave
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class CineWavePlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(CineWaveProvider(context)) }
}
