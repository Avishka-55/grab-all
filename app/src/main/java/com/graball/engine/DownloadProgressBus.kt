package com.graball.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny in-process progress bus from DownloadService to MainActivity's queue.
 *
 * The service posts states from its worker thread; listeners always run on
 * the main thread. No history is kept: an activity that (re)creates only
 * sees states posted after it subscribes. Unsubscribe in onPause to avoid
 * leaking the activity.
 */
object DownloadProgressBus {

    enum class Status { DOWNLOADING, COMPLETE, FAILED }

    data class State(
        /** Queue id — currently the download URL. */
        val id: String,
        /** 0..100 while downloading; 100 on complete; null on failure. */
        val percent: Float?,
        val status: Status,
        /** Human-readable failure reason; only set when status is FAILED. */
        val detail: String? = null
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun subscribe(listener: (State) -> Unit) {
        listeners += listener
    }

    fun unsubscribe(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun post(state: State) {
        mainHandler.post {
            listeners.forEach { it(state) }
        }
    }
}
