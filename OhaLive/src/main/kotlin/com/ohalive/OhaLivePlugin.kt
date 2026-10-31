package com.ohalive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OhaLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OhaLiveProvider())
    }
}
