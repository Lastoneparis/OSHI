package com.oshi.messenger.wear

import android.content.Context
import com.oshi.messenger.wear.data.WearSource

/** Release builds have no sample data: the phone is the only source. */
object DebugSources {
    fun fake(context: Context): WearSource? = null
}
