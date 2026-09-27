package org.kiwidroid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.kiwidroid.core.Mode

/** Recreated activities attach to the same controls and active foreground service. */
class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val radio = (application as KiwiApplication).radio
    val state = radio.state
    fun play() = radio.play()
    fun stop() = radio.stop()
    fun tune(text: String) = radio.tune(text)
    fun step(direction: Int) = radio.step(direction)
    fun setMode(mode: Mode) = radio.setMode(mode)
    fun setStep(text: String) = radio.setStep(text)
    fun selectReceiver(index: Int) = radio.selectReceiver(index)
    fun saveReceiver(index: Int?, name: String, url: String) = radio.saveReceiver(index, name, url)
    fun removeReceiver(index: Int) = radio.removeReceiver(index)
}
