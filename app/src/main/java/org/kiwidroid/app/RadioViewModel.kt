package org.kiwidroid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kiwidroid.core.*

data class RadioUiState(
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
    val running: Boolean = false,
    val status: String = "Loading settings…",
    val metrics: SessionMetrics? = null,
    val message: String? = null,
)

class RadioViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val store = SettingsStore(application)
    private val mutableState = MutableStateFlow(RadioUiState())
    val state = mutableState.asStateFlow()
    private val tuning = MutableStateFlow(Tuning())
    private data class PlaybackRequest(val receiver: Receiver, val generation: Long)
    private var generation = 0L
    private val requests = MutableStateFlow<PlaybackRequest?>(null)
    private val saves = Channel<Settings>(Channel.CONFLATED)
    private val session = KiwiSession()
    private val focus = AudioFocus(application) { stop(it) }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        viewModelScope.launch {
            try {
                val settings = store.load()
                tuning.value = settings.tuning
                mutableState.update { it.copy(settings = settings, loaded = true, status = "Stopped") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loaded = true, status = "Stopped",
                    message = "Could not restore settings; using defaults: ${e.message}") }
            }
        }
        viewModelScope.launch {
            for (settings in saves) {
                try { store.save(settings) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutableState.update { it.copy(message = "Could not save settings: ${e.message}") }
                }
            }
        }
        viewModelScope.launch {
            // collectLatest cancels AND joins the old connection before running the replacement.
            requests.collectLatest { request ->
                if (request == null) return@collectLatest
                try {
                    focus.acquire()
                    withContext(Dispatchers.IO) {
                        session.run(request.receiver, tuning, AndroidAudioSink()) { metrics ->
                            viewModelScope.launch {
                                if (requests.value == request && state.value.running) {
                                    mutableState.update { it.copy(status = "Playing", metrics = metrics) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (requests.value == request) {
                        mutableState.update { it.copy(running = false, metrics = null, status = "Error",
                            message = e.message ?: "Connection failed") }
                    }
                } finally { focus.release() }
            }
        }
    }

    fun play() {
        if (!state.value.loaded || state.value.running) return
        mutableState.update { it.copy(running = true, status = "Connecting…", metrics = null, message = null) }
        requests.value = PlaybackRequest(state.value.settings.receiver, ++generation)
    }

    fun stop(reason: String = "Stopped") {
        requests.value = null
        mutableState.update { it.copy(running = false, status = reason, metrics = null) }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (state.value.running) stop("Stopped in background")
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        saves.close()
        focus.release()
        super.onCleared()
    }

    private fun change(transform: (Settings) -> Settings): Boolean = try {
        check(state.value.loaded) { "Settings are still loading" }
        val previous = state.value.settings
        val next = transform(previous)
        tuning.value = next.tuning
        mutableState.update { it.copy(settings = next, message = null) }
        saves.trySend(next)
        if (state.value.running && next.receiver != previous.receiver) {
            mutableState.update { it.copy(status = "Connecting…", metrics = null) }
            requests.value = PlaybackRequest(next.receiver, ++generation)
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
    fun setMode(mode: Mode) = change { it.copy(tuning = it.tuning.copy(mode = mode)) }
    fun setStep(text: String): Boolean = change {
        val step = text.toLongOrNull()
        require(step != null && step in 1..MAX_FREQUENCY_HZ) { "Step must be a whole number from 1 to 30000000 Hz" }
        it.copy(stepHz = step)
    }
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
