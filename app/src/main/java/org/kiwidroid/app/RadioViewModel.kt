package org.kiwidroid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.kiwidroid.core.Mode
import org.kiwidroid.core.MwStation
import org.kiwidroid.core.Passband
import org.kiwidroid.core.SoundSettings

/** Recreated activities attach to the same controls and active foreground service. */
class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val radio = (application as KiwiApplication).radio
    val state = radio.state
    val stations = (application as KiwiApplication).stations.state
    fun play() = radio.play()
    fun stop() = radio.stop()
    fun tune(text: String) = radio.tune(text)
    fun step(direction: Int) = radio.step(direction)
    fun tuneToStation(khz: Int) = radio.tuneToStation(khz)
    fun setMode(mode: Mode) = radio.setMode(mode)
    fun setPassband(band: Passband) = radio.setPassband(band)
    fun setStep(text: String) = radio.setStep(text)
    fun setSound(sound: SoundSettings) = radio.setSound(sound)
    fun setMwIncludePermits(include: Boolean) = radio.setMwIncludePermits(include)
    fun setRadioAudio(enabled: Boolean) = radio.setRadioAudio(enabled)
    fun setRssiTones(enabled: Boolean) = radio.setRssiTones(enabled)
    fun selectReceiver(index: Int) = radio.selectReceiver(index)
    fun saveReceiver(index: Int?, name: String, url: String, latitude: String, longitude: String) =
        radio.saveReceiver(index, name, url, latitude, longitude)
    fun toggleFavorite(station: MwStation) = radio.toggleFavorite(station)
    suspend fun lookupReceiverLocation(url: String) = radio.lookupReceiverLocation(url)
    fun removeReceiver(index: Int) = radio.removeReceiver(index)
}
