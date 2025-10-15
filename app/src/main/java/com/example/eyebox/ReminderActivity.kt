package com.example.eyebox

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class ReminderActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "eyebox_prefs"
        private const val KEY_LAST_SESSION_MICROSLEEP = "last_session_microsleep"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)
        enterImmersiveMode()

        val root = findViewById<View>(R.id.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, _ -> WindowInsetsCompat.CONSUMED }

        window.setBackgroundDrawable(ColorDrawable(0x00000000))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.35f)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hadMicrosleep = prefs.getBoolean(KEY_LAST_SESSION_MICROSLEEP, false)

        val tv = findViewById<TextView>(R.id.tvReminder)
        tv.text = if (hadMicrosleep) {
            "Pada sesi sebelumnya terdeteksi tanda microsleep.\n\nPastikan Anda cukup istirahat sebelum melanjutkan."
        } else {
            "Sesi sebelumnya aman untuk berkendara.\n\nTetap waspada dan pastikan Anda cukup istirahat sebelum melanjutkan."
        }

        findViewById<TextView>(R.id.btnLanjutkan).setOnClickListener {
            prefs.edit().putBoolean(KEY_LAST_SESSION_MICROSLEEP, false).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }
}