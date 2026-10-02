package org.kiwidroid.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.kiwidroid.core.MwStationDb

data class StationDirectoryState(val db: MwStationDb? = null, val error: String? = null)

/** Read-only bundled FCC MW table, parsed once per process off the main thread. */
class StationDirectory(context: Context) {
    private val mutableState = MutableStateFlow(StationDirectoryState())
    val state = mutableState.asStateFlow()

    init {
        val assets = context.applicationContext.assets
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            mutableState.value = try {
                StationDirectoryState(db = assets.open(ASSET).reader(Charsets.UTF_8).use(MwStationDb::read))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                StationDirectoryState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object { const val ASSET = "mw_stations.tsv" }
}
