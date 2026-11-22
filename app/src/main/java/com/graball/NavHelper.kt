package com.graball

import android.app.Activity
import android.content.Intent

/**
 * Smooth, zero-flicker bottom-navigation tab switcher shared across all
 * top-level GrabAll screens (Grab, Music, Downloads, Settings, Status).
 *
 * Uses REORDER_TO_FRONT + a crisp 115ms top-layer cross-dissolve so the
 * outgoing screen stays opaque underneath (zero flash, bottom bar stays steady).
 */
object NavHelper {

    fun switchTab(from: Activity, target: Class<out Activity>) {
        if (from.javaClass == target) return
        val intent = Intent(from, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        from.startActivity(intent)
        @Suppress("DEPRECATION")
        from.overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit)
    }
}
