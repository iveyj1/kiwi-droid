package org.kiwidroid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Temporary interruptions silence output without losing the live receiver connection. */
class AudioFocus(private val context: Context, private val stop: (String) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var registered = false
    @Volatile var interrupted = false
        private set
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> stop("Headphones disconnected")
            }
        }
    }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (registered) handleChange(change)
        }, Handler(Looper.getMainLooper()))
        .setWillPauseWhenDucked(true)
        .build()

    internal fun handleChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> interrupted = false
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> interrupted = true
            AudioManager.AUDIOFOCUS_LOSS -> {
                interrupted = true
                stop("Audio focus lost")
            }
        }
    }

    fun acquire() {
        interrupted = false
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio focus unavailable; stop other audio and try again"
        }
        if (!registered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    fun release() {
        interrupted = true
        if (registered) {
            registered = false
            context.unregisterReceiver(receiver)
        }
        manager.abandonAudioFocusRequest(request)
    }
}
