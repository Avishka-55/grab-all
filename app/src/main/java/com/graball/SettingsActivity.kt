package com.graball

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Lightweight zero-flicker trampoline routing into Settings inside
 * [MainActivity]'s single-activity bottom-nav shell.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.createTabIntent(this, MainActivity.TAB_SETTINGS))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
