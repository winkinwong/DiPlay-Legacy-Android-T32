package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import androidx.multidex.MultiDexApplication

class DiPlayApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        ProcessVitals.start(this)
    }

    /** LMK evidence: a trim shortly before the log goes silent is what a system kill looks like. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ProcessVitals.recordTrim(this, level)
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(this, thread, throwable)
            } catch (ignored: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val TAG = "DiPlayApplication"

        fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
            Log.e(TAG, "FATAL EXCEPTION", throwable)
            // Redacted, bounded and readable by the exported report even after a restart.
            CrashDiagnostics.recordInAppDirectories(context, thread, throwable)
        }
    }
}
