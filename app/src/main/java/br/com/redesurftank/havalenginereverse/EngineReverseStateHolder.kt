package br.com.redesurftank.havalenginereverse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.util.concurrent.atomic.AtomicLong

object EngineReverseStateHolder {

    var vehicleConnected by mutableStateOf(false)
    var strategyStatus   by mutableStateOf("Aguardando conexão...")

    // key → last value (para exibir tabela live)
    val discoveredKeys = SnapshotStateMap<String, String>()

    // key → timestamp (ms) da última atualização de valor
    val lastUpdatedAt = SnapshotStateMap<String, Long>()

    // log cronológico dos eventos
    val eventLog = mutableStateListOf<EventEntry>()
    var logEnabled by mutableStateOf(true)

    // chaves fixadas pelo usuário (aparecem no topo da aba Chaves)
    val pinnedKeys = mutableStateListOf<String>()

    // chaves ignoradas pelo usuário (não recebem mais updates, aparecem no fim)
    val ignoredKeys = mutableStateListOf<String>()

    fun pinKey(key: String) {
        if (!pinnedKeys.contains(key)) pinnedKeys.add(0, key)
    }

    fun unpinKey(key: String) {
        pinnedKeys.remove(key)
    }

    fun ignoreKey(key: String) {
        unpinKey(key)
        if (!ignoredKeys.contains(key)) ignoredKeys.add(key)
    }

    fun unignoreKey(key: String) {
        ignoredKeys.remove(key)
    }

    fun clearAll() {
        discoveredKeys.clear()
        lastUpdatedAt.clear()
        eventLog.clear()
        pinnedKeys.clear()
        ignoredKeys.clear()
    }

    fun clearLog() {
        eventLog.clear()
    }

    private val idCounter = AtomicLong(0)

    data class EventEntry(
        val id: Long,
        val time: String,
        val key: String,
        val value: String,
        val source: String
    )

    fun onEventReceived(key: String, value: String, source: String) {
        // Chaves ignoradas não recebem mais atualizações na grid
        if (ignoredKeys.contains(key)) return
        discoveredKeys[key] = value
        lastUpdatedAt[key] = System.currentTimeMillis()
        if (!logEnabled) return
        val entry = EventEntry(
            id = idCounter.getAndIncrement(),
            time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date()),
            key = key,
            value = value,
            source = source
        )
        eventLog.add(0, entry)
        if (eventLog.size > 2000) eventLog.removeAt(eventLog.lastIndex)
    }

    var probeRunning      by mutableStateOf(false)
    var apkScanRunning    by mutableStateOf(false)
    var dumpsysRunning    by mutableStateOf(false)
    var logcatRunning     by mutableStateOf(false)
    var servicesRunning   by mutableStateOf(false)
    var bruteRunning      by mutableStateOf(false)
    var dataFilesRunning  by mutableStateOf(false)

    // ── Ações: pacote Speech ──────────────────────────────────────────────
    var speechPackageEnabled by mutableStateOf<Boolean?>(null)
    var speechPackageLoading by mutableStateOf(false)

    fun setSpeechPackageState(enabled: Boolean?) {
        speechPackageEnabled = enabled
    }

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
