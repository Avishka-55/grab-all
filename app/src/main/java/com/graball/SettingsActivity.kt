package com.graball

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.CookieImporter
import com.graball.engine.RuntimePaths

/**
 * Settings, real since v0.5.9. The YouTube session import moved here
 * from the main screen: the main screen is for downloading, and the
 * session is a setting, not a download action.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cookiesStatus: TextView

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val result = CookieImporter.importCookies(this, uri)
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.ok) {
                    cookiesStatus.text = getString(R.string.cookies_saved)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cookiesStatus = findViewById(R.id.settingsCookiesStatus)
        findViewById<Button>(R.id.settingsBack).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.settingsImportButton).setOnClickListener {
            importLauncher.launch("*/*")
        }
    }

    override fun onResume() {
        super.onResume()
        cookiesStatus.text = if (RuntimePaths.cookiesFile(this).exists()) {
            getString(R.string.cookies_saved)
        } else {
            getString(R.string.cookies_missing)
        }
    }
}
