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
) {
    init {
        require(receivers.isNotEmpty() && receivers.size <= 50)
        require(selected in receivers.indices)
        require(stepHz in 1..MAX_FREQUENCY_HZ)
        require(receivers.map { it.baseUrl }.distinct().size == receivers.size) { "Receiver URL already exists" }
    }
    val receiver get() = receivers[selected]
}

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
        put("stepHz", settings.stepHz)
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
                Mode.valueOf(obj.getValue("mode").jsonPrimitive.content)),
            stepHz = obj.getValue("stepHz").jsonPrimitive.long,
        )
    }
}

class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings_v1")
    suspend fun load(): Settings = context.settingsDataStore.data.first()[key]?.let(SettingsCodec::decode) ?: Settings()
    suspend fun save(settings: Settings) {
        context.settingsDataStore.edit { it[key] = SettingsCodec.encode(settings) }
    }
}
