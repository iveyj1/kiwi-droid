package org.kiwidroid.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.time.Instant
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
    /** Neutral confirmation (not an error), e.g. a finished export. */
    val notice: String? = null,
    val audibleReceiver: Receiver? = null,
    val audibleTuning: Tuning? = null,
    val retainedReceiver: Receiver? = null,
    val previousFrequencyHz: Long? = null,
    val previousReceiverUrl: String? = null,
) {
    val transitioning: Boolean get() = running && (
        audibleReceiver?.baseUrl != settings.receiver.baseUrl || audibleTuning?.command() != settings.tuning.command() ||
            metrics == null || metrics.tuningCommand != settings.tuning.command() || metrics.settling || metrics.stale ||
            metrics.playback?.buffering == true)
}

/** Process-scoped controls/settings; playback resources belong exclusively to PlaybackService. */
class RadioController(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SettingsStore(application)
    private val mutableState = MutableStateFlow(RadioUiState())
    val state = mutableState.asStateFlow()
    internal data class PlaybackRequest(val receiver: Receiver, val generation: Long, val cycle: Long)
    private var generation = 0L
    private var playbackCycle = 0L
    private val mutableRequests = MutableStateFlow<PlaybackRequest?>(null)
    internal val requests = mutableRequests.asStateFlow()
    private val saves = Channel<Settings>(Channel.CONFLATED)
    /** Fresh RSSI readings for the current tuning only; cleared on retune and stop. */
    private val rssiWindow = RssiWindow()
    val logbook = Logbook(File(application.filesDir, "logbook.jsonl"))

    init {
        scope.launch {
            try {
                val settings = store.load()
                mutableState.update { it.copy(settings = settings, loaded = true, status = "Stopped") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loaded = true, status = "Stopped",
                    message = "Could not restore settings; using defaults: ${e.message}") }
            }
        }
        scope.launch {
            try { logbook.load() } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(message = "Could not read the logbook: ${e.message}") }
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
        mutableRequests.value = PlaybackRequest(state.value.settings.receiver, ++generation, ++playbackCycle)
        try {
            ContextCompat.startForegroundService(application, Intent(application, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY))
        } catch (e: Exception) {
            failed(checkNotNull(requests.value), e.message ?: "Could not start playback service")
        }
    }

    fun stop(reason: String = "Stopped") {
        mutableRequests.value = null
        rssiWindow.clear()
        mutableState.update { it.copy(running = false, status = reason, metrics = null,
            audibleReceiver = null, audibleTuning = null, retainedReceiver = null) }
    }

    internal fun playbackChanged(playback: ReceiverPlayback) {
        if (!state.value.running) return
        if (playback.receiver == null && playback.connecting == null && playback.error != null) {
            requests.value?.let { failed(it, checkNotNull(playback.error)) }
            return
        }
        val old = state.value
        if (old.audibleReceiver != playback.receiver || old.audibleTuning?.command() != playback.tuning?.command()) rssiWindow.clear()
        val metrics = playback.metrics
        if (metrics != null && !metrics.stale && !metrics.settling && metrics.tuningCommand == playback.tuning?.command()) metrics.rssiDbm?.let { rssiWindow.add(SystemClock.elapsedRealtime(), it) }
        var settings = old.settings
        if (playback.error != null && playback.receiver != null && playback.connecting == null) {
            val index = settings.receivers.indexOf(playback.receiver)
            if (index >= 0) {
                val audible = playback.tuning ?: settings.tuning
                val bands = settings.tuning.passbands + (audible.mode to audible.passband)
                settings = settings.copy(selected = index, tuning = audible.copy(passbands = bands))
                saves.trySend(settings)
            }
        }
        mutableState.update { it.copy(settings = settings, metrics = metrics,
            previousReceiverUrl = if (settings.receiver.baseUrl != old.settings.receiver.baseUrl) old.settings.receiver.locationKey else it.previousReceiverUrl,
            previousFrequencyHz = if (settings.tuning.frequencyHz != old.settings.tuning.frequencyHz) old.settings.tuning.frequencyHz else it.previousFrequencyHz,
            audibleReceiver = playback.receiver, audibleTuning = playback.tuning, retainedReceiver = playback.retained,
            status = if (playback.connecting != null) {
                (playback.receiver?.let { receiver -> "Listening to ${receiver.name} · " } ?: "") + "Connecting to ${playback.connecting?.name}…"
            } else if (playback.receiver != null) "Playing" else "Connecting…",
            message = playback.error ?: it.message) }
    }

    internal fun failed(request: PlaybackRequest, message: String) {
        if (requests.value == request) {
            mutableRequests.value = null
            rssiWindow.clear()
            mutableState.update { it.copy(running = false, metrics = null, status = "Error", message = message,
                audibleReceiver = null, audibleTuning = null, retainedReceiver = null) }
        }
    }

    private fun change(transform: (Settings) -> Settings): Boolean = try {
        check(state.value.loaded) { "Settings are still loading" }
        val previous = state.value.settings
        val next = transform(previous)
        if (next.tuning.command() != previous.tuning.command()) rssiWindow.clear()
        mutableState.update { it.copy(settings = next, message = null, notice = null,
            previousFrequencyHz = if (next.tuning.frequencyHz != previous.tuning.frequencyHz) previous.tuning.frequencyHz else it.previousFrequencyHz,
            previousReceiverUrl = if (next.receiver.baseUrl != previous.receiver.baseUrl) previous.receiver.locationKey else it.previousReceiverUrl) }
        saves.trySend(next)
        if (state.value.running && next.receiver != previous.receiver) {
            mutableState.update { it.copy(status = "Connecting to ${next.receiver.name}…") }
            mutableRequests.value = PlaybackRequest(next.receiver, ++generation, playbackCycle)
        }
        true
    } catch (e: IllegalArgumentException) {
        mutableState.update { it.copy(message = e.message ?: "Invalid setting") }
        false
    } catch (e: ArithmeticException) {
        mutableState.update { it.copy(message = "Frequency is out of range") }
        false
    }

    fun previousFrequency(): Boolean = state.value.previousFrequencyHz?.let { hz ->
        change { it.copy(tuning = it.tuning.copy(frequencyHz = hz)) }
    } ?: false
    fun previousReceiver(): Boolean {
        val index = state.value.settings.receivers.indexOfFirst { it.locationKey == state.value.previousReceiverUrl }
        return if (index >= 0) selectReceiver(index) else false
    }
    fun complementMode(): Boolean = when (state.value.settings.tuning.mode) {
        Mode.USB -> setMode(Mode.LSB)
        Mode.LSB -> setMode(Mode.USB)
        else -> false
    }

    fun tune(text: String): Boolean = change { it.copy(tuning = it.tuning.copy(frequencyHz = parseFrequencyKhz(text))) }
    fun step(direction: Int) = change { it.copy(tuning = it.tuning.step(it.stepHz * direction)) }
    fun tuneToStation(khz: Int) = change { it.copy(tuning = it.tuning.toBroadcastChannel(khz)) }
    fun tuneTo(frequencyHz: Long, mode: Mode) = change { it.copy(tuning = it.tuning.copy(frequencyHz = frequencyHz, mode = mode)) }

    /** Snapshot of what is being heard now; the editor adds station, confidence and notes. */
    fun draftLog(): LogEntry {
        val settings = state.value.settings
        val receiver = state.value.audibleReceiver ?: settings.receiver
        val tuning = state.value.audibleTuning ?: settings.tuning
        val rssi = rssiWindow.snapshot(SystemClock.elapsedRealtime())
        return LogEntry(utc = Instant.now(), receiverName = receiver.name, receiverUrl = receiver.url,
            receiverLocation = settings.locationOf(receiver), frequencyHz = tuning.frequencyHz, mode = tuning.mode,
            passband = tuning.passband, rssiDbm = rssi?.first, rssiMinDbm = rssi?.second, rssiMaxDbm = rssi?.third,
            station = null, confidence = LogConfidence.UNIDENTIFIED)
    }

    private fun logbookAction(failure: String, action: suspend () -> Unit) = scope.launch {
        try { action() } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(message = "$failure: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun saveLog(entry: LogEntry) = logbookAction("Could not save the log entry") {
        if (logbook.entries.value.any { it.id == entry.id }) logbook.replace(entry) else logbook.add(entry)
    }
    fun deleteLog(id: String) = logbookAction("Could not delete the log entry") { logbook.delete(id) }

    /** Writes every entry as CSV to a document the user chose through the system file picker. */
    fun exportLog(uri: Uri) = logbookAction("Could not export the logbook") {
        val entries = logbook.entries.value
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val stream = checkNotNull(application.contentResolver.openOutputStream(uri, "wt")) { "No output stream" }
            stream.bufferedWriter(Charsets.UTF_8).use { LogCsv.write(entries, it) }
        }
        mutableState.update { it.copy(notice = "Exported ${entries.size} log entries", message = null) }
    }
    fun setMode(mode: Mode) = change { it.copy(tuning = it.tuning.copy(mode = mode)) }
    fun setPassband(band: Passband) = change { it.copy(tuning = it.tuning.withPassband(band)) }
    fun setStep(text: String): Boolean = change {
        val step = text.toLongOrNull()
        require(step != null && step in 1..MAX_FREQUENCY_HZ) { "Step must be a whole number from 1 to 30000000 Hz" }
        it.copy(stepHz = step)
    }
    fun setKeepReceiverSeconds(seconds: Int) = change { it.copy(keepReceiverSeconds = seconds) }
    fun setSound(sound: SoundSettings) = change { it.copy(sound = sound) }
    fun setMwIncludePermits(include: Boolean) = change { it.copy(mwIncludePermits = include) }
    fun setMwIncludeSilent(include: Boolean) = change { it.copy(mwIncludeSilent = include) }
    fun setMwIncludeUnverified(include: Boolean) = change { it.copy(mwIncludeUnverified = include) }

    /** Reports a bad entry (hidden everywhere but the Hidden view), or restores it. */
    fun toggleHidden(station: MwStation): Boolean = change {
        val rest = it.mwHidden.filterNot { ref -> ref.matches(station) }
        if (rest.size < it.mwHidden.size) it.copy(mwHidden = rest)
        else {
            require(rest.size < Settings.MAX_HIDDEN) { "At most ${Settings.MAX_HIDDEN} hidden stations" }
            it.copy(mwHidden = rest + MwFavorite.of(station))
        }
    }
    fun setRadioAudio(enabled: Boolean) = change { it.copy(sound = it.sound.copy(radioEnabled = enabled)) }
    fun setRssiTones(enabled: Boolean) = change { it.copy(sound = it.sound.copy(tones = it.sound.tones.copy(enabled = enabled))) }
    fun selectReceiver(index: Int) = change { it.copy(selected = index) }
    /** Blank latitude and longitude clear the location; editing only the location does not reconnect. */
    fun saveReceiver(index: Int?, name: String, url: String, latitude: String = "", longitude: String = ""): Boolean = change {
        val endpoint = Receiver.endpoint(url.trim())
        val previous = index?.let { i -> it.receivers[i] }
        // Keep the stored spelling (e.g. no trailing slash) when unchanged, so the active session is not replaced.
        val receiver = Receiver(name.trim(), previous?.url?.takeIf { old -> Receiver.endpoint(old) == endpoint } ?: endpoint.toString())
        val location = if (latitude.isBlank() && longitude.isBlank()) null else GeoPoint.parse(latitude, longitude)
        val receivers = it.receivers.toMutableList()
        val locations = it.receiverLocations.toMutableMap()
        if (index == null) receivers.add(receiver) else locations.remove(receivers.set(index, receiver).locationKey)
        if (location == null) locations.remove(receiver.locationKey) else locations[receiver.locationKey] = location
        it.copy(receivers = receivers, receiverLocations = locations)
    }
    fun removeReceiver(index: Int): Boolean = change {
        require(it.receivers.size > 1) { "Keep at least one receiver" }
        val receivers = it.receivers.toMutableList().apply { removeAt(index) }
        val selected = when {
            index < it.selected -> it.selected - 1
            index == it.selected -> index.coerceAtMost(receivers.lastIndex)
            else -> it.selected
        }
        it.copy(receivers = receivers, selected = selected, receiverLocations = it.receiverLocations - it.receivers[index].locationKey)
    }

    fun toggleFavorite(station: MwStation): Boolean = change {
        val existing = it.favorites.filterNot { favorite -> favorite.matches(station) }
        if (existing.size < it.favorites.size) it.copy(favorites = existing)
        else {
            require(existing.size < Settings.MAX_FAVORITES) { "At most ${Settings.MAX_FAVORITES} favorites" }
            it.copy(favorites = existing + MwFavorite.of(station))
        }
    }

    /** One explicit `/status` request for the URL being edited; nothing is saved until the user saves. */
    suspend fun lookupReceiverLocation(url: String): GeoPoint {
        val fields = ReceiverStatus.fetch(Receiver("Lookup", Receiver.endpoint(url.trim()).toString()))
        return requireNotNull(ReceiverStatus.location(fields)) { "This receiver does not publish its GPS location" }
    }
}
