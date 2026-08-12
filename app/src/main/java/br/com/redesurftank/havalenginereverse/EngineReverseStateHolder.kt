package br.com.redesurftank.havalenginereverse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import br.com.redesurftank.havalenginereverse.services.UniversalMonitorService
import java.util.concurrent.atomic.AtomicLong

private const val PREFS_NAME = "engine_reverse_prefs"
private const val KEY_PINNED  = "pinned_keys"
private const val KEY_IGNORED = "ignored_keys"
private const val KEY_MIRROR_ENABLED = "mirror_temp_enabled"
private const val KEY_MIRROR_TARGET  = "mirror_temp_target"

/** Chave do sensor real de temperatura externa (telemetria read-only do barramento). */
const val OUTSIDE_TEMP_SENSOR_KEY = "car.basic.outside_temp"
/** Chave-alvo padrão onde o valor do sensor é espelhado para aparecer na tela OEM. */
const val DEFAULT_MIRROR_TARGET_KEY = "car.configure.outside_temp_display"
private const val SEPARATOR   = "" // ASCII Unit Separator — nunca aparece em nomes de chaves

object EngineReverseStateHolder {

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadPersistedKeys()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadPersistedKeys() {
        val p = prefs() ?: return
        val pinned  = p.getString(KEY_PINNED,  null)
        val ignored = p.getString(KEY_IGNORED, null)
        if (!pinned.isNullOrEmpty())  pinnedKeys.addAll(pinned.split(SEPARATOR))
        if (!ignored.isNullOrEmpty()) ignoredKeys.addAll(ignored.split(SEPARATOR))
        mirrorTempEnabled   = p.getBoolean(KEY_MIRROR_ENABLED, false)
        mirrorTempTargetKey = p.getString(KEY_MIRROR_TARGET, DEFAULT_MIRROR_TARGET_KEY)
            ?: DEFAULT_MIRROR_TARGET_KEY
    }

    private fun savePinned() {
        prefs()?.edit()?.putString(KEY_PINNED, pinnedKeys.joinToString(SEPARATOR))?.apply()
    }

    private fun saveIgnored() {
        prefs()?.edit()?.putString(KEY_IGNORED, ignoredKeys.joinToString(SEPARATOR))?.apply()
    }

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
        if (!pinnedKeys.contains(key)) { pinnedKeys.add(0, key); savePinned() }
    }

    fun unpinKey(key: String) {
        if (pinnedKeys.remove(key)) savePinned()
    }

    fun ignoreKey(key: String) {
        unpinKey(key)
        if (!ignoredKeys.contains(key)) { ignoredKeys.add(key); saveIgnored() }
    }

    fun unignoreKey(key: String) {
        if (ignoredKeys.remove(key)) saveIgnored()
    }

    fun clearAll() {
        discoveredKeys.clear()
        lastUpdatedAt.clear()
        eventLog.clear()
        pinnedKeys.clear()
        ignoredKeys.clear()
        prefs()?.edit()?.remove(KEY_PINNED)?.remove(KEY_IGNORED)?.apply()
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
        // Espelhamento: dispara ANTES do filtro de ignorados para não depender da UI.
        if (key == OUTSIDE_TEMP_SENSOR_KEY) {
            UniversalMonitorService.onOutsideTempSensorChanged(value)
        }
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

    // ── Proxy HTTPS (mitm) ───────────────────────────────────────────────
    var proxyRunning  by mutableStateOf(false)
    var proxyStatus   by mutableStateOf("")
    var caInstalled   by mutableStateOf(false)
    val interceptedReqs = mutableStateListOf<ProxyEntry>()

    /** Instância do servidor proxy — não é estado Compose, só referência. */
    var proxyServer: br.com.redesurftank.havalenginereverse.utils.MitmProxyServer? = null

    data class ProxyEntry(
        val id: Long,
        val time: String,
        val method: String,
        val host: String,
        val path: String,
        val requestHeaders: String,
        val requestBody: String,
        val responseCode: Int,
        val responseHeaders: String,
        val responseBody: String
    )

    // ── Rede: tcpdump ────────────────────────────────────────────────────
    var tcpdumpRunning  by mutableStateOf(false)
    var tcpdumpFilePath by mutableStateOf("/sdcard/haval_capture.pcap")
    var tcpdumpStatus   by mutableStateOf("")

    // ── Diagnóstico de clima ─────────────────────────────────────────────
    var climaScanRunning by mutableStateOf(false)
    var climaScanResult  by mutableStateOf("")

    // ── Temperatura na tela (espelhamento sensor → chave de display) ──────
    // Fonte da verdade é persistida em prefs; o serviço lê/aplica. A UI reflete e altera via intents.
    var mirrorTempEnabled   by mutableStateOf(false)
    var mirrorTempTargetKey by mutableStateOf(DEFAULT_MIRROR_TARGET_KEY)
    var mirrorTempStatus    by mutableStateOf("")

    // ── Exportação de APKs OEM ────────────────────────────────────────────
    var oemApkExportRunning by mutableStateOf(false)
    var oemApkExportResult  by mutableStateOf("")
    var oemApkUploading     by mutableStateOf(false)
    // Caminhos absolutos dos APKs copiados para o diretório privado do app (prontos p/ upload).
    val oemApkFiles = mutableStateListOf<String>()

    /**
     * Ponte para o serviço: chamado a cada novo valor do sensor de temperatura externa.
     * O serviço registra este hook (via campos estáticos) e faz a escrita AIDL na chave-alvo.
     * Mantido como lambda simples para desacoplar StateHolder (UI) do serviço.
     */
    @JvmStatic
    fun persistMirrorConfig(enabled: Boolean, targetKey: String) {
        mirrorTempEnabled = enabled
        mirrorTempTargetKey = targetKey
        prefs()?.edit()
            ?.putBoolean(KEY_MIRROR_ENABLED, enabled)
            ?.putString(KEY_MIRROR_TARGET, targetKey)
            ?.apply()
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
