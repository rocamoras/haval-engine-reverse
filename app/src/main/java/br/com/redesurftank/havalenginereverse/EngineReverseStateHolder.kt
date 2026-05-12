package br.com.redesurftank.havalenginereverse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap

object EngineReverseStateHolder {

    var vehicleConnected by mutableStateOf(false)
    var strategyStatus   by mutableStateOf("Aguardando conexão...")

    // key → last value (para exibir tabela live)
    val discoveredKeys = SnapshotStateMap<String, String>()

    // log cronológico dos eventos
    val eventLog = mutableStateListOf<EventEntry>()

    // chaves fixadas pelo usuário (aparecem no topo da aba Chaves)
    val pinnedKeys = mutableStateListOf<String>()

    fun pinKey(key: String) {
        if (!pinnedKeys.contains(key)) pinnedKeys.add(0, key)
    }

    fun unpinKey(key: String) {
        pinnedKeys.remove(key)
    }

    fun clearAll() {
        discoveredKeys.clear()
        eventLog.clear()
        pinnedKeys.clear()
    }

    data class EventEntry(
        val time: String,
        val key: String,
        val value: String,
        val source: String
    )

    fun onEventReceived(key: String, value: String, source: String) {
        discoveredKeys[key] = value
        val entry = EventEntry(
            time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date()),
            key = key,
            value = value,
            source = source
        )
        eventLog.add(0, entry)
        if (eventLog.size > 200) eventLog.removeAt(eventLog.lastIndex)
    }

    var probeRunning   by mutableStateOf(false)
    var apkScanRunning by mutableStateOf(false)

    fun setConnected(connected: Boolean, status: String) {
        vehicleConnected = connected
        strategyStatus = status
    }


    fun exportAsJson(): String {
        val sb = StringBuilder("{\n")
        discoveredKeys.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
            val comma = if (i < discoveredKeys.size - 1) "," else ""
            sb.append("  \"$k\": \"$v\"$comma\n")
        }
        sb.append("}")
        return sb.toString()
    }
}
