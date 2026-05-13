package br.com.redesurftank.havalenginereverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import br.com.redesurftank.havalenginereverse.services.UniversalMonitorService
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import br.com.redesurftank.havalenginereverse.ui.theme.HavalEngineReverseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"
private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/rocamoras/haval-engine-reverse/releases/latest"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalEngineReverseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    DiscoveryScreen()
                }
            }
        }
    }
}

@Composable
fun DiscoveryScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val state   = EngineReverseStateHolder

    var currentVersion   by remember { mutableStateOf("--") }
    var isChecking       by remember { mutableStateOf(false) }
    var isDownloading    by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable  by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf("") }
    var downloadUrl      by remember { mutableStateOf("") }
    var updateMessage    by remember { mutableStateOf("") }
    var showMsgDialog    by remember { mutableStateOf(false) }
    var showPermDialog   by remember { mutableStateOf(false) }
    var downloadJob      by remember { mutableStateOf<Job?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            currentVersion = info.versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}
    }

    fun compareVersions(v1: String, v2: String): Int {
        val p1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until minOf(p1.size, p2.size)) {
            if (p1[i] > p2[i]) return 1
            if (p1[i] < p2[i]) return -1
        }
        return p1.size.compareTo(p2.size)
    }

    fun checkForUpdates() {
        scope.launch {
            isChecking = true
            updateMessage = ""
            try {
                val json = withContext(Dispatchers.IO) {
                    val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout    = 5000
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
                }
                val obj     = JSONObject(json)
                val tagName = obj.getString("tag_name")
                latestVersion = tagName.removePrefix("v")
                val assets  = obj.getJSONArray("assets")
                if (assets.length() > 0) {
                    downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                }
                val cmp = compareVersions(currentVersion, latestVersion)
                updateAvailable = cmp < 0
                updateMessage = when {
                    cmp < 0  -> "Nova versão disponível: v$latestVersion"
                    cmp == 0 -> "Você já tem a versão mais recente (v$currentVersion)"
                    else     -> "Versão local (v$currentVersion) mais nova que release"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar atualizações", e)
                updateMessage = "Erro ao verificar: ${e.message}"
                updateAvailable = false
            } finally {
                isChecking = false
                showMsgDialog = true
            }
        }
    }

    fun downloadAndInstall() {
        if (downloadUrl.isEmpty()) return
        downloadJob = scope.launch {
            isDownloading    = true
            downloadProgress = 0f
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout    = 60000
                    conn.connect()
                    val total = conn.contentLength.toLong()
                    val file  = File(context.cacheDir, "update.apk")
                    BufferedInputStream(conn.inputStream).use { input ->
                        FileOutputStream(file).use { output ->
                            val buf  = ByteArray(8192)
                            var read = 0L
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                output.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    withContext(Dispatchers.Main) {
                                        downloadProgress = read.toFloat() / total
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    file
                }

                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", apkFile)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(install)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao baixar APK", e)
                updateMessage = "Erro no download: ${e.message}"
                showMsgDialog = true
            } finally {
                isDownloading    = false
                downloadProgress = 0f
            }
        }
    }

    if (showMsgDialog) {
        AlertDialog(
            onDismissRequest = { showMsgDialog = false },
            title   = { Text("Atualização") },
            text    = { Text(updateMessage) },
            confirmButton = {
                if (updateAvailable) {
                    TextButton(onClick = {
                        showMsgDialog = false
                        val canInstall = context.packageManager.canRequestPackageInstalls()
                        if (!canInstall) showPermDialog = true
                        else downloadAndInstall()
                    }) { Text("Baixar e Instalar") }
                } else {
                    TextButton(onClick = { showMsgDialog = false }) { Text("OK") }
                }
            },
            dismissButton = if (updateAvailable) ({
                TextButton(onClick = { showMsgDialog = false }) { Text("Agora não") }
            }) else null
        )
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title   = { Text("Permissão necessária") },
            text    = { Text("Permita a instalação de aplicativos desconhecidos para esta app.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermDialog = false
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"))
                    permLauncher.launch(intent)
                }) { Text("Abrir configurações") }
            },
            dismissButton = {
                TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

        // ── Cabeçalho ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Haval Engine Reverse",
                    color = Color(0xFF4FC3F7),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotColor = if (state.vehicleConnected) Color(0xFF81C784) else Color(0xFFEF5350)
                    Box(Modifier.size(8.dp).background(dotColor, shape = RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(state.strategyStatus, color = Color(0xFFB0BEC5), fontSize = 11.sp)
                }
            }

            if (isDownloading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(28.dp),
                        color = Color(0xFF4FC3F7),
                        strokeWidth = 3.dp
                    )
                    Text("${(downloadProgress * 100).toInt()}%", color = Color(0xFFB0BEC5), fontSize = 10.sp)
                }
            } else {
                IconButton(
                    onClick = { if (!isChecking) checkForUpdates() },
                    enabled = !isChecking
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF4FC3F7), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Verificar atualização",
                            tint = if (updateAvailable) Color(0xFF81C784) else Color(0xFF4FC3F7))
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            Text("v$currentVersion", color = Color(0xFF546E7A), fontSize = 11.sp)
        }

        // ── Conteúdo ─────────────────────────────────────────────────────
        KeysTab(state = state, onUnpinKey = { state.unpinKey(it) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeysTab(
    state: EngineReverseStateHolder,
    onUnpinKey: (String) -> Unit
) {
    val context        = LocalContext.current
    val pinnedList     = state.pinnedKeys

    var sortByRecent   by remember { mutableStateOf(false) }
    var filterRecent   by remember { mutableStateOf(false) }

    // Ticker que atualiza "agora" a cada 5 s enquanto o filtro estiver ativo,
    // forçando recomposição e expirando chaves que saíram da janela de 2 min.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(filterRecent) {
        if (filterRecent) {
            while (true) {
                delay(5_000)
                now = System.currentTimeMillis()
            }
        }
    }

    val recentCutoff = now - 2 * 60 * 1000L

    val sortedRegular = state.discoveredKeys.entries
        .filter { it.key !in pinnedList }
        .let { list ->
            if (filterRecent) list.filter { (state.lastUpdatedAt[it.key] ?: 0L) >= recentCutoff }
            else list
        }
        .let { list ->
            if (sortByRecent) list.sortedByDescending { state.lastUpdatedAt[it.key] ?: 0L }
            else list.sortedBy { it.key }
        }

    val probeRunning      = state.probeRunning
    val scanRunning       = state.apkScanRunning
    val dumpsysRunning    = state.dumpsysRunning
    val logcatRunning     = state.logcatRunning
    val servicesRunning   = state.servicesRunning
    val bruteRunning      = state.bruteRunning
    val dataFilesRunning  = state.dataFilesRunning

    val anyRunning = probeRunning || scanRunning || dumpsysRunning ||
            logcatRunning || servicesRunning || bruteRunning || dataFilesRunning

    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title   = { Text("Limpar tudo") },
            text    = { Text("Apagar todas as chaves e fixadas?") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; state.clearAll() }) {
                    Text("Limpar", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancelar") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Barra de ações — linha 1: info + copiar + limpar ────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pinnedCount  = pinnedList.size
            val totalKeys    = state.discoveredKeys.size
            val visibleCount = sortedRegular.size
            Text(
                buildString {
                    append("$totalKeys chaves descobertas")
                    if (filterRecent) append(" · $visibleCount nos últ. 2 min")
                    if (pinnedCount > 0) append(" ($pinnedCount fixada${if (pinnedCount > 1) "s" else ""})")
                },
                color = if (filterRecent) Color(0xFFEF9A9A) else Color(0xFFB0BEC5),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val json = state.exportAsJson()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("beantechs_keys", json))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Copiar JSON", fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { showClearDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A)),
                border = BorderStroke(1.dp, Color(0x44EF5350)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Limpar", color = Color(0xFFEF5350), fontSize = 11.sp)
            }
        }

        // ── Barra de ações — linha 2: estratégias de busca ───────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Busca:", color = Color(0xFF546E7A), fontSize = 11.sp)

            // S5 — Active Probe
            StrategyButton(
                label = "S5 Probe", runningLabel = "Probe…",
                isRunning = probeRunning,
                enabled = state.vehicleConnected && !anyRunning,
                accentColor = Color(0xFFF48FB1),
                containerColor = Color(0xFF1A2A1A),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_PROBE
                        }
                    )
                }
            )

            // S6 — APK DEX Scan
            StrategyButton(
                label = "S6 Scan APK", runningLabel = "Scan APK…",
                isRunning = scanRunning,
                enabled = state.vehicleConnected && !anyRunning,
                accentColor = Color(0xFFFFD54F),
                containerColor = Color(0xFF2A2A10),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_APK_SCAN
                        }
                    )
                }
            )

            // S7 — Dumpsys
            StrategyButton(
                label = "S7 Dumpsys", runningLabel = "Dumpsys…",
                isRunning = dumpsysRunning,
                enabled = !anyRunning,
                accentColor = Color(0xFF4FC3F7),
                containerColor = Color(0xFF0D2030),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_DUMPSYS
                        }
                    )
                }
            )

            // S8 — Logcat Scan
            StrategyButton(
                label = "S8 Logcat", runningLabel = "Logcat…",
                isRunning = logcatRunning,
                enabled = !anyRunning,
                accentColor = Color(0xFFA5D6A7),
                containerColor = Color(0xFF0D1F0D),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_LOGCAT
                        }
                    )
                }
            )

            // S9 — Enumeração de serviços
            StrategyButton(
                label = "S9 Serviços", runningLabel = "Serviços…",
                isRunning = servicesRunning,
                enabled = !anyRunning,
                accentColor = Color(0xFFCE93D8),
                containerColor = Color(0xFF1A0D2E),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_SERVICES
                        }
                    )
                }
            )

            // S10 — Brute force de transaction codes
            StrategyButton(
                label = "S10 Brute", runningLabel = "Brute…",
                isRunning = bruteRunning,
                enabled = state.vehicleConnected && !anyRunning,
                accentColor = Color(0xFFFFB74D),
                containerColor = Color(0xFF2A1A0D),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_BRUTE
                        }
                    )
                }
            )

            // S11 — Arquivos de dados do app
            StrategyButton(
                label = "S11 Arquivos", runningLabel = "Arquivos…",
                isRunning = dataFilesRunning,
                enabled = !anyRunning,
                accentColor = Color(0xFF80DEEA),
                containerColor = Color(0xFF0D1F1F),
                onClick = {
                    context.startService(
                        Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_TRIGGER_DATA_FILES
                        }
                    )
                }
            )
        }

        // ── Barra de toggles — linha 3: ordenação e filtro ──────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Exibir:", color = Color(0xFF546E7A), fontSize = 11.sp)

            // Toggle: ordenar por mais recente
            FilterToggleChip(
                label = "⏱ Mais recentes",
                active = sortByRecent,
                activeColor = Color(0xFF4FC3F7),
                onClick = { sortByRecent = !sortByRecent }
            )

            // Toggle: mostrar apenas alterados nos últimos 2 min
            FilterToggleChip(
                label = "🔴 Últimos 2 min",
                active = filterRecent,
                activeColor = Color(0xFFEF5350),
                onClick = {
                    filterRecent = !filterRecent
                    if (filterRecent) now = System.currentTimeMillis()
                }
            )

            if (filterRecent) {
                val remaining = state.discoveredKeys.entries.count {
                    (state.lastUpdatedAt[it.key] ?: 0L) >= recentCutoff
                }
                Text("$remaining visíveis", color = Color(0xFF546E7A), fontSize = 10.sp)
            }
        }

        HorizontalDivider(color = Color(0xFF1E1E2E), thickness = 1.dp)

        // ── Cabeçalho da tabela ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E2E))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            val chaveLabel = if (sortByRecent) "CHAVE  ⏱▼" else "CHAVE"
            Text(chaveLabel,
                color = if (sortByRecent) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                fontSize = 10.sp, modifier = Modifier.weight(0.6f),
                fontFamily = FontFamily.Monospace)
            Text("VALOR", color = Color(0xFF546E7A), fontSize = 10.sp,
                modifier = Modifier.weight(0.25f), fontFamily = FontFamily.Monospace)
            Text("FONTE", color = Color(0xFF546E7A), fontSize = 10.sp,
                modifier = Modifier.weight(0.15f), fontFamily = FontFamily.Monospace)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── Seção de chaves fixadas ──────────────────────────────────
            if (pinnedList.isNotEmpty()) {
                item(key = "__pinned_header__") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111A11))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📌 FIXADAS", color = Color(0xFF81C784), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${pinnedList.size} chave${if (pinnedList.size > 1) "s" else ""} — duplo clique para desafixar",
                            color = Color(0xFF546E7A), fontSize = 10.sp
                        )
                    }
                }
                items(pinnedList, key = { "pin_$it" }) { key ->
                    val value   = state.discoveredKeys[key] ?: "--"
                    val lastLog = state.eventLog.firstOrNull { it.key == key }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111A11))
                            .combinedClickable(onClick = {}, onDoubleClick = { onUnpinKey(key) })
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📌 $key", color = Color(0xFFA5D6A7), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(value, color = Color(0xFFE0E0E0), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.25f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lastLog?.source ?: "", color = sourceColor(lastLog?.source ?: ""),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(0.15f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = Color(0xFF1A2A1A), thickness = 0.5.dp)
                }
                item(key = "__pinned_divider__") {
                    HorizontalDivider(color = Color(0xFF2A2A3E), thickness = 1.dp)
                }
            }

            // ── Chaves regulares ─────────────────────────────────────────
            items(sortedRegular, key = { it.key }) { (key, value) ->
                val lastLog = state.eventLog.firstOrNull { it.key == key }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onDoubleClick = { state.pinKey(key) })
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(key, color = Color(0xFF80CBC4), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(value, color = Color(0xFFE0E0E0), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.25f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(lastLog?.source ?: "", color = sourceColor(lastLog?.source ?: ""),
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.15f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun FilterToggleChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val bgColor    = if (active) activeColor.copy(alpha = 0.15f) else Color(0xFF1E1E2E)
    val borderColor = if (active) activeColor.copy(alpha = 0.8f) else Color(0xFF2A2A3E)
    val textColor  = if (active) activeColor else Color(0xFF546E7A)

    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(label, color = textColor, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun StrategyButton(
    label: String,
    runningLabel: String,
    isRunning: Boolean,
    enabled: Boolean,
    accentColor: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isRunning,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) Color(0xFF1A1A2E) else containerColor,
            disabledContainerColor = if (isRunning) Color(0xFF1A1A2E) else containerColor.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = accentColor,
                strokeWidth = 1.5.dp
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (isRunning) runningLabel else label,
            color = accentColor,
            fontSize = 11.sp
        )
    }
}

private fun sourceColor(source: String): Color = when {
    source.startsWith("listener")      -> Color(0xFF81C784)
    source.startsWith("raw-transact")  -> Color(0xFFFFB74D)
    source.startsWith("wildcard")      -> Color(0xFFCE93D8)
    source.startsWith("empty-key")     -> Color(0xFF4FC3F7)
    source.startsWith("initial-fetch") -> Color(0xFF546E7A)
    source.startsWith("reply")         -> Color(0xFFFF8A65)
    source.startsWith("probe")         -> Color(0xFFF48FB1)
    source.startsWith("apk-scan")      -> Color(0xFFFFD54F)
    source.startsWith("dumpsys")       -> Color(0xFF4FC3F7)
    source.startsWith("logcat")        -> Color(0xFFA5D6A7)
    source.startsWith("services")      -> Color(0xFFCE93D8)
    source.startsWith("brute")         -> Color(0xFFFFB74D)
    source.startsWith("data-files")    -> Color(0xFF80DEEA)
    else                               -> Color(0xFFB0BEC5)
}
