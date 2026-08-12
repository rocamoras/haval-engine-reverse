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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.ui.text.input.ImeAction
import br.com.redesurftank.havalenginereverse.services.UniversalMonitorService
import br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper
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
        EngineReverseStateHolder.init(applicationContext)
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

    var selectedTab by remember { mutableIntStateOf(0) }

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

        // ── Tabs ──────────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1A1A2E),
            contentColor = Color(0xFF4FC3F7)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        "Chaves",
                        color = if (selectedTab == 0) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        "Testes",
                        color = if (selectedTab == 1) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    Text(
                        "Logs",
                        color = if (selectedTab == 2) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = {
                    Text(
                        "Ações",
                        color = if (selectedTab == 3) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            )
            Tab(
                selected = selectedTab == 4,
                onClick = { selectedTab = 4 },
                text = {
                    Text(
                        "Rede",
                        color = if (selectedTab == 4) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            )
            Tab(
                selected = selectedTab == 5,
                onClick = { selectedTab = 5 },
                text = {
                    Text(
                        "Tela",
                        color = if (selectedTab == 5) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            )
        }

        // ── Conteúdo ─────────────────────────────────────────────────────
        when (selectedTab) {
            0 -> KeysTab(state = state, onUnpinKey = { state.unpinKey(it) })
            1 -> TestsTab(state = state)
            2 -> LogsTab(state = state)
            3 -> ActionsTab(state = state)
            4 -> NetworkTab(state = state)
            5 -> ScreenTempTab(state = state)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tela: Temperatura externa real na tela (probe + espelhamento) + export APKs OEM
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScreenTempTab(state: EngineReverseStateHolder) {
    val context = LocalContext.current

    val sensorValue = state.discoveredKeys[OUTSIDE_TEMP_SENSOR_KEY]

    val candidateKeys = listOf(
        "car.configure.outside_temp_display",
        "car.weather.temperature",
        "car.weather.outside_temp",
        "car.basic.outside_temp"
    )
    var probeValue    by remember { mutableStateOf("7") }
    var selectedTarget by remember { mutableStateOf(state.mirrorTempTargetKey) }
    var customKey     by remember { mutableStateOf("") }
    LaunchedEffect(state.mirrorTempTargetKey) { selectedTarget = state.mirrorTempTargetKey }

    fun sendProbe(key: String, value: String) {
        if (key.isBlank()) return
        context.startService(Intent(context, UniversalMonitorService::class.java).apply {
            action = UniversalMonitorService.ACTION_PROBE_TEMP_KEY
            putExtra(UniversalMonitorService.EXTRA_REQ_KEY, key)
            putExtra(UniversalMonitorService.EXTRA_REQ_VALUE, value)
        })
    }
    fun setMirror(enabled: Boolean, target: String) {
        context.startService(Intent(context, UniversalMonitorService::class.java).apply {
            action = UniversalMonitorService.ACTION_SET_TEMP_MIRROR
            putExtra(UniversalMonitorService.EXTRA_MIRROR_ENABLED, enabled)
            putExtra(UniversalMonitorService.EXTRA_MIRROR_TARGET_KEY, target)
        })
    }

    var apkUploadStatus by remember { mutableStateOf("") }
    // Sobe os APKs exportados um a um para o Firebase (logs/), acumulando os links.
    fun uploadApksSequential(files: List<String>, index: Int, links: StringBuilder) {
        if (index >= files.size) {
            state.oemApkUploading = false
            apkUploadStatus = "✓ ${files.size} APK(s) enviados:\n$links"
            return
        }
        val f = java.io.File(files[index])
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        FirebaseLogUploader.uploadFile(
            file = f,
            destName = "oem_${ts}_${f.name}",
            onProgress = { apkUploadStatus = "(${index + 1}/${files.size}) $it" },
            onSuccess = { url ->
                links.append("• ").append(url).append('\n')
                uploadApksSequential(files, index + 1, links)
            },
            onError = {
                state.oemApkUploading = false
                apkUploadStatus = "Erro em ${f.name}: $it"
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Temperatura externa real na tela",
            color = Color(0xFF4FC3F7), fontSize = 15.sp, fontWeight = FontWeight.Bold
        )
        Text(
            "O sensor real do carro é car.basic.outside_temp. A tela OEM mostra o clima online " +
                "(weatherservice). Aqui você descobre qual chave a tela lê (Probe) e depois espelha " +
                "o sensor real nela automaticamente.",
            color = Color(0xFF546E7A), fontSize = 10.sp
        )

        // ── Card 1: leitura ao vivo do sensor real ───────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = BorderStroke(1.dp, Color(0x334FC3F7))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Sensor real (car.basic.outside_temp)",
                    color = Color(0xFF546E7A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (sensorValue != null) "$sensorValue°C" else "— (aguardando leitura)",
                    color = if (sensorValue != null) Color(0xFF81C784) else Color(0xFF546E7A),
                    fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Card 2: Probe — descobrir a chave que a tela lê ──────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = BorderStroke(1.dp, Color(0x33FFCC80))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1) Probe — qual chave move o número na tela?",
                    color = Color(0xFFFFCC80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Envie um valor óbvio e observe a barra de status da tela central. " +
                    "A chave que mudar o número é a certa.",
                    color = Color(0xFF546E7A), fontSize = 10.sp)
                OutlinedTextField(
                    value = probeValue,
                    onValueChange = { probeValue = it },
                    label = { Text("Valor de teste", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFCC80),
                        unfocusedBorderColor = Color(0xFF2A2A3E),
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        cursorColor = Color(0xFFFFCC80)
                    )
                )
                candidateKeys.forEach { key ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(key, color = Color(0xFFB0BEC5), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Button(
                            onClick = { sendProbe(key, probeValue) },
                            enabled = state.vehicleConnected && probeValue.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2F1E)),
                            border = BorderStroke(1.dp, Color(0x55FFCC80)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text("Testar", color = Color(0xFFFFCC80), fontSize = 11.sp) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customKey,
                        onValueChange = { customKey = it },
                        label = { Text("Outra chave…", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFCC80),
                            unfocusedBorderColor = Color(0xFF2A2A3E),
                            focusedTextColor = Color(0xFFE0E0E0),
                            unfocusedTextColor = Color(0xFFE0E0E0),
                            cursorColor = Color(0xFFFFCC80)
                        )
                    )
                    Button(
                        onClick = { sendProbe(customKey.trim(), probeValue) },
                        enabled = state.vehicleConnected && customKey.isNotBlank() && probeValue.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2F1E)),
                        border = BorderStroke(1.dp, Color(0x55FFCC80)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("Testar", color = Color(0xFFFFCC80), fontSize = 11.sp) }
                }
            }
        }

        // ── Card 3: Espelhamento contínuo ────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = BorderStroke(1.dp, if (state.mirrorTempEnabled) Color(0x5581C784) else Color(0x334FC3F7))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("2) Espelhar sensor → tela",
                            color = Color(0xFF81C784), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Escreve o sensor real na chave escolhida e reaplica a cada 30s.",
                            color = Color(0xFF546E7A), fontSize = 10.sp)
                    }
                    Switch(
                        checked = state.mirrorTempEnabled,
                        onCheckedChange = { setMirror(it, selectedTarget) },
                        enabled = state.vehicleConnected,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF81C784),
                            checkedTrackColor = Color(0x5581C784)
                        )
                    )
                }
                Text("Chave-alvo:", color = Color(0xFF546E7A), fontSize = 10.sp)
                candidateKeys.forEach { key ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTarget = key
                                if (state.mirrorTempEnabled) setMirror(true, key)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = selectedTarget == key,
                            onClick = {
                                selectedTarget = key
                                if (state.mirrorTempEnabled) setMirror(true, key)
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFF81C784),
                                unselectedColor = Color(0xFF546E7A)
                            )
                        )
                        Text(key, color = Color(0xFFB0BEC5), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.mirrorTempStatus.isNotBlank()) {
                    Text(state.mirrorTempStatus, color = Color(0xFF4FC3F7), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── Card 4: Exportar APKs OEM ────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = BorderStroke(1.dp, Color(0x33B39DDB))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3) Exportar APKs OEM (para reverter)",
                    color = Color(0xFFB39DDB), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Copia weatherservice/launcher/systemui para /sdcard/Download/haval-oem-apks. " +
                    "Se o Probe não achar chave escrevível, mande esses APKs pra descobrir como a tela lê a temperatura.",
                    color = Color(0xFF546E7A), fontSize = 10.sp)
                Button(
                    onClick = {
                        context.startService(Intent(context, UniversalMonitorService::class.java).apply {
                            action = UniversalMonitorService.ACTION_EXPORT_OEM_APKS
                        })
                    },
                    enabled = !state.oemApkExportRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2640)),
                    border = BorderStroke(1.dp, Color(0x55B39DDB))
                ) {
                    if (state.oemApkExportRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFFB39DDB), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Exportar APKs OEM", color = Color(0xFFB39DDB), fontSize = 12.sp)
                }
                if (state.oemApkExportResult.isNotBlank()) {
                    Text(state.oemApkExportResult, color = Color(0xFFB0BEC5), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
                if (state.oemApkFiles.isNotEmpty()) {
                    Button(
                        onClick = {
                            state.oemApkUploading = true
                            apkUploadStatus = "Iniciando upload…"
                            uploadApksSequential(state.oemApkFiles.toList(), 0, StringBuilder())
                        },
                        enabled = !state.oemApkUploading && !state.oemApkExportRunning,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243B2A)),
                        border = BorderStroke(1.dp, Color(0x5581C784))
                    ) {
                        if (state.oemApkUploading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF81C784), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Enviar APKs ao Firebase (${state.oemApkFiles.size})",
                            color = Color(0xFF81C784), fontSize = 12.sp)
                    }
                }
                if (apkUploadStatus.isNotBlank()) {
                    Text(apkUploadStatus, color = Color(0xFF4FC3F7), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tela de Testes
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TestsTab(state: EngineReverseStateHolder) {
    val context = LocalContext.current

    // Registra o último envio por chave: key → "HH:mm:ss  action=X  value=Y"
    val sentLog = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>() }

    fun sendRequest(key: String, value: String, reqAction: String = "cmd.common.request.set") {
        context.startService(
            Intent(context, UniversalMonitorService::class.java).apply {
                action = UniversalMonitorService.ACTION_SEND_REQUEST
                putExtra(UniversalMonitorService.EXTRA_REQ_ACTION, reqAction)
                putExtra(UniversalMonitorService.EXTRA_REQ_KEY, key)
                putExtra(UniversalMonitorService.EXTRA_REQ_VALUE, value)
            }
        )
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        sentLog[key] = "$ts  action=$reqAction  valor=$value"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            "Enviar comandos ao serviço Beantechs",
            color = Color(0xFF546E7A),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // ── Card 1: car.basic.engine_state — campo livre ─────────────────
        val engineKey = "car.basic.engine_state"
        val engineCurrent = state.discoveredKeys[engineKey]
        var engineInput by remember { mutableStateOf(engineCurrent ?: "") }

        // Sincroniza o campo se o valor chegar via listener após abrir a tela
        LaunchedEffect(engineCurrent) {
            if (engineInput.isEmpty() && engineCurrent != null) engineInput = engineCurrent
        }

        TestCard(title = engineKey, currentValue = engineCurrent, sentInfo = sentLog[engineKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = engineInput,
                    onValueChange = { engineInput = it },
                    label = { Text("Novo valor", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF4FC3F7),
                        unfocusedBorderColor = Color(0xFF2A2A3E),
                        focusedLabelColor    = Color(0xFF4FC3F7),
                        unfocusedLabelColor  = Color(0xFF546E7A),
                        focusedTextColor     = Color(0xFFE0E0E0),
                        unfocusedTextColor   = Color(0xFFE0E0E0),
                        cursorColor          = Color(0xFF4FC3F7)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendRequest(engineKey, engineInput) })
                )
                Button(
                    onClick = { sendRequest(engineKey, engineInput) },
                    enabled = state.vehicleConnected && engineInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                    border = BorderStroke(1.dp, Color(0x554FC3F7)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Enviar", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                }
            }
        }

        // ── Card 2: car.hvac.acmax_enable — toggle 0/1 ───────────────────
        val acmaxKey     = "car.hvac.acmax_enable"
        val acmaxCurrent = state.discoveredKeys[acmaxKey]
        val acmaxIsOn    = acmaxCurrent == "1"

        TestCard(title = acmaxKey, currentValue = acmaxCurrent, sentInfo = sentLog[acmaxKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Botão OFF
                Button(
                    onClick = { sendRequest(acmaxKey, "0") },
                    enabled = state.vehicleConnected && acmaxIsOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor      = if (!acmaxIsOn) Color(0xFF1A2A3A) else Color(0xFF1E1E2E),
                        disabledContainerColor = Color(0xFF1A2A3A)
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (!acmaxIsOn) Color(0xFF4FC3F7) else Color(0xFF2A2A3E)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "0  —  Off",
                        color = if (!acmaxIsOn) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp,
                        fontWeight = if (!acmaxIsOn) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Botão ON
                Button(
                    onClick = { sendRequest(acmaxKey, "1") },
                    enabled = state.vehicleConnected && !acmaxIsOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor      = if (acmaxIsOn) Color(0xFF1A3A2A) else Color(0xFF1E1E2E),
                        disabledContainerColor = Color(0xFF1A3A2A)
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (acmaxIsOn) Color(0xFF81C784) else Color(0xFF2A2A3E)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "1  —  On",
                        color = if (acmaxIsOn) Color(0xFF81C784) else Color(0xFF546E7A),
                        fontSize = 13.sp,
                        fontWeight = if (acmaxIsOn) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // ── Card 3: car.configure.seat_belt_warning — toggle 0/1 ────────
        val seatBeltKey     = "car.configure.seat_belt_warning"
        val seatBeltCurrent = state.discoveredKeys[seatBeltKey]
        val seatBeltIsOn    = seatBeltCurrent == "1"

        TestCard(title = seatBeltKey, currentValue = seatBeltCurrent, sentInfo = sentLog[seatBeltKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Botão OFF
                Button(
                    onClick = { sendRequest(seatBeltKey, "0") },
                    enabled = state.vehicleConnected && seatBeltIsOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = if (!seatBeltIsOn) Color(0xFF1A2A3A) else Color(0xFF1E1E2E),
                        disabledContainerColor = Color(0xFF1A2A3A)
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (!seatBeltIsOn) Color(0xFF4FC3F7) else Color(0xFF2A2A3E)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "0  —  Off",
                        color = if (!seatBeltIsOn) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp,
                        fontWeight = if (!seatBeltIsOn) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Botão ON
                Button(
                    onClick = { sendRequest(seatBeltKey, "1") },
                    enabled = state.vehicleConnected && !seatBeltIsOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = if (seatBeltIsOn) Color(0xFF1A3A2A) else Color(0xFF1E1E2E),
                        disabledContainerColor = Color(0xFF1A3A2A)
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (seatBeltIsOn) Color(0xFF81C784) else Color(0xFF2A2A3E)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "1  —  On",
                        color = if (seatBeltIsOn) Color(0xFF81C784) else Color(0xFF546E7A),
                        fontSize = 13.sp,
                        fontWeight = if (seatBeltIsOn) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // ── Card 4: car.configure.outside_temp_display — campo livre ────
        val outsideTempKey     = "car.configure.outside_temp_display"
        val outsideTempCurrent = state.discoveredKeys[outsideTempKey]
        var outsideTempInput   by remember { mutableStateOf(outsideTempCurrent ?: "") }

        LaunchedEffect(outsideTempCurrent) {
            if (outsideTempInput.isEmpty() && outsideTempCurrent != null) outsideTempInput = outsideTempCurrent
        }

        TestCard(title = outsideTempKey, currentValue = outsideTempCurrent, sentInfo = sentLog[outsideTempKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = outsideTempInput,
                    onValueChange = { outsideTempInput = it },
                    label = { Text("Novo valor", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF4FC3F7),
                        unfocusedBorderColor = Color(0xFF2A2A3E),
                        focusedLabelColor    = Color(0xFF4FC3F7),
                        unfocusedLabelColor  = Color(0xFF546E7A),
                        focusedTextColor     = Color(0xFFE0E0E0),
                        unfocusedTextColor   = Color(0xFFE0E0E0),
                        cursorColor          = Color(0xFF4FC3F7)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendRequest(outsideTempKey, outsideTempInput) })
                )
                Button(
                    onClick = { sendRequest(outsideTempKey, outsideTempInput) },
                    enabled = state.vehicleConnected && outsideTempInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                    border = BorderStroke(1.dp, Color(0x554FC3F7)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Enviar", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                }
            }
        }

        // ── Card 5: car.ev.setting.wade_mode_enable — toggle 0/1 ────────
        val wadeModeKey     = "car.ev.setting.wade_mode_enable"
        val wadeModeCurrent = state.discoveredKeys[wadeModeKey]
        val wadeModeIsOn    = wadeModeCurrent == "1"

        TestCard(title = wadeModeKey, currentValue = wadeModeCurrent, sentInfo = sentLog[wadeModeKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { sendRequest(wadeModeKey, "0") },
                    enabled = state.vehicleConnected && wadeModeIsOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = if (!wadeModeIsOn) Color(0xFF1A2A3A) else Color(0xFF1E1E2E),
                        disabledContainerColor = Color(0xFF1A2A3A)
                    ),
                    border = BorderStroke(1.5.dp, if (!wadeModeIsOn) Color(0xFF4FC3F7) else Color(0xFF2A2A3E)),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "0  —  Off",
                        color = if (!wadeModeIsOn) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                        fontSize = 13.sp,
                        fontWeight = if (!wadeModeIsOn) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Button(
                    onClick = { sendRequest(wadeModeKey, "1") },
                    enabled = state.vehicleConnected && !wadeModeIsOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = if (wadeModeIsOn) Color(0xFF1A3A2A) else Color(0xFF1E1E2E),
                        disabledContainerColor = Color(0xFF1A3A2A)
                    ),
                    border = BorderStroke(1.5.dp, if (wadeModeIsOn) Color(0xFF81C784) else Color(0xFF2A2A3E)),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "1  —  On",
                        color = if (wadeModeIsOn) Color(0xFF81C784) else Color(0xFF546E7A),
                        fontSize = 13.sp,
                        fontWeight = if (wadeModeIsOn) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // ── Card 6: car.oms.frs.seat_staff_info — campo livre ───────────
        val seatStaffKey     = "car.oms.frs.seat_staff_info"
        val seatStaffCurrent = state.discoveredKeys[seatStaffKey]
        var seatStaffInput   by remember { mutableStateOf(seatStaffCurrent ?: "") }

        LaunchedEffect(seatStaffCurrent) {
            if (seatStaffInput.isEmpty() && seatStaffCurrent != null) seatStaffInput = seatStaffCurrent
        }

        TestCard(title = seatStaffKey, currentValue = seatStaffCurrent, sentInfo = sentLog[seatStaffKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = seatStaffInput,
                    onValueChange = { seatStaffInput = it },
                    label = { Text("Novo valor", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF4FC3F7),
                        unfocusedBorderColor = Color(0xFF2A2A3E),
                        focusedLabelColor    = Color(0xFF4FC3F7),
                        unfocusedLabelColor  = Color(0xFF546E7A),
                        focusedTextColor     = Color(0xFFE0E0E0),
                        unfocusedTextColor   = Color(0xFFE0E0E0),
                        cursorColor          = Color(0xFF4FC3F7)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendRequest(seatStaffKey, seatStaffInput) })
                )
                Button(
                    onClick = { sendRequest(seatStaffKey, seatStaffInput) },
                    enabled = state.vehicleConnected && seatStaffInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                    border = BorderStroke(1.dp, Color(0x554FC3F7)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Enviar", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                }
            }
        }

        // ── Card 7: car.basic.seated_state — campo livre ─────────────────
        val seatedStateKey     = "car.basic.seated_state"
        val seatedStateCurrent = state.discoveredKeys[seatedStateKey]
        var seatedStateInput   by remember { mutableStateOf(seatedStateCurrent ?: "") }

        LaunchedEffect(seatedStateCurrent) {
            if (seatedStateInput.isEmpty() && seatedStateCurrent != null) seatedStateInput = seatedStateCurrent
        }

        TestCard(title = seatedStateKey, currentValue = seatedStateCurrent, sentInfo = sentLog[seatedStateKey]) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = seatedStateInput,
                    onValueChange = { seatedStateInput = it },
                    label = { Text("Novo valor", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF4FC3F7),
                        unfocusedBorderColor = Color(0xFF2A2A3E),
                        focusedLabelColor    = Color(0xFF4FC3F7),
                        unfocusedLabelColor  = Color(0xFF546E7A),
                        focusedTextColor     = Color(0xFFE0E0E0),
                        unfocusedTextColor   = Color(0xFFE0E0E0),
                        cursorColor          = Color(0xFF4FC3F7)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendRequest(seatedStateKey, seatedStateInput) })
                )
                Button(
                    onClick = { sendRequest(seatedStateKey, seatedStateInput) },
                    enabled = state.vehicleConnected && seatedStateInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                    border = BorderStroke(1.dp, Color(0x554FC3F7)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Enviar", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                }
            }
        }

        // ── Seção "Forçar Clima" ─────────────────────────────────────────
        HorizontalDivider(color = Color(0xFF2A2A3E), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "Forçar dados de clima (Vetor 2 — AIDL)",
            color = Color(0xFFFFCC80),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Envia valores diretamente via Beantechs AIDL. Se o widget ler estas chaves ele mostrará os dados abaixo.",
            color = Color(0xFF546E7A),
            fontSize = 10.sp
        )

        val weatherKeys = listOf(
            "car.basic.outside_temp",
            "car.weather.condition",
            "car.weather.icon",
            "car.weather.city",
            "car.weather.temperature",
            "car.weather.humidity",
            "car.weather.forecast"
        )
        val weatherDefaults = mapOf(
            "car.basic.outside_temp" to "25",
            "car.weather.condition"  to "Sunny",
            "car.weather.icon"       to "sunny",
            "car.weather.city"       to "Sao Paulo",
            "car.weather.temperature" to "25",
            "car.weather.humidity"   to "60",
            "car.weather.forecast"   to "Sunny 25C"
        )

        weatherKeys.forEach { wKey ->
            val wCurrent = state.discoveredKeys[wKey]
            var wInput   by remember(wKey) { mutableStateOf(wCurrent ?: weatherDefaults[wKey] ?: "") }
            LaunchedEffect(wCurrent) {
                if (wCurrent != null) wInput = wCurrent
            }
            TestCard(title = wKey, currentValue = wCurrent, sentInfo = sentLog[wKey]) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = wInput,
                        onValueChange = { wInput = it },
                        label = { Text("Valor a forçar", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color(0xFFFFCC80),
                            unfocusedBorderColor = Color(0xFF2A2A3E),
                            focusedLabelColor    = Color(0xFFFFCC80),
                            unfocusedLabelColor  = Color(0xFF546E7A),
                            focusedTextColor     = Color(0xFFE0E0E0),
                            unfocusedTextColor   = Color(0xFFE0E0E0),
                            cursorColor          = Color(0xFFFFCC80)
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendRequest(wKey, wInput) })
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // action = cmd.common.request.set (padrão Beantechs)
                        Button(
                            onClick = { sendRequest(wKey, wInput) },
                            enabled = wInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1E0A)),
                            border = BorderStroke(1.dp, Color(0xFFFFCC80)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("req", color = Color(0xFFFFCC80), fontSize = 11.sp)
                        }
                        // action = cmd.common.set (variante mais curta, testada nos brute codes)
                        Button(
                            onClick = { sendRequest(wKey, wInput, "cmd.common.set") },
                            enabled = wInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A1A)),
                            border = BorderStroke(1.dp, Color(0xFF81C784)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("set", color = Color(0xFF81C784), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (!state.vehicleConnected) {
            Text(
                "⚠ Serviço não conectado — os botões ficam desabilitados até conectar.",
                color = Color(0xFFFFB74D),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TestCard(
    title: String,
    currentValue: String?,
    sentInfo: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        border = BorderStroke(1.dp, Color(0xFF2A2A3E)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Título — nome da propriedade
            Text(
                title,
                color = Color(0xFF80CBC4),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            // Valor atual
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Valor atual: ", color = Color(0xFF546E7A), fontSize = 11.sp)
                Text(
                    currentValue ?: "—  (desconhecido)",
                    color = if (currentValue != null) Color(0xFFE0E0E0) else Color(0xFF3A3A4E),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            // Feedback do último envio
            if (sentInfo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ Enviado: ", color = Color(0xFF81C784), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                    Text(sentInfo, color = Color(0xFF81C784), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A3E), thickness = 0.5.dp)
            // Conteúdo específico do card
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Aba de Chaves (grid de descoberta)
// ─────────────────────────────────────────────────────────────────────────────

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

    val ignoredList = state.ignoredKeys

    val sortedRegular = state.discoveredKeys.entries
        .filter { it.key !in pinnedList && it.key !in ignoredList }
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
                            "${pinnedList.size} chave${if (pinnedList.size > 1) "s" else ""} — 2× desafixa · 3× ignora",
                            color = Color(0xFF546E7A), fontSize = 10.sp
                        )
                    }
                }
                items(pinnedList, key = { "pin_$it" }) { key ->
                    val value   = state.discoveredKeys[key] ?: "--"
                    val lastLog = state.eventLog.firstOrNull { it.key == key }
                    var clicks by remember { mutableIntStateOf(0) }
                    LaunchedEffect(clicks) {
                        if (clicks == 0) return@LaunchedEffect
                        delay(350)
                        when (clicks) {
                            2    -> onUnpinKey(key)
                            in 3..Int.MAX_VALUE -> state.ignoreKey(key)
                        }
                        clicks = 0
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111A11))
                            .clickable { clicks++ }
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
                var clicks by remember { mutableIntStateOf(0) }
                LaunchedEffect(clicks) {
                    if (clicks == 0) return@LaunchedEffect
                    delay(350)
                    when (clicks) {
                        2    -> state.pinKey(key)
                        in 3..Int.MAX_VALUE -> state.ignoreKey(key)
                    }
                    clicks = 0
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clicks++ }
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

            // ── Seção de chaves ignoradas (no final) ─────────────────────
            if (ignoredList.isNotEmpty()) {
                item(key = "__ignored_header__") {
                    HorizontalDivider(color = Color(0xFF2A2A3E), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1212))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🚫 IGNORADAS", color = Color(0xFF546E7A), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${ignoredList.size} chave${if (ignoredList.size > 1) "s" else ""} — duplo clique para restaurar",
                            color = Color(0xFF3A3A4E), fontSize = 10.sp
                        )
                    }
                }
                items(ignoredList, key = { "ign_$it" }) { key ->
                    val lastValue = state.discoveredKeys[key] ?: "--"
                    var clicks by remember { mutableIntStateOf(0) }
                    LaunchedEffect(clicks) {
                        if (clicks == 0) return@LaunchedEffect
                        delay(350)
                        if (clicks >= 2) state.unignoreKey(key)
                        clicks = 0
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1212))
                            .clickable { clicks++ }
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🚫 $key", color = Color(0xFF3A3A4E), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lastValue, color = Color(0xFF3A3A4E), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.25f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("ignorada", color = Color(0xFF2A2A3E), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.15f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = Color(0xFF1A1212), thickness = 0.5.dp)
                }
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

// ─────────────────────────────────────────────────────────────────────────────
// Aba de Logs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LogsTab(state: EngineReverseStateHolder) {
    val logs = state.eventLog
    var uploadStatus by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

        // ── Barra de controles ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Botão ativar/desativar
            val logEnabled = state.logEnabled
            OutlinedButton(
                onClick = { state.logEnabled = !logEnabled },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (logEnabled) Color(0xFF1B5E20) else Color(0xFF1A1A2E),
                    contentColor   = if (logEnabled) Color(0xFF81C784) else Color(0xFF546E7A)
                ),
                border = BorderStroke(1.dp, if (logEnabled) Color(0xFF81C784) else Color(0xFF546E7A)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    if (logEnabled) "Pausar" else "Ativar",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Botão limpar
            OutlinedButton(
                onClick = { state.clearLog() },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF1A1A2E),
                    contentColor   = Color(0xFFEF9A9A)
                ),
                border = BorderStroke(1.dp, Color(0xFFEF9A9A)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Limpar", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            // Botão enviar
            OutlinedButton(
                onClick = {
                    if (!uploading && logs.isNotEmpty()) {
                        uploading = true
                        uploadStatus = ""
                        FirebaseLogUploader.upload(
                            entries = logs.toList(),
                            onProgress = { uploadStatus = it },
                            onSuccess = { uploadStatus = "✓ Enviado: $it"; uploading = false },
                            onError   = { uploadStatus = "✗ $it"; uploading = false }
                        )
                    }
                },
                enabled = !uploading && logs.isNotEmpty(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF1A1A2E),
                    contentColor   = Color(0xFF4FC3F7)
                ),
                border = BorderStroke(1.dp, Color(0xFF4FC3F7)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                if (uploading) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Color(0xFF4FC3F7), strokeWidth = 1.5.dp)
                } else {
                    Text("Enviar", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "${logs.size} / 2000",
                color = Color(0xFF546E7A),
                fontSize = 11.sp
            )
        }

        // ── Status do upload ──────────────────────────────────────────────
        if (uploadStatus.isNotEmpty()) {
            Text(
                text = uploadStatus,
                color = if (uploadStatus.startsWith("✓")) Color(0xFF81C784)
                        else if (uploadStatus.startsWith("✗")) Color(0xFFEF9A9A)
                        else Color(0xFF546E7A),
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D1A))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ── Lista de entradas ─────────────────────────────────────────────
        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.logEnabled) "Aguardando eventos..." else "Log pausado",
                    color = Color(0xFF546E7A),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: EngineReverseStateHolder.EventEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            text = entry.time,
            color = Color(0xFF546E7A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp)
        )
        Spacer(Modifier.width(6.dp))
        // Bolinha colorida com source
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(6.dp)
                .background(sourceColor(entry.source), shape = RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(6.dp))
        // Chave
        Text(
            text = entry.key,
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        // Valor
        Text(
            text = entry.value,
            color = Color(0xFF4FC3F7),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 100.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Aba Ações
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionsTab(state: EngineReverseStateHolder) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        context.startService(
            Intent(context, UniversalMonitorService::class.java).apply {
                action = UniversalMonitorService.ACTION_CHECK_SPEECH_PACKAGE
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Monitoramento e Ações",
            color = Color(0xFF546E7A),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        ActionPackageCard(
            name        = "Speech",
            packageName = "com.iflytek.cutefly.speechclient.hmi",
            isEnabled   = state.speechPackageEnabled,
            isLoading   = state.speechPackageLoading,
            onClick = {
                context.startService(
                    Intent(context, UniversalMonitorService::class.java).apply {
                        action = UniversalMonitorService.ACTION_TOGGLE_SPEECH_PACKAGE
                    }
                )
            }
        )

        // ── Card: seat belt warning — ciclo 0..4 ─────────────────────────
        val seatBeltWarnKey = "car.configure.seat_belt_warning"
        ActionValueCycleCard(
            title   = "Seat Belt Warning",
            key     = seatBeltWarnKey,
            values  = listOf("0", "1", "2", "3", "4"),
            current = state.discoveredKeys[seatBeltWarnKey],
            onSend  = { value ->
                context.startService(
                    Intent(context, UniversalMonitorService::class.java).apply {
                        action = UniversalMonitorService.ACTION_SEND_REQUEST
                        putExtra(UniversalMonitorService.EXTRA_REQ_ACTION, "cmd.common.request.set")
                        putExtra(UniversalMonitorService.EXTRA_REQ_KEY, seatBeltWarnKey)
                        putExtra(UniversalMonitorService.EXTRA_REQ_VALUE, value)
                    }
                )
            }
        )
    }
}

@Composable
private fun ActionPackageCard(
    name: String,
    packageName: String,
    isEnabled: Boolean?,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val statusColor = when {
        isLoading        -> Color(0xFF546E7A)
        isEnabled == null -> Color(0xFF546E7A)
        isEnabled        -> Color(0xFF81C784)
        else             -> Color(0xFFEF5350)
    }

    val statusText = when {
        isLoading        -> "Verificando..."
        isEnabled == null -> "Desconhecido"
        isEnabled        -> "Ativo"
        else             -> "Desativado"
    }

    val actionHint = when {
        isLoading || isEnabled == null -> "Aguarde..."
        isEnabled                      -> "Toque para desativar"
        else                           -> "Toque para ativar"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onClick() },
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Indicador colorido
            Box(
                Modifier
                    .size(12.dp)
                    .background(statusColor, shape = RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))

            // Textos
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color      = Color(0xFFE0E0E0),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp
                )
                Text(
                    packageName,
                    color      = Color(0xFF546E7A),
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    actionHint,
                    color    = statusColor.copy(alpha = 0.75f),
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            // Badge de status ou spinner
            if (isLoading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(24.dp),
                    color       = Color(0xFF4FC3F7),
                    strokeWidth = 2.dp
                )
            } else {
                Surface(
                    shape  = RoundedCornerShape(6.dp),
                    color  = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        statusText,
                        color      = statusColor,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier   = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionValueCycleCard(
    title: String,
    key: String,
    values: List<String>,
    current: String?,
    onSend: (String) -> Unit
) {
    Card(
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ── Cabeçalho ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (current != null) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                            shape = RoundedCornerShape(5.dp)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color      = Color(0xFFE0E0E0),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 15.sp
                    )
                    Text(
                        key,
                        color      = Color(0xFF546E7A),
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                }
                // Valor atual em destaque
                if (current != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF4FC3F7).copy(alpha = 0.15f)
                    ) {
                        Text(
                            "atual: $current",
                            color      = Color(0xFF4FC3F7),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier   = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // ── Botões de valor ───────────────────────────────────────────
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                values.forEach { v ->
                    val isActive = v == current
                    Button(
                        onClick = { onSend(v) },
                        enabled = !isActive,
                        shape  = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor         = if (isActive) Color(0xFF1A3A4A) else Color(0xFF1E1E2E),
                            disabledContainerColor = Color(0xFF1A3A4A)
                        ),
                        border = BorderStroke(
                            1.5.dp,
                            if (isActive) Color(0xFF4FC3F7) else Color(0xFF2A2A3E)
                        ),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                    ) {
                        Text(
                            v,
                            color      = if (isActive) Color(0xFF4FC3F7) else Color(0xFF546E7A),
                            fontSize   = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Diagnóstico de clima — scan de packages, providers, logcat e tcp
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ClimaScanSection(state: EngineReverseStateHolder) {
    val scope   = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }

    fun runShell(cmd: String, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var telnet: br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper? = null
            try {
                telnet = br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper()
                telnet.connect("127.0.0.1", 23)
                val result = telnet.executeCommand(cmd, 20000)
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Erro: ${e.message}") }
            } finally {
                try { telnet?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    val scanCmd = """
echo "=== LAUNCHER/WEATHER PACKAGES ===" &&
pm list packages 2>/dev/null | grep -i -E "(launcher|gwm|haval|weather|clima|oem|home|desktop)" &&
echo "=== ACTIVITY FOREGROUND ===" &&
dumpsys activity top 2>/dev/null | head -25 &&
echo "=== CONTENT PROVIDERS ===" &&
dumpsys activity providers 2>/dev/null | grep -i -E "(weather|clima|gwm|temp)" | head -20 &&
echo "=== LOGCAT WEATHER ===" &&
logcat -d -t 300 2>/dev/null | grep -i -E "(weather|clima|gwmcloud|outside.temp|forecast|openweather|aqi)" | tail -30 &&
echo "=== TCP CONNECTIONS 443 ===" &&
cat /proc/net/tcp6 2>/dev/null | awk 'NR>1{print $3}' | grep -i "01BB" | head -10 &&
netstat -tn 2>/dev/null | grep ":443 " | head -10 &&
echo "=== SERVICE LIST WEATHER ===" &&
service list 2>/dev/null | grep -i -E "(weather|clima|gwm)" | head -10 &&
echo "=== DONE ==="
    """.trimIndent().replace("\n", " ")

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape  = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3A1A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.climaScanRunning) CircularProgressIndicator(
                    Modifier.size(12.dp), color = Color(0xFFFFCC80), strokeWidth = 1.5.dp)
                Text(
                    "Diagnóstico de Clima",
                    color = Color(0xFFFFCC80), fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Identifica qual package/processo gerencia o clima e quais ContentProviders existem.",
                color = Color(0xFF546E7A), fontSize = 10.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        state.climaScanRunning = true
                        state.climaScanResult  = "Executando scan..."
                        runShell(scanCmd) { result ->
                            state.climaScanRunning = false
                            state.climaScanResult  = result.ifBlank { "(sem saída)" }
                        }
                    },
                    enabled = !state.climaScanRunning,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2A0A),
                        disabledContainerColor = Color(0xFF1A1A08)
                    ),
                    border = BorderStroke(1.dp, if (!state.climaScanRunning) Color(0xFFFFCC80) else Color(0x22FFCC80)),
                    shape  = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Scan Clima", color = if (!state.climaScanRunning) Color(0xFFFFCC80) else Color(0xFF443311), fontSize = 12.sp) }

                Button(
                    onClick = {
                        uploading = true
                        val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        FirebaseLogUploader.uploadJson(
                            json       = org.json.JSONObject.quote(state.climaScanResult),
                            onProgress = { state.climaScanResult = "Enviando..." },
                            onSuccess  = { state.climaScanResult = "✓ Enviado: $it"; uploading = false },
                            onError    = { state.climaScanResult = "Erro: $it"; uploading = false }
                        )
                    },
                    enabled = !uploading && state.climaScanResult.isNotBlank()
                            && !state.climaScanRunning && state.climaScanResult != "Executando scan...",
                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A1A)),
                    border  = BorderStroke(1.dp, Color(0x5581C784)),
                    shape   = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (uploading) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = Color(0xFF81C784), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Enviar", color = Color(0xFF81C784), fontSize = 12.sp)
                }
            }

            if (state.climaScanResult.isNotBlank()) {
                Card(
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFF0A0A14)),
                    shape    = RoundedCornerShape(6.dp),
                    border   = BorderStroke(1.dp, Color(0xFF1A2A0A)),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(state.climaScanResult) { scrollState.animateScrollTo(0) }
                    Text(
                        state.climaScanResult,
                        color = Color(0xFFB0BEC5),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private fun sourceColor(source: String): Color = when {
    source.startsWith("listener")      -> Color(0xFF81C784)
    source.startsWith("raw-transact")  -> Color(0xFFFFB74D)
    source.startsWith("wildcard")      -> Color(0xFFCE93D8)
    source.startsWith("empty-key")     -> Color(0xFF4FC3F7)
source.startsWith("reply")         -> Color(0xFFFF8A65)
    source.startsWith("probe")         -> Color(0xFFF48FB1)
    source.startsWith("apk-scan")      -> Color(0xFFFFD54F)
    source.startsWith("dumpsys")       -> Color(0xFF4FC3F7)
    source.startsWith("logcat")        -> Color(0xFFA5D6A7)
    source.startsWith("services")      -> Color(0xFFCE93D8)
    source.startsWith("brute")         -> Color(0xFFFFB74D)
    source.startsWith("data-files")    -> Color(0xFF80DEEA)
    source.startsWith("request")       -> Color(0xFFFFCC80)
    else                               -> Color(0xFFB0BEC5)
}

// ─────────────────────────────────────────────────────────────────────────────
// Aba Rede — captura tcpdump
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NetworkTab(state: EngineReverseStateHolder) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Usa o diretório privado do app — sem necessidade de permissão READ_EXTERNAL_STORAGE
    val appFilesDir = remember {
        (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
    }
    val logFile  = remember { "$appFilesDir/haval_capture.log" }
    val pcapPath = remember { "$appFilesDir/haval_capture.pcap" }

    // Sincroniza o path no state para exibição e upload
    LaunchedEffect(pcapPath) { state.tcpdumpFilePath = pcapPath }

    // linhas ao vivo exibidas enquanto captura
    var liveLines by remember { mutableStateOf<List<String>>(emptyList()) }

    // polling a cada 2s enquanto tcpdump está rodando
    LaunchedEffect(state.tcpdumpRunning) {
        if (!state.tcpdumpRunning) return@LaunchedEffect
        while (state.tcpdumpRunning) {
            delay(2000)
            val lines = withContext(Dispatchers.IO) {
                var telnet: TelnetClientWrapper? = null
                try {
                    telnet = TelnetClientWrapper()
                    telnet.connect("127.0.0.1", 23)
                    telnet.executeCommand("tail -40 $logFile 2>/dev/null", 4000)
                } catch (_: Exception) { "" } finally {
                    try { telnet?.disconnect() } catch (_: Exception) {}
                }
            }
            liveLines = lines.lines().filter { it.isNotBlank() }.takeLast(40)
        }
    }

    fun runShell(cmd: String) {
        scope.launch(Dispatchers.IO) {
            var telnet: TelnetClientWrapper? = null
            try {
                telnet = TelnetClientWrapper()
                telnet.connect("127.0.0.1", 23)
                telnet.executeCommand(cmd, 8000)
            } catch (e: Exception) {
                scope.launch(Dispatchers.Main) { state.tcpdumpStatus = "Erro: ${e.message}" }
            } finally {
                try { telnet?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D1A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Card de status ────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2A3E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.tcpdumpRunning) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = Color(0xFF4FC3F7), strokeWidth = 1.5.dp)
                    }
                    Text(
                        if (state.tcpdumpRunning) "Capturando tráfego..." else "Captura de Rede (tcpdump)",
                        color = Color(0xFF4FC3F7),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "pcap: ${state.tcpdumpFilePath}",
                    color = Color(0xFF37474F),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (state.tcpdumpStatus.isNotBlank()) {
                    Text(
                        state.tcpdumpStatus,
                        color = when {
                            state.tcpdumpStatus.startsWith("Erro") -> Color(0xFFEF9A9A)
                            state.tcpdumpStatus.startsWith("✓")    -> Color(0xFF81C784)
                            else -> Color(0xFFFFD54F)
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ── Botões Iniciar / Parar / Enviar ───────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    liveLines = emptyList()
                    state.tcpdumpStatus = "Capturando..."
                    state.tcpdumpRunning = true
                    // pcap binário + log texto em paralelo, gravando no dir privado do app
                    runShell(
                        "mkdir -p $appFilesDir 2>/dev/null; rm -f $logFile 2>/dev/null; " +
                        "tcpdump -i any -s 0 -n -w $pcapPath > /dev/null 2>&1 & " +
                        "tcpdump -i any -n -l > $logFile 2>&1 &"
                    )
                },
                enabled = !state.tcpdumpRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E3A5F),
                    disabledContainerColor = Color(0xFF111A28)
                ),
                border = BorderStroke(1.dp, if (!state.tcpdumpRunning) Color(0xFF4FC3F7) else Color(0x224FC3F7)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Iniciar", color = if (!state.tcpdumpRunning) Color(0xFF4FC3F7) else Color(0xFF224466), fontSize = 13.sp) }

            Button(
                onClick = {
                    state.tcpdumpRunning = false
                    state.tcpdumpStatus = "Parado. Pronto para enviar."
                    runShell("pkill tcpdump")
                },
                enabled = state.tcpdumpRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A1A1A),
                    disabledContainerColor = Color(0xFF1A1108)
                ),
                border = BorderStroke(1.dp, if (state.tcpdumpRunning) Color(0xFFFFD54F) else Color(0x22FFD54F)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Parar", color = if (state.tcpdumpRunning) Color(0xFFFFD54F) else Color(0xFF443311), fontSize = 13.sp) }
        }

        var uploading by remember { mutableStateOf(false) }
        val pcapFile = File(pcapPath)

        Button(
            onClick = {
                uploading = true
                state.tcpdumpStatus = "Lendo arquivo..."
                // 1) lê bytes no IO  2) chama Firebase na Main
                scope.launch(Dispatchers.IO) {
                    val bytes = try {
                        pcapFile.readBytes()
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            state.tcpdumpStatus = "Erro ao ler arquivo: ${e.message}"
                            uploading = false
                        }
                        return@launch
                    }
                    val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val fileName = "pcap_$ts.pcap"
                    scope.launch(Dispatchers.Main) {
                        FirebaseLogUploader.uploadPcapBytes(
                            bytes    = bytes,
                            fileName = fileName,
                            onProgress = { state.tcpdumpStatus = it },
                            onSuccess  = { state.tcpdumpStatus = "✓ Enviado: $it"; uploading = false },
                            onError    = { state.tcpdumpStatus = "Erro: $it"; uploading = false }
                        )
                    }
                }
            },
            enabled = !uploading && !state.tcpdumpRunning && pcapFile.exists(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A1A)),
            border = BorderStroke(1.dp, Color(0x5581C784)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uploading) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF81C784), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Enviar para nuvem", color = Color(0xFF81C784), fontSize = 13.sp)
        }

        // ── Log ao vivo ───────────────────────────────────────────────────
        if (liveLines.isNotEmpty() || state.tcpdumpRunning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A14)),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF1A2A1A)),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "Pacotes capturados",
                        color = Color(0xFF37474F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(liveLines.size) {
                        if (liveLines.isNotEmpty()) listState.animateScrollToItem(liveLines.lastIndex)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(liveLines) { line ->
                            Text(
                                line,
                                color = when {
                                    line.contains("HTTP") || line.contains(".80 ") || line.contains("> 80") -> Color(0xFF80CBC4)
                                    line.contains("443")  -> Color(0xFFCE93D8)
                                    line.contains("DNS")  || line.contains(".53 ") -> Color(0xFFFFD54F)
                                    else -> Color(0xFF546E7A)
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                "Inicie a captura, navegue pelo app de clima na central por ~1 min, depois pare e envie o .pcap para analisar no Wireshark.",
                color = Color(0xFF37474F),
                fontSize = 11.sp
            )
        }

        // ── Seção de interceptação HTTPS ──────────────────────────────────
        ProxySection(state = state)

        // ── Diagnóstico de clima ──────────────────────────────────────────
        ClimaScanSection(state = state)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Seção Proxy HTTPS (mitm) — embutida na aba Rede
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProxySection(state: EngineReverseStateHolder) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var uploadingProxy by remember { mutableStateOf(false) }

    fun runShell(cmd: String, onResult: (String) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            var telnet: br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper? = null
            try {
                telnet = br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper()
                telnet.connect("127.0.0.1", 23)
                val result = telnet.executeCommand(cmd, 12000)
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { state.proxyStatus = "Erro shell: ${e.message}" }
            } finally {
                try { telnet?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    // ── Card de status ────────────────────────────────────────────────────
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape  = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF2A1A3E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.proxyRunning) CircularProgressIndicator(
                    Modifier.size(12.dp), color = Color(0xFFCE93D8), strokeWidth = 1.5.dp)
                Text(
                    "Interceptação HTTPS",
                    color = Color(0xFFCE93D8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "CA: ${if (state.caInstalled) "Instalado ✓" else "Não instalado"}   " +
                "Proxy: ${if (state.proxyRunning) "Ativo :${state.proxyServer?.port ?: 8443}" else "Parado"}   " +
                "${state.interceptedReqs.size} req interceptadas",
                color = Color(0xFF546E7A), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            if (state.proxyStatus.isNotBlank()) Text(
                state.proxyStatus,
                color = when {
                    state.proxyStatus.startsWith("Erro") -> Color(0xFFEF9A9A)
                    state.proxyStatus.startsWith("✓")    -> Color(0xFF81C784)
                    else -> Color(0xFFFFD54F)
                },
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
    }

    // ── Botões CA + Proxy ─────────────────────────────────────────────────
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {

        // Gerar & Instalar CA
        Button(
            onClick = {
                state.proxyStatus = "Gerando certificados..."
                scope.launch(Dispatchers.IO) {
                    try {
                        br.com.redesurftank.havalenginereverse.utils.ProxyCA.generate(context)
                        val pemPath = "${context.filesDir}/${br.com.redesurftank.havalenginereverse.utils.ProxyCA.PEM_FILE}"
                        withContext(Dispatchers.Main) { state.proxyStatus = "Instalando CA no sistema..." }
                        // Instala via shell root
                        runShell(
                            "CERTPATH=$pemPath; " +
                            "HASH=\$(openssl x509 -subject_hash_old -noout -in \$CERTPATH 2>/dev/null); " +
                            "mount -o remount,rw /system 2>/dev/null || mount -o rw,remount /system 2>/dev/null; " +
                            "cp \$CERTPATH /system/etc/security/cacerts/\${HASH}.0 && " +
                            "chmod 644 /system/etc/security/cacerts/\${HASH}.0; " +
                            "mount -o remount,ro /system 2>/dev/null || mount -o ro,remount /system 2>/dev/null; " +
                            "echo \"DONE:\${HASH}\""
                        ) { result ->
                            if (result.contains("DONE:")) {
                                state.caInstalled  = true
                                state.proxyStatus  = "✓ CA instalado. Reinicie o headunit para ativar."
                            } else {
                                state.proxyStatus  = "Erro ao instalar CA: $result"
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { state.proxyStatus = "Erro: ${e.message}" }
                    }
                }
            },
            enabled = !state.proxyRunning,
            colors  = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A1A2E),
                disabledContainerColor = Color(0xFF111118)
            ),
            border = BorderStroke(1.dp, if (!state.proxyRunning) Color(0xFFCE93D8) else Color(0x22CE93D8)),
            shape  = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        ) { Text("Gerar CA", color = if (!state.proxyRunning) Color(0xFFCE93D8) else Color(0xFF332233), fontSize = 12.sp) }

        // Iniciar / Parar proxy
        Button(
            onClick = {
                if (!state.proxyRunning) {
                    // INICIAR
                    scope.launch(Dispatchers.IO) {
                        try {
                            br.com.redesurftank.havalenginereverse.utils.ProxyCA.initialize(context)
                            val sslCtx = br.com.redesurftank.havalenginereverse.utils.ProxyCA.serverSslContext()
                            val server = br.com.redesurftank.havalenginereverse.utils.MitmProxyServer(8443) { entry ->
                                state.interceptedReqs.add(0, entry)
                                if (state.interceptedReqs.size > 500) state.interceptedReqs.removeAt(state.interceptedReqs.lastIndex)
                            }
                            server.start(sslCtx)
                            withContext(Dispatchers.Main) {
                                state.proxyServer = server
                                state.proxyRunning = true
                                state.proxyStatus  = "Proxy ativo. Aplicando redirecionamentos..."
                            }
                            // /etc/hosts + iptables
                            runShell(
                                "mount -o remount,rw /system 2>/dev/null || mount -o rw,remount /system 2>/dev/null; " +
                                "grep -qF 'ap-hu-gateway' /system/etc/hosts || " +
                                "  echo '127.0.0.1 ${br.com.redesurftank.havalenginereverse.utils.ProxyCA.TARGET_DOMAIN}' >> /system/etc/hosts; " +
                                "mount -o remount,ro /system 2>/dev/null || mount -o ro,remount /system 2>/dev/null; " +
                                "iptables -t nat -C OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports 8443 2>/dev/null || " +
                                "  iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports 8443; " +
                                "echo OK"
                            ) { state.proxyStatus = "✓ Proxy ativo na porta 8443" }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                state.proxyRunning = false
                                state.proxyStatus  = "Erro ao iniciar: ${e.message}"
                            }
                        }
                    }
                } else {
                    // PARAR
                    state.proxyServer?.stop()
                    state.proxyServer  = null
                    state.proxyRunning = false
                    state.proxyStatus  = "Proxy parado. Removendo redirecionamentos..."
                    runShell(
                        "iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports 8443 2>/dev/null; " +
                        "mount -o remount,rw /system 2>/dev/null || mount -o rw,remount /system 2>/dev/null; " +
                        "sed -i '/ap-hu-gateway/d' /system/etc/hosts 2>/dev/null; " +
                        "mount -o remount,ro /system 2>/dev/null || mount -o ro,remount /system 2>/dev/null; " +
                        "echo OK"
                    ) { state.proxyStatus = "Proxy parado." }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.proxyRunning) Color(0xFF2A1A1A) else Color(0xFF1A1A2E),
                disabledContainerColor = Color(0xFF111118)
            ),
            border = BorderStroke(1.dp, if (state.proxyRunning) Color(0xFFEF9A9A) else Color(0x88CE93D8)),
            shape  = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                if (state.proxyRunning) "Parar" else "Iniciar Proxy",
                color = if (state.proxyRunning) Color(0xFFEF9A9A) else Color(0xFFCE93D8),
                fontSize = 12.sp
            )
        }
    }

    // ── Lista de requisições interceptadas ────────────────────────────────
    if (state.interceptedReqs.isNotEmpty()) {

        // Botão enviar interceptações
        Button(
            onClick = {
                uploadingProxy = true
                val json = buildString {
                    append("[")
                    state.interceptedReqs.forEachIndexed { i, e ->
                        append("""{"time":"${e.time}","method":"${e.method}","host":"${e.host}","path":${
                            org.json.JSONObject.quote(e.path)},"status":${e.responseCode},"requestBody":${
                            org.json.JSONObject.quote(e.requestBody)},"responseBody":${
                            org.json.JSONObject.quote(e.responseBody)}}""")
                        if (i < state.interceptedReqs.lastIndex) append(",")
                    }
                    append("]")
                }
                FirebaseLogUploader.uploadJson(
                    json       = json,
                    onProgress = { state.proxyStatus = it },
                    onSuccess  = { state.proxyStatus = "✓ Enviado: $it"; uploadingProxy = false },
                    onError    = { state.proxyStatus = "Erro: $it"; uploadingProxy = false }
                )
            },
            enabled = !uploadingProxy,
            colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A2E)),
            border  = BorderStroke(1.dp, Color(0x5581C784)),
            shape   = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uploadingProxy) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF81C784), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Enviar interceptações (${state.interceptedReqs.size})", color = Color(0xFF81C784), fontSize = 12.sp)
        }

        Card(
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF0A0A14)),
            shape    = RoundedCornerShape(8.dp),
            border   = BorderStroke(1.dp, Color(0xFF1A1A2E)),
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
        ) {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(state.interceptedReqs) { entry ->
                    val expanded = expandedId == entry.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedId = if (expanded) null else entry.id }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${entry.time}  ${entry.method}  ${entry.path.take(60)}",
                                color = Color(0xFF80CBC4), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${entry.responseCode}",
                                color = when {
                                    entry.responseCode in 200..299 -> Color(0xFF81C784)
                                    entry.responseCode in 400..499 -> Color(0xFFFFD54F)
                                    else -> Color(0xFFEF9A9A)
                                },
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        if (expanded) {
                            Spacer(Modifier.height(4.dp))
                            Text("── Request Headers ──", color = Color(0xFF37474F), fontSize = 9.sp)
                            Text(entry.requestHeaders, color = Color(0xFF546E7A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            if (entry.requestBody.isNotBlank()) {
                                Text("── Request Body ──", color = Color(0xFF37474F), fontSize = 9.sp)
                                Text(entry.requestBody.take(2000), color = Color(0xFF80CBC4), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text("── Response Body ──", color = Color(0xFF37474F), fontSize = 9.sp)
                            Text(entry.responseBody.take(2000), color = Color(0xFFCE93D8), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                        HorizontalDivider(color = Color(0xFF1A1A2E), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
