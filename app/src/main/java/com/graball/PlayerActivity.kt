package com.graball

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Lightweight zero-flicker trampoline preserving Android's "Open with GrabAll Music"
 * (`ACTION_VIEW`) intent filters, notification PendingIntents, and deep links while
 * hosting the actual Music Player UI inside [MainActivity]'s single-activity shell.
 */
class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToMain(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardToMain(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun forwardToMain(incoming: Intent?) {
        val target = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MUSIC)
            if (incoming != null) {
                incoming.extras?.let { putExtras(it) }
                if (incoming.action == Intent.ACTION_VIEW && incoming.data != null) {
                    action = Intent.ACTION_VIEW
                    setDataAndType(incoming.data, incoming.type)
                    if ((incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    incoming.clipData?.let { clipData = it }
                }
            }
        }
        startActivity(target)
    }

    companion object {
        const val EXTRA_OPEN_FOLDER_PATH = "open_folder_path"
        const val EXTRA_OPEN_FOLDER_NAME = "open_folder_name"
    }
}
