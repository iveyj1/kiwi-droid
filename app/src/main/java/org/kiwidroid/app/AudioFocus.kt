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

/** Background playback still stops for lost audio focus or a disconnected audio route. */
class AudioFocus(private val context: Context, private val stop: (String) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var registered = false
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
            if (registered && change != AudioManager.AUDIOFOCUS_GAIN) stop("Audio focus lost")
        }, Handler(Looper.getMainLooper()))
        .setWillPauseWhenDucked(true)
        .build()

    fun acquire() {
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
        manager.abandonAudioFocusRequest(request)
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }
}
