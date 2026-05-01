package com.epher.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.epher.app.retention.RetentionCleanupWorker

class EpherApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RetentionCleanupWorker.schedule(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "App terminating, cleaning up resources")
        container.cleanup()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "Critical memory pressure detected")
        }
    }

    private companion object {
        const val TAG = "EpherApplication"
    }
}
