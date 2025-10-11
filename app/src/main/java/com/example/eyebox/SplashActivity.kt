package com.example.eyebox

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()              // system splash -> kini nyaris tak terlihat
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)  // full-screen logo + teks kamu

        window.decorView.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1000) // 500–900ms supaya tulisan kebaca
    }
}
