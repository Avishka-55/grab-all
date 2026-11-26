package com.graball.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny in-process progress bus from DownloadService to the Downloads
 * screen's queue.
 *
 * The service posts states from its worker thread; listeners always run on
 * the main thread. The latest state per URL is kept (see lastStates), so
 * an activity that (re)creates can replay them and rebuild its rows.
 * Unsubscribe in onPause to avoid leaking the activity.
 *
 * Modes is the exception to "no history": whoever starts a download
 * records its mode label ("720p", "Audio only · First 20") here, so
 * the Downloads screen can label a row whose start it never saw.
 */
object DownloadProgressBus {

    enum class Status { PREPARING, DOWNLOADING, COMPLETE, FAILED, PAUSED }

    data class State(
        /** Queue id — currently the download URL. */
        val id: String,
        /** 0..100 while downloading; 100 on complete; null on failure. */
        val percent: Float?,
        val status: Status,
        /** Human-readable failure reason; only set when status is FAILED. */
        val detail: String? = null,
        /** Playlist position ("Item 5 of 20"); only while DOWNLOADING. */
        val item: String? = null,
        /**
         * Latest published file for this row, relative to
         * Downloads/GrabAll ("video.mp4", "My Playlist/01 - Song.mp3").
         * Set on COMPLETE; the Downloads screen's Open button uses it.
         */
        val output: String? = null,
        /** Friendly display title (video / playlist name instead of raw URL). */
        val title: String? = null
    )

    /** Mode label per URL, set at download start (see above). */
    val modes = mutableMapOf<String, String>()

    /** Display title per URL (video or playlist title), updated as soon as known. */
    val titles = mutableMapOf<String, String>()

    /**
     * Latest state per URL. The bus keeps no history of events, but a
     * Downloads screen that opens mid-download still needs to know a
     * row exists: on subscribe it replays these, so a download stuck
     * in "Preparing…" (or already downloading) shows a row at once
     * instead of only on the next progress tick.
     */
    private val lastStates = mutableMapOf<String, State>()

    fun snapshot(): List<State> = lastStates.values.toList()

    /** Count of currently active (preparing / downloading / paused) jobs. */
    fun activeCount(): Int = lastStates.values.count {
        it.status == Status.PREPARING ||
            it.status == Status.DOWNLOADING ||
            it.status == Status.PAUSED
    }

    fun remove(id: String) {
        val removed = lastStates.remove(id)
        modes.remove(id)
        titles.remove(id)
        if (removed != null) {
            notifyCountListeners()
        }
    }

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val countListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun subscribe(listener: (State) -> Unit) {
        listeners += listener
    }

    fun unsubscribe(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun subscribeActiveCount(listener: (Int) -> Unit) {
        countListeners += listener
        listener(activeCount())
    }

    fun unsubscribeActiveCount(listener: (Int) -> Unit) {
        countListeners -= listener
    }

    private fun notifyCountListeners() {
        val count = activeCount()
        mainHandler.post {
            countListeners.forEach { it(count) }
        }
    }

    fun post(state: State) {
        val resolvedTitle = state.title?.takeIf { it.isNotBlank() } ?: titles[state.id]
        if (!resolvedTitle.isNullOrBlank()) {
            titles[state.id] = resolvedTitle
        }
        val enriched = if (state.title == resolvedTitle) state else state.copy(title = resolvedTitle)
        lastStates[enriched.id] = enriched
        val count = activeCount()
        mainHandler.post {
            listeners.forEach { it(enriched) }
            countListeners.forEach { it(count) }
        }
    }
}
