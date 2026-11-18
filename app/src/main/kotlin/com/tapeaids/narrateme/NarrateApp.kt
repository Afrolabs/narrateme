package com.tapeaids.narrateme

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class NarrateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Every other Wearables call (including handleIntent in MainActivity.onCreate)
        // throws NOT_INITIALIZED until this has run, so do it before any activity starts.
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e("NarrateApp", "Wearables toolkit init failed: $error")
        }
    }
}
