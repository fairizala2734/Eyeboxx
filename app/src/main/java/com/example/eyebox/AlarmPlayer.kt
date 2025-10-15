package com.example.eyebox

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

object AlarmPlayer {
    @Volatile private var mp: MediaPlayer? = null

    @Synchronized
    fun play(ctx: Context) {
        if (mp != null) return
        try {
            val afd = ctx.resources.openRawResourceFd(R.raw.alarm) ?: return
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    isLooping = true
                    setOnErrorListener { p, _, _ ->
                        try { p.reset(); p.release() } catch (_: Throwable) {}
                        mp = null
                        true
                    }
                    prepare()
                    start()
                }
                mp = player
            } finally {
                afd.close()
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            stop()
        }
    }

    @Synchronized
    fun stop() {
        try {
            mp?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (_: Throwable) {
            // ignore
        } finally {
            mp = null
        }
    }

    fun isPlaying(): Boolean = mp?.isPlaying == true
}