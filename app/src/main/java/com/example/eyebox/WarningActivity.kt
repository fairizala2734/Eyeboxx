package com.example.eyebox

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class WarningActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warning)

        // tombol volume mengatur stream alarm
        @Suppress("DEPRECATION")
        setVolumeControlStream(AudioManager.STREAM_ALARM)

        // mulai alarm
        startAlarm()

        findViewById<TextView>(R.id.btnLanjut).setOnClickListener {
            stopAlarm()
            finish()
        }
    }

    private fun startAlarm() {
        // agar tombol volume mengontrol alarm
        @Suppress("DEPRECATION")
        setVolumeControlStream(AudioManager.STREAM_ALARM)

        val mp = MediaPlayer()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM) // penting: pakai channel ALARM
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        mp.setAudioAttributes(attrs)

        // set data source dari /res/raw (bukan MediaPlayer.create)
        val afd = resources.openRawResourceFd(R.raw.alarm) // ogg/mp3/wav di res/raw
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()

        mp.isLooping = true
        mp.prepare()   // siap diputar
        mp.start()

        mediaPlayer = mp
    }

    private fun stopAlarm() {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        mediaPlayer = null
    }

    override fun onStop() {
        super.onStop()
        // pastikan alarm berhenti jika user keluar/back/home
        stopAlarm()
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
