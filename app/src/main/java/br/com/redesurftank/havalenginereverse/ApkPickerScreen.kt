package br.com.redesurftank.havalenginereverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aba "APKs" — lista os apps do head unit (processos rodando primeiro) e permite
 * extrair + subir o APK escolhido pro Firebase Storage (logs/).
 *
 * Extração: copia direto de ApplicationInfo.sourceDir; se o arquivo não for legível
 * pelo app, cai pro shell (telnet 127.0.0.1:23, mesmo caminho usado pelo serviço).
 */

private data class ApkRow(
    val pkg: String,
    val label: String,
    val running: Boolean,
    val system: Boolean,
    val sources: List<String>,   // base + splits
    val sizeBytes: Long
)

/** Executa um comando no shell do head unit via telnet (mesmo truque do serviço). */
private fun shell(cmd: String, timeoutMs: Long = 15000): String {
    var telnet: TelnetClientWrapper? = null
    return try {
        telnet = TelnetClientWrapper()
        telnet.connect("127.0.0.1", 23)
        val tmp = "/data/local/tmp/apkpicker_out.txt"
        telnet.executeCommand("$cmd > $tmp 2>/dev/null; echo ok", timeoutMs)
        val out = telnet.executeCommand("cat $tmp 2>/dev/null", 10000)
        telnet.executeCommand("rm $tmp 2>/dev/null")
        out
    } catch (e: Exception) {
        ""
    } finally {
        try { telnet?.disconnect() } catch (_: Exception) {}
    }
}

/** Nomes de processo ativos (o nome do processo costuma ser o próprio pacote). */
private fun runningProcessNames(): Set<String> {
    val out = shell("ps -A -o NAME 2>/dev/null || ps -o NAME")
    return out.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "NAME" }
        .map { it.substringBefore(':') }   // com.foo:remote → com.foo
        .toSet()
}

private fun scanApps(context: Context): List<ApkRow> {
    val pm = context.packageManager
    val running = runningProcessNames()
    val apps: List<ApplicationInfo> = try {
        pm.getInstalledApplications(0)
    } catch (e: Exception) {
        emptyList()
    }
    return apps.map { ai ->
        val sources = buildList {
            ai.sourceDir?.let { add(it) }
            ai.splitSourceDirs?.let { addAll(it) }
        }
        val size = sources.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
        ApkRow(
            pkg = ai.packageName,
            label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName),
            running = running.contains(ai.packageName),
            system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            sources = sources,
            sizeBytes = size
        )
    }.sortedWith(compareByDescending<ApkRow> { it.running }.thenBy { it.label.lowercase() })
}

/**
 * Copia um APK pro diretório privado do app (legível sem permissões).
 * Retorna o arquivo ou null com a mensagem de erro no callback.
 */
private fun extractApk(context: Context, src: String, destName: String): Pair<File?, String> {
    val dir = context.getExternalFilesDir("apk-picker") ?: File(context.filesDir, "apk-picker")
    dir.mkdirs()
    val dst = File(dir, destName)
    // 1) leitura direta (a maioria dos APKs é 0644)
    try {
        File(src).inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        if (dst.length() > 0) return dst to "cópia direta"
    } catch (_: Exception) {
        // cai pro shell
    }
    // 2) shell (telnet) — necessário quando o diretório do pacote não é atravessável
    shell("cp -f '$src' '${dst.absolutePath}' && chmod 644 '${dst.absolutePath}'", 60000)
    return if (dst.exists() && dst.length() > 0) dst to "cópia via shell"
    else null to "não foi possível ler $src (sem Shizuku/telnet?)"
}

@Composable
fun ApkPickerTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rows = remember { mutableStateListOf<ApkRow>() }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var onlyRunning by remember { mutableStateOf(true) }
    var hideSystem by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var lastLink by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var confirmRow by remember { mutableStateOf<ApkRow?>(null) }

    fun refresh() {
        if (loading) return
        loading = true
        status = "Listando pacotes e processos…"
        scope.launch {
            val list = withContext(Dispatchers.IO) { scanApps(context) }
            rows.clear(); rows.addAll(list)
            val run = list.count { it.running }
            status = "${list.size} pacotes — $run rodando" +
                if (run == 0) " (sem shell? mostre todos desmarcando \"só rodando\")" else ""
            loading = false
        }
    }

    LaunchedEffect(Unit) { if (rows.isEmpty()) refresh() }

    /** Extrai (IO) e sobe (main thread — Firebase exige Looper) cada source em sequência. */
    fun uploadRow(row: ApkRow) {
        if (uploading) return
        uploading = true
        lastLink = ""
        val links = StringBuilder()

        fun step(index: Int) {
            if (index >= row.sources.size) {
                uploading = false
                status = "✓ ${row.pkg} enviado"
                lastLink = links.toString().trim()
                return
            }
            val src = row.sources[index]
            val destName = row.pkg + if (index == 0) ".apk" else "_split$index.apk"
            status = "(${index + 1}/${row.sources.size}) extraindo $destName…"
            scope.launch {
                val (file, how) = withContext(Dispatchers.IO) { extractApk(context, src, destName) }
                if (file == null) {
                    uploading = false
                    status = "Erro: $how"
                    return@launch
                }
                status = "(${index + 1}/${row.sources.size}) $destName — $how, enviando…"
                FirebaseLogUploader.uploadFile(
                    file = file,
                    destName = destName,
                    onProgress = { status = "(${index + 1}/${row.sources.size}) $it" },
                    onSuccess = { url ->
                        links.append(destName).append(": ").append(url).append("\n")
                        runCatching { file.delete() }
                        step(index + 1)
                    },
                    onError = {
                        uploading = false
                        status = "Erro em $destName: $it"
                    }
                )
            }
        }
        step(0)
    }

    val filtered = rows.filter { r ->
        (!onlyRunning || r.running) &&
            (!hideSystem || !r.system) &&
            (query.isBlank() ||
                r.pkg.contains(query, true) || r.label.contains(query, true))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Extrair APK e subir pro Firebase", color = Color(0xFF4FC3F7),
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Lista os apps do head unit (processos ativos primeiro). Toque num item para " +
                "copiar o APK e enviar pro Storage (logs/). Splits vão junto.",
            color = Color(0xFF90A4AE), fontSize = 11.sp
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filtrar por nome ou pacote", fontSize = 11.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color(0xFFECEFF1)),
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = onlyRunning,
                onClick = { onlyRunning = !onlyRunning },
                label = { Text("só rodando", fontSize = 11.sp) }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = hideSystem,
                onClick = { hideSystem = !hideSystem },
                label = { Text("ocultar sistema", fontSize = 11.sp) }
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh() }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = Color(0xFF4FC3F7)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text("Atualizar", color = Color(0xFF4FC3F7), fontSize = 12.sp)
            }
        }

        if (status.isNotBlank()) {
            Text(status, color = Color(0xFFB0BEC5), fontSize = 11.sp)
        }
        if (lastLink.isNotBlank()) {
            Text(
                lastLink,
                color = Color(0xFF81C784), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("apk-link", lastLink))
                    status = "Link copiado"
                }
            )
        }

        Text("${filtered.size} app(s)", color = Color(0xFF546E7A), fontSize = 10.sp)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.pkg }) { row ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    border = BorderStroke(
                        1.dp,
                        if (row.running) Color(0xFF2E7D32) else Color(0xFF263238)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !uploading) { confirmRow = row }
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (row.running) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(Color(0xFF66BB6A), RoundedCornerShape(4.dp))
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                row.label, color = Color(0xFFECEFF1), fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${row.sizeBytes / 1024} KB",
                                color = Color(0xFF546E7A), fontSize = 10.sp
                            )
                        }
                        Text(
                            row.pkg + if (row.sources.size > 1) "  (+${row.sources.size - 1} split)" else "",
                            color = if (row.system) Color(0xFF8D6E63) else Color(0xFF78909C),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    confirmRow?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmRow = null },
            containerColor = Color(0xFF1A1A2E),
            title = { Text("Enviar APK?", color = Color(0xFF4FC3F7), fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.label, color = Color(0xFFECEFF1), fontSize = 13.sp)
                    Text(row.pkg, color = Color(0xFF78909C), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Text(
                        "${row.sources.size} arquivo(s) — ${row.sizeBytes / 1024} KB no total",
                        color = Color(0xFFB0BEC5), fontSize = 11.sp
                    )
                    row.sources.forEach {
                        Text(it, color = Color(0xFF546E7A), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmRow = null; uploadRow(row) }, enabled = !uploading) {
                    Text("Enviar pro Firebase", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRow = null }) {
                    Text("Cancelar", color = Color(0xFF90A4AE), fontSize = 12.sp)
                }
            }
        )
    }
}
