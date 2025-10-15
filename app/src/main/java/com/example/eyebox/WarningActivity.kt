package com.example.eyebox

import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class WarningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        volumeControlStream = AudioManager.STREAM_ALARM

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warning)
        enterImmersiveMode()

        val root: View = findViewById(R.id.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, _ -> WindowInsetsCompat.CONSUMED }

        if (!AlarmPlayer.isPlaying()) {
            AlarmPlayer.play(applicationContext)
        }

        findViewById<TextView>(R.id.btnLanjut).setOnClickListener {
            AlarmPlayer.stop()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onStop() {
        super.onStop()
        AlarmPlayer.stop()
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        super.onDestroy()
    }
}