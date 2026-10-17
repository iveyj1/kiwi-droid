package org.kiwidroid.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.kiwidroid.core.*

private val Context.settingsDataStore by preferencesDataStore("radio")

data class Settings(
    val receivers: List<Receiver> = DEFAULT_RECEIVERS,
    val selected: Int = 0,
    val tuning: Tuning = Tuning(),
    val stepHz: Long = 100,
    val keepReceiverSeconds: Int = 0,
    val sound: SoundSettings = SoundSettings(),
    /** Show US construction permits (not yet on air) alongside licensed and notified MW stations. */
    val mwIncludePermits: Boolean = false,
    /** Show stations the FCC lists as licensed but silent. */
    val mwIncludeSilent: Boolean = false,
    /** Show treaty records that no national list confirms. */
    val mwIncludeUnverified: Boolean = false,
    /** Entries the user reported as bad; hidden everywhere except the Hidden view. */
    val mwHidden: List<MwFavorite> = emptyList(),
    /** Antenna locations keyed by normalized receiver URL, kept outside [Receiver] so editing one never reconnects. */
    val receiverLocations: Map<String, GeoPoint> = emptyMap(),
    val favorites: List<MwFavorite> = emptyList(),
) {
    init {
        require(receivers.isNotEmpty() && receivers.size <= 50)
        require(selected in receivers.indices)
        require(stepHz in 1..MAX_FREQUENCY_HZ)
        require(keepReceiverSeconds in 0..600) { "Receiver retention must be 0–600 seconds" }
        require(receivers.map { it.baseUrl }.distinct().size == receivers.size) { "Receiver URL already exists" }
        require(favorites.size <= MAX_FAVORITES) { "At most $MAX_FAVORITES favorites" }
        require(favorites.map { it.facilityId to it.khz }.distinct().size == favorites.size) { "Duplicate favorite" }
        require(mwHidden.size <= MAX_HIDDEN) { "At most $MAX_HIDDEN hidden stations" }
    }
    val receiver get() = receivers[selected]
    val receiverLocation: GeoPoint? get() = locationOf(receiver)
    fun locationOf(receiver: Receiver): GeoPoint? = receiverLocations[receiver.locationKey]

    val mwVisibility: MwVisibility get() = MwVisibility(mwIncludePermits, mwIncludeSilent, mwIncludeUnverified, mwHidden)

    companion object {
        const val MAX_FAVORITES = 200
        const val MAX_HIDDEN = 2000
    }
}

/** `http://host:8073` and `http://host:8073/` are the same receiver. */
val Receiver.locationKey: String get() = baseUrl.toString()

internal object SettingsCodec {
    fun encode(settings: Settings): String = buildJsonObject {
        put("version", 1)
        put("receivers", buildJsonArray {
            settings.receivers.forEach { receiver -> add(buildJsonObject {
                put("name", receiver.name); put("url", receiver.url)
            }) }
        })
        put("selected", settings.selected)
        put("frequencyHz", settings.tuning.frequencyHz)
        put("mode", settings.tuning.mode.name)
        put("passbands", buildJsonObject {
            settings.tuning.passbands.forEach { (mode, band) -> put(mode.name, buildJsonObject {
                put("lowHz", band.lowHz); put("highHz", band.highHz)
            }) }
        })
        put("stepHz", settings.stepHz)
        put("keepReceiverSeconds", settings.keepReceiverSeconds)
        put("mwIncludePermits", settings.mwIncludePermits)
        put("receiverLocations", buildJsonObject {
            settings.receiverLocations.forEach { (url, point) -> put(url, buildJsonObject {
                put("lat", point.latitude); put("lon", point.longitude)
            }) }
        })
        put("favorites", stationRefs(settings.favorites))
        put("mwIncludeSilent", settings.mwIncludeSilent)
        put("mwIncludeUnverified", settings.mwIncludeUnverified)
        put("mwHidden", stationRefs(settings.mwHidden))
        put("sound", buildJsonObject {
            put("radioEnabled", settings.sound.radioEnabled)
            put("radioVolume", settings.sound.radioVolumePercent)
            val tones = settings.sound.tones
            put("tonesEnabled", tones.enabled)
            put("points", buildJsonArray { tones.points.forEach { point -> add(buildJsonObject {
                put("dbm", point.dbm); put("midi", point.midi)
            }) } })
            put("scale", tones.scale.name)
            put("root", tones.root)
            put("customOffsets", buildJsonArray { tones.customOffsets.forEach { add(it) } })
            put("volume", tones.volumePercent)
            put("durationMs", tones.durationMs)
            put("intervalMs", tones.intervalMs)
            put("fadeMs", tones.fadeMs)
            put("smoothingMs", tones.smoothingMs)
            put("hysteresisDb", tones.hysteresisDb)
            put("waveform", tones.waveform.name)
            put("latchMode", tones.latchMode.name)
            put("latchHoldMs", tones.latchHoldMs)
            put("latchThresholdDb", tones.latchThresholdDb)
        })
    }.toString()

    fun decode(text: String): Settings {
        val obj = Json.parseToJsonElement(text).jsonObject
        require(obj.getValue("version").jsonPrimitive.int == 1) { "Unknown settings version" }
        return Settings(
            receivers = obj.getValue("receivers").jsonArray.map {
                Receiver(it.jsonObject.getValue("name").jsonPrimitive.content,
                    it.jsonObject.getValue("url").jsonPrimitive.content)
            },
            selected = obj.getValue("selected").jsonPrimitive.int,
            tuning = Tuning(obj.getValue("frequencyHz").jsonPrimitive.long,
                Mode.valueOf(obj.getValue("mode").jsonPrimitive.content),
                passbands = Mode.entries.associateWith { mode ->
                    obj["passbands"]?.jsonObject?.get(mode.name)?.jsonObject?.let {
                        Passband(it.getValue("lowHz").jsonPrimitive.int, it.getValue("highHz").jsonPrimitive.int)
                    } ?: mode.defaultPassband
                }),
            stepHz = obj.getValue("stepHz").jsonPrimitive.long,
            keepReceiverSeconds = obj["keepReceiverSeconds"]?.jsonPrimitive?.int ?: 0,
            sound = obj["sound"]?.jsonObject?.let(::decodeSound) ?: SoundSettings(),
            mwIncludePermits = obj["mwIncludePermits"]?.jsonPrimitive?.boolean ?: false,
            receiverLocations = obj["receiverLocations"]?.jsonObject?.entries?.associate { (url, value) ->
                Receiver.endpoint(url).toString() to
                    GeoPoint(value.jsonObject.getValue("lat").jsonPrimitive.double, value.jsonObject.getValue("lon").jsonPrimitive.double)
            } ?: emptyMap(),
            favorites = obj["favorites"]?.jsonArray?.let(::decodeStationRefs) ?: emptyList(),
            mwIncludeSilent = obj["mwIncludeSilent"]?.jsonPrimitive?.boolean ?: false,
            mwIncludeUnverified = obj["mwIncludeUnverified"]?.jsonPrimitive?.boolean ?: false,
            mwHidden = obj["mwHidden"]?.jsonArray?.let(::decodeStationRefs) ?: emptyList(),
        )
    }

    private fun stationRefs(refs: List<MwFavorite>) = buildJsonArray { refs.forEach { ref -> add(buildJsonObject {
        put("facility", ref.facilityId); put("khz", ref.khz); put("call", ref.call)
    }) } }

    private fun decodeStationRefs(array: JsonArray) = array.map {
        val ref = it.jsonObject
        MwFavorite(ref.getValue("facility").jsonPrimitive.int, ref.getValue("khz").jsonPrimitive.int, ref.getValue("call").jsonPrimitive.content)
    }

    private fun decodeSound(obj: JsonObject): SoundSettings = SoundSettings(
        radioEnabled = obj.getValue("radioEnabled").jsonPrimitive.boolean,
        radioVolumePercent = obj.getValue("radioVolume").jsonPrimitive.int,
        tones = RssiToneSettings(
            enabled = obj.getValue("tonesEnabled").jsonPrimitive.boolean,
            points = obj.getValue("points").jsonArray.map {
                PitchPoint(it.jsonObject.getValue("dbm").jsonPrimitive.double, it.jsonObject.getValue("midi").jsonPrimitive.int)
            },
            scale = MusicalScale.valueOf(obj.getValue("scale").jsonPrimitive.content),
            root = obj.getValue("root").jsonPrimitive.int,
            customOffsets = obj.getValue("customOffsets").jsonArray.map { it.jsonPrimitive.int },
            volumePercent = obj.getValue("volume").jsonPrimitive.int,
            durationMs = obj.getValue("durationMs").jsonPrimitive.int,
            intervalMs = obj.getValue("intervalMs").jsonPrimitive.int,
            fadeMs = obj.getValue("fadeMs").jsonPrimitive.int,
            smoothingMs = obj.getValue("smoothingMs").jsonPrimitive.int,
            hysteresisDb = obj.getValue("hysteresisDb").jsonPrimitive.double,
            waveform = ToneWaveform.valueOf(obj.getValue("waveform").jsonPrimitive.content),
            latchMode = obj["latchMode"]?.jsonPrimitive?.content?.let(RssiLatchMode::valueOf) ?: RssiLatchMode.BOTH,
            latchHoldMs = obj["latchHoldMs"]?.jsonPrimitive?.int ?: 1000,
            latchThresholdDb = obj["latchThresholdDb"]?.jsonPrimitive?.double ?: 1.0,
        ),
    )
}

class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings_v1")
    suspend fun load(): Settings = context.settingsDataStore.data.first()[key]?.let(SettingsCodec::decode) ?: Settings()
    suspend fun save(settings: Settings) {
        context.settingsDataStore.edit { it[key] = SettingsCodec.encode(settings) }
    }
}
