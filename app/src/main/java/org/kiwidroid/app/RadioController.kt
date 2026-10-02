package org.kiwidroid.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.kiwidroid.core.*

data class RadioUiState(
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
    val running: Boolean = false,
    val status: String = "Loading settings…",
    val metrics: SessionMetrics? = null,
    val message: String? = null,
)

/** Process-scoped controls/settings; playback resources belong exclusively to PlaybackService. */
class RadioController(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SettingsStore(application)
    private val mutableState = MutableStateFlow(RadioUiState())
    val state = mutableState.asStateFlow()
    private val mutableTuning = MutableStateFlow(Tuning())
    internal val tuning = mutableTuning.asStateFlow()
    internal data class PlaybackRequest(val receiver: Receiver, val generation: Long)
    private var generation = 0L
    private val mutableRequests = MutableStateFlow<PlaybackRequest?>(null)
    internal val requests = mutableRequests.asStateFlow()
    private val saves = Channel<Settings>(Channel.CONFLATED)

    init {
        scope.launch {
            try {
                val settings = store.load()
                mutableTuning.value = settings.tuning
                mutableState.update { it.copy(settings = settings, loaded = true, status = "Stopped") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loaded = true, status = "Stopped",
                    message = "Could not restore settings; using defaults: ${e.message}") }
            }
        }
        scope.launch {
            for (settings in saves) {
                try { store.save(settings) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutableState.update { it.copy(message = "Could not save settings: ${e.message}") }
                }
            }
        }
    }

    fun play() {
        if (!state.value.loaded || state.value.running) return
        mutableState.update { it.copy(running = true, status = "Connecting…", metrics = null, message = null) }
        mutableRequests.value = PlaybackRequest(state.value.settings.receiver, ++generation)
        try {
            ContextCompat.startForegroundService(application, Intent(application, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY))
        } catch (e: Exception) {
            failed(checkNotNull(requests.value), e.message ?: "Could not start playback service")
        }
    }

    fun stop(reason: String = "Stopped") {
        mutableRequests.value = null
        mutableState.update { it.copy(running = false, status = reason, metrics = null) }
    }

    internal fun received(request: PlaybackRequest, metrics: SessionMetrics) {
        if (requests.value == request && state.value.running) {
            mutableState.update { it.copy(status = "Playing", metrics = metrics) }
        }
    }

    internal fun failed(request: PlaybackRequest, message: String) {
        if (requests.value == request) {
            mutableRequests.value = null
            mutableState.update { it.copy(running = false, metrics = null, status = "Error", message = message) }
        }
    }

    private fun change(transform: (Settings) -> Settings): Boolean = try {
        check(state.value.loaded) { "Settings are still loading" }
        val previous = state.value.settings
        val next = transform(previous)
        mutableTuning.value = next.tuning
        mutableState.update { it.copy(settings = next, message = null) }
        saves.trySend(next)
        if (state.value.running && next.receiver != previous.receiver) {
            mutableState.update { it.copy(status = "Connecting…", metrics = null) }
            mutableRequests.value = PlaybackRequest(next.receiver, ++generation)
        }
        true
    } catch (e: IllegalArgumentException) {
        mutableState.update { it.copy(message = e.message ?: "Invalid setting") }
        false
    } catch (e: ArithmeticException) {
        mutableState.update { it.copy(message = "Frequency is out of range") }
        false
    }

    fun tune(text: String): Boolean = change { it.copy(tuning = it.tuning.copy(frequencyHz = parseFrequencyKhz(text))) }
    fun step(direction: Int) = change { it.copy(tuning = it.tuning.step(it.stepHz * direction)) }
    fun tuneToStation(khz: Int) = change { it.copy(tuning = it.tuning.toBroadcastChannel(khz)) }
    fun setMode(mode: Mode) = change { it.copy(tuning = it.tuning.copy(mode = mode)) }
    fun setPassband(band: Passband) = change { it.copy(tuning = it.tuning.withPassband(band)) }
    fun setStep(text: String): Boolean = change {
        val step = text.toLongOrNull()
        require(step != null && step in 1..MAX_FREQUENCY_HZ) { "Step must be a whole number from 1 to 30000000 Hz" }
        it.copy(stepHz = step)
    }
    fun setSound(sound: SoundSettings) = change { it.copy(sound = sound) }
    fun setMwIncludePermits(include: Boolean) = change { it.copy(mwIncludePermits = include) }
    fun setRadioAudio(enabled: Boolean) = change { it.copy(sound = it.sound.copy(radioEnabled = enabled)) }
    fun setRssiTones(enabled: Boolean) = change { it.copy(sound = it.sound.copy(tones = it.sound.tones.copy(enabled = enabled))) }
    fun selectReceiver(index: Int) = change { it.copy(selected = index) }
    fun saveReceiver(index: Int?, name: String, url: String): Boolean = change {
        val receiver = Receiver(name.trim(), Receiver.endpoint(url.trim()).toString())
        val receivers = it.receivers.toMutableList()
        if (index == null) receivers.add(receiver) else receivers[index] = receiver
        it.copy(receivers = receivers)
    }
    fun removeReceiver(index: Int): Boolean = change {
        require(it.receivers.size > 1) { "Keep at least one receiver" }
        val receivers = it.receivers.toMutableList().apply { removeAt(index) }
        val selected = when {
            index < it.selected -> it.selected - 1
            index == it.selected -> index.coerceAtMost(receivers.lastIndex)
            else -> it.selected
        }
        it.copy(receivers = receivers, selected = selected)
    }
}
