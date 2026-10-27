package com.cinemaplus

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemaPlusPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(CinemaPlusProvider()) }
}
