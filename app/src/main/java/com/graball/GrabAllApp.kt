package com.graball

import android.app.Application
import java.io.File

/**
 * v0.5.2 diagnosis: v0.5.1's catcher lived in MainActivity.onCreate,
 * but the app dies ~10/10 launches with no popup — so the crash
 * happens BEFORE that catcher runs (or is not a Java exception at
 * all). Installing the handler here, in Application.onCreate, runs
 * before ANY activity code: theme application, field initializers,
 * setContentView, everything. MainActivity still shows the saved
 * trace in a dialog on the next launch.
 */
class GrabAllApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val traceFile = File(filesDir, "last_crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                traceFile.writeText(e.toString() + "\n" + sw.toString().take(8000))
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
