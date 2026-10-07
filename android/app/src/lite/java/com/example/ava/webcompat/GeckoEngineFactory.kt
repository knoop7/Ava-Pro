package com.example.ava.webcompat

import android.content.Context

/** Default flavor: no GeckoView compiled in, so there is no GeckoView surface. */
object GeckoEngineFactory {
    fun create(context: Context): EngineSurface? = null
}
