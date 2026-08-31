package br.com.redesurftank.havalenginereverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.utils.WindowModeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onde ficam as escolhas do usuário entre um boot e outro. A barra
 * (SidebarActivity) lê o componente daqui pra saber o que devolver ao fullscreen.
 */
object AaSplitPrefs {
    private const val PREFS = "engine_reverse_prefs"
    private const val KEY_COMPONENT = "aa_split_component"
    private const val KEY_PERCENT = "aa_split_percent"

    fun component(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COMPONENT, "") ?: ""

    fun setComponent(ctx: Context, v: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMPONENT, v).apply()
    }

    fun percent(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PERCENT, 30)

    fun setPercent(ctx: Context, v: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PERCENT, v).apply()
    }
}

/**
 * Aba "AA Split" — estreita o Android Auto e coloca a barra de ações na esquerda.
 *
 * A projeção do AA não pode ser embutida numa View nossa (Surface do processo
 * dele), então o caminho é redimensionar a task pelo WindowManager via Shizuku.
 * Toda a mecânica está em WindowModeUtils; aqui é só o painel de controle — e ele
 * mostra os comandos crus de propósito, porque na primeira rodada a gente ainda
 * não sabe quais deles a central aceita.
 */
@Composable
fun AaSplitTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var component by remember { mutableStateOf(AaSplitPrefs.component(context)) }
    var percentTxt by remember { mutableStateOf(AaSplitPrefs.percent(context).toString()) }
    var customCmd by remember { mutableStateOf("") }

    var screen by remember { mutableStateOf<WindowModeUtils.ScreenSize?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    var fbBusy by remember { mutableStateOf(false) }
    var fbMsg by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun addLog(s: String) {
        log.add(0, "[${stamp()}] $s")
        while (log.size > 80) log.removeAt(log.size - 1)
    }

    /** Shizuku bloqueia; nada disso pode rodar na main thread. */
    fun run(label: String, block: () -> String) {
        if (busy) return
        busy = true
        addLog("$label…")
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try { block() } catch (t: Throwable) { "erro: ${t.message}" }
            }
            addLog(res.ifBlank { "(sem saída)" })
            busy = false
        }
    }

    val percent = percentTxt.toIntOrNull()?.coerceIn(10, 70) ?: 30
    val sidebarPx = screen?.let { it.width * percent / 100 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Android Auto em janela",
            color = Color(0xFF4FC3F7), fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        Text(
            "Não dá pra desenhar a projeção do AA dentro do nosso app — a Surface é do " +
                "processo dele. O que dá é forçar o WindowManager a encolher a task e " +
                "colocar a barra de ações na faixa que sobrar. Requer Shizuku ativo.",
            color = Color(0xFF90A4AE), fontSize = 11.sp
        )

        // ── 1. Diagnóstico ───────────────────────────────────────────────
        SplitCard("1 · Diagnóstico") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("Escanear", busy) {
                    run("Escaneando central") {
                        val s = WindowModeUtils.screenSize()
                        screen = s
                        candidates = WindowModeUtils.candidatePackages()
                        buildString {
                            appendLine(WindowModeUtils.androidVersion())
                            appendLine("tela: " + (s?.let { "${it.width}x${it.height}" } ?: "?"))
                            appendLine(WindowModeUtils.readFlags())
                            appendLine("candidatos: ${candidates.size}")
                        }
                    }
                }
                SplitButton("Capturar app no topo", busy) {
                    run("Lendo activity no topo") {
                        val top = WindowModeUtils.topActivity()
                        if (top == null) {
                            "não consegui ler o topo"
                        } else {
                            component = top.component
                            AaSplitPrefs.setComponent(context, component)
                            "topo: ${top.component}  (task ${top.taskId})"
                        }
                    }
                }
            }
            Text(
                "Abra o Android Auto na central e toque em \"Capturar app no topo\" — " +
                    "é assim que descobrimos o pacote do receiver sem chutar.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            if (candidates.isNotEmpty()) {
                Text(
                    "Candidatos (toque pra usar):",
                    color = Color(0xFF90A4AE), fontSize = 11.sp
                )
                candidates.forEach { pkg ->
                    Text(
                        pkg,
                        color = Color(0xFF4FC3F7),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                run("Resolvendo activity de $pkg") {
                                    val c = WindowModeUtils.launchComponentOf(pkg)
                                    if (c == null) {
                                        "$pkg não tem activity de LAUNCHER — use \"Capturar app no topo\""
                                    } else {
                                        component = c
                                        AaSplitPrefs.setComponent(context, c)
                                        "alvo = $c"
                                    }
                                }
                            }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }

        // ── 2. Alvo e geometria ──────────────────────────────────────────
        SplitCard("2 · Alvo e geometria") {
            OutlinedTextField(
                value = component,
                onValueChange = {
                    component = it
                    AaSplitPrefs.setComponent(context, it)
                },
                label = { Text("pacote/activity do AA", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFFE0E0E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = percentTxt,
                    onValueChange = {
                        percentTxt = it.filter { c -> c.isDigit() }.take(2)
                        percentTxt.toIntOrNull()?.let { p -> AaSplitPrefs.setPercent(context, p) }
                    },
                    label = { Text("barra %", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color(0xFFE0E0E0), fontSize = 12.sp
                    ),
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    screen?.let { "tela ${it.width}x${it.height} → barra ${sidebarPx}px, AA ${it.width - (sidebarPx ?: 0)}px" }
                        ?: "rode o Escanear pra ler o tamanho da tela",
                    color = Color(0xFF90A4AE), fontSize = 11.sp
                )
            }
        }

        // ── 3. Flags globais ─────────────────────────────────────────────
        SplitCard("3 · Flags globais (plano B)") {
            Text(
                "No Android 9 o system_server só lê force_resizable_activities e " +
                    "enable_freeform_support no boot. Ligue, reinicie a central, e só então " +
                    "tente de novo — mas tente antes SEM elas: o \"am task resizeable\" do " +
                    "passo 4 costuma bastar e não exige reboot.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("Ler", busy) { run("Lendo flags") { WindowModeUtils.readFlags() } }
                SplitButton("Ligar", busy) { run("Ligando flags") { WindowModeUtils.enableFlags() } }
                SplitButton("Desligar", busy) { run("Desligando flags") { WindowModeUtils.disableFlags() } }
            }
            if (!confirmReboot) {
                SplitButton("Reiniciar central", busy, accent = Color(0xFF6D4C41)) {
                    confirmReboot = true
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reiniciar agora?", color = Color(0xFFFFAB91), fontSize = 12.sp)
                    SplitButton("Sim, reiniciar", busy, accent = Color(0xFFB71C1C)) {
                        confirmReboot = false
                        run("Reiniciando") { WindowModeUtils.sh("svc power reboot") }
                    }
                    SplitButton("Cancelar", busy) { confirmReboot = false }
                }
            }
        }

        // ── 4. Aplicar ───────────────────────────────────────────────────
        SplitCard("4 · Aplicar") {
            Text(
                "Tente na ordem. Freeform dá controle exato da geometria; split é mais " +
                    "\"nativo\" mas quem decide o tamanho é o WM. Se o AA piscar, congelar " +
                    "ou voltar sozinho pro fullscreen, é ele renegociando o stream — anote " +
                    "no log e passe pra próxima.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            val s = screen
            val px = sidebarPx
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("A · Freeform", busy) {
                    if (s == null || px == null) addLog("rode o Escanear antes")
                    else run("Aplicando freeform") {
                        WindowModeUtils.applyFreeform(component.trim(), px, s)
                    }
                }
                SplitButton("B · Split", busy) {
                    if (s == null || px == null) addLog("rode o Escanear antes")
                    else run("Aplicando split") {
                        WindowModeUtils.applySplit(component.trim(), px, s)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("C · Só redimensionar", busy) {
                    if (s == null || px == null) addLog("rode o Escanear antes")
                    else run("Redimensionando task") {
                        WindowModeUtils.resizeOnly(component.substringBefore("/").trim(), px, s)
                    }
                }
                SplitButton("Restaurar", busy, accent = Color(0xFF8D6E63)) {
                    run("Restaurando fullscreen") { WindowModeUtils.restore(component.trim()) }
                }
            }
            SplitButton("Ver stacks", busy) { run("am stack list") { WindowModeUtils.stacks() } }
        }

        // ── 5. Comando livre ─────────────────────────────────────────────
        SplitCard("5 · Comando livre") {
            OutlinedTextField(
                value = customCmd,
                onValueChange = { customCmd = it },
                label = { Text("shell (roda como shell, não root)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFFE0E0E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.fillMaxWidth()
            )
            SplitButton("Executar", busy) {
                val c = customCmd.trim()
                if (c.isEmpty()) addLog("comando vazio")
                else run("\$ $c") { WindowModeUtils.sh(c) }
            }
        }

        // ── 6. Diagnóstico → Firebase ────────────────────────────────────
        SplitCard("6 · Diagnóstico → Firebase") {
            Text(
                "Coleta o estado real do WindowManager (stacks, bounds, resizeMode do alvo, " +
                    "features de multi-window) + o logcat do ActivityManager — é lá que sai o " +
                    "motivo de um resize ser recusado, porque o `am` devolve sucesso mesmo " +
                    "quando o WM ignora o pedido. Junta o log desta aba e envia.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            SplitButton(
                if (fbBusy) "enviando…" else "enviar diagnóstico pro Firebase",
                fbBusy,
                accent = Color(0xFF00695C)
            ) {
                fbBusy = true
                fbMsg = "coletando…"
                scope.launch {
                    val target = component.trim()
                    val tabLog = log.joinToString("\n")
                    val diag = withContext(Dispatchers.IO) {
                        WindowModeUtils.collectDiagnostics(target, tabLog)
                    }
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val name = "aasplit_diag_$ts.txt"
                    val f = java.io.File(context.cacheDir, name)
                    withContext(Dispatchers.IO) { f.writeText(diag) }
                    fbMsg = "enviando ${f.length() / 1024} KB…"
                    // uploadFile usa Looper internamente — tem que ser na main thread.
                    FirebaseLogUploader.uploadFile(
                        file = f, destName = name,
                        onProgress = { fbMsg = it },
                        onSuccess = { url ->
                            fbMsg = "✓ $url"
                            val cb = context
                                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("firebase-log", url))
                            fbBusy = false
                        },
                        onError = { fbMsg = "erro: $it"; fbBusy = false }
                    )
                }
            }
            if (fbMsg.isNotBlank()) {
                Text(
                    fbMsg, color = Color(0xFF4FC3F7), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Log ──────────────────────────────────────────────────────────
        SplitCard("Log") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("Copiar", false) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("aa-split", log.joinToString("\n")))
                    addLog("log copiado")
                }
                SplitButton("Limpar", false) { log.clear() }
            }
            if (log.isEmpty()) {
                Text("vazio", color = Color(0xFF546E7A), fontSize = 11.sp)
            } else {
                log.forEach {
                    Text(
                        it,
                        color = Color(0xFFB0BEC5),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        border = BorderStroke(1.dp, Color(0xFF263238)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = Color(0xFF4FC3F7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SplitButton(
    label: String,
    busy: Boolean,
    accent: Color = Color(0xFF1E3A5F),
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFFE0E0E0), fontSize = 12.sp, maxLines = 1)
    }
}
