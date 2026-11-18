package com.tapeaids.narrateme

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import org.opencv.android.OpenCVLoader

class NarrateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Every other Wearables call (including handleIntent in MainActivity.onCreate)
        // throws NOT_INITIALIZED until this has run, so do it before any activity starts.
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e("NarrateApp", "Wearables toolkit init failed: $error")
        }
        if (!OpenCVLoader.initLocal()) Log.e("NarrateApp", "OpenCV failed to load; page recognition won't work")
    }
}
