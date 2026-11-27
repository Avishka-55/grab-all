package com.graball

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Lightweight zero-flicker trampoline preserving notification PendingIntents and
 * deep links while hosting Downloads inside [MainActivity]'s single-activity shell.
 */
class DownloadsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.createTabIntent(this, MainActivity.TAB_DOWNLOADS))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
