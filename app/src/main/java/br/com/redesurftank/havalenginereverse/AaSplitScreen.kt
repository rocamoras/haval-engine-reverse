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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.utils.WindowModeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * O log vive fora da composição de propósito.
 *
 * Mexer em janela relança activities, inclusive a nossa — e cada relançamento
 * zerava um `remember`, então o log chegava vazio no Firebase justamente quando
 * havia algo pra ler.
 */
object AaSplitLog {
    val lines = mutableStateListOf<String>()

    fun add(s: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add(0, "[$ts] $s")
        while (lines.size > 200) lines.removeAt(lines.size - 1)
    }

    fun dump() = lines.joinToString("\n")
}

/**
 * Aba "AA Split" — estreita o Android Auto e coloca a barra de ações na esquerda.
 *
 * A projeção do AA não pode ser embutida numa View nossa (Surface do processo
 * dele), então o caminho é redimensionar a task pelo WindowManager via Shizuku.
 * Toda a mecânica está em WindowModeUtils; aqui é só o painel de controle.
 *
 * O passo 0 (probe) existe porque a central rebaixa modos não suportados em
 * silêncio: o `am` responde sucesso e a stack sai fullscreen. Sem medir isso
 * primeiro, as tentativas em cima do AA não dizem nada.
 */
@Composable
fun AaSplitTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var component by remember { mutableStateOf(AaSplitPrefs.component(context)) }
    var percentTxt by remember { mutableStateOf(AaSplitPrefs.percent(context).toString()) }
    var customCmd by remember { mutableStateOf("") }

    var area by remember { mutableStateOf<WindowModeUtils.Area?>(null) }
    var tasks by remember { mutableStateOf<List<WindowModeUtils.TaskInfo>>(emptyList()) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    var fbBusy by remember { mutableStateOf(false) }
    var fbMsg by remember { mutableStateOf("") }

    fun addLog(s: String) = AaSplitLog.add(s)

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

    fun setTarget(v: String) {
        component = v
        AaSplitPrefs.setComponent(context, v)
    }

    val percent = percentTxt.toIntOrNull()?.coerceIn(10, 70) ?: 30
    val sidebarPx = area?.let { it.width * percent / 100 }

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

        // ── 0. Probe ─────────────────────────────────────────────────────
        SplitCard("0 · A central aceita multi-window?") {
            Text(
                "Faça isto ANTES de mexer no AA. Usa a nossa própria barra como cobaia: " +
                    "pede freeform e split, relê o estado e diz o que o WM concedeu de " +
                    "verdade. Se os dois derem NEGADO, nenhuma das estratégias do passo 4 " +
                    "vai funcionar e o caminho passa a ser overlay por cima do AA.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            SplitButton("Testar suporte", busy, accent = Color(0xFF4A148C)) {
                run("Testando suporte a multi-window") { WindowModeUtils.probeMultiWindow() }
            }
        }

        // ── 1. Alvo ──────────────────────────────────────────────────────
        SplitCard("1 · Achar a task do Android Auto") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("Escanear", busy) {
                    run("Escaneando central") {
                        val a = WindowModeUtils.usableArea()
                        area = a
                        tasks = WindowModeUtils.listTasks()
                        buildString {
                            appendLine(WindowModeUtils.androidVersion())
                            appendLine("área utilizável: " + (a?.toString() ?: "?"))
                            appendLine(WindowModeUtils.readFlags())
                            appendLine("tasks abertas: ${tasks.size}")
                        }
                    }
                }
                SplitButton(
                    if (countdown > 0) "capturando em ${countdown}s" else "Capturar em 10s",
                    busy || countdown > 0,
                    accent = Color(0xFF00695C)
                ) {
                    scope.launch {
                        addLog("abra o Android Auto agora — capturo em 10s")
                        for (i in 10 downTo 1) { countdown = i; delay(1000) }
                        countdown = 0
                        val top = withContext(Dispatchers.IO) { WindowModeUtils.topActivity() }
                        if (top == null) {
                            addLog("não consegui ler o topo")
                        } else if (top.pkg == WindowModeUtils.SIDEBAR_PKG) {
                            addLog("capturei o nosso próprio app — você não trocou de tela")
                        } else {
                            setTarget(top.component)
                            addLog("alvo = ${top.component} (task ${top.taskId})")
                        }
                        tasks = withContext(Dispatchers.IO) { WindowModeUtils.listTasks() }
                    }
                }
            }
            Text(
                "\"Capturar em 10s\" é o que funciona: o botão de captura imediata só via o " +
                    "nosso próprio app, porque pra tocar nele você tem que estar aqui. " +
                    "Toque, troque pro Android Auto, espere a contagem.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            if (tasks.isNotEmpty()) {
                Text(
                    "Ou escolha na lista de tasks abertas (o AA continua listado depois que " +
                        "você sai dele, com visible=false):",
                    color = Color(0xFF90A4AE), fontSize = 11.sp
                )
                tasks.forEach { t ->
                    val mine = t.pkg == WindowModeUtils.SIDEBAR_PKG
                    Text(
                        "t${t.taskId} s${t.stackId} ${if (t.visible) "●" else "○"} ${t.component}",
                        color = if (mine) Color(0xFF546E7A) else Color(0xFF4FC3F7),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy && !mine) { setTarget(t.component) }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }

        // ── 2. Alvo e geometria ──────────────────────────────────────────
        SplitCard("2 · Alvo e geometria") {
            OutlinedTextField(
                value = component,
                onValueChange = { setTarget(it) },
                label = { Text("pacote/activity do AA", fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(0xFFE0E0E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (component.substringBefore("/") == WindowModeUtils.SIDEBAR_PKG) {
                Text(
                    "⚠ o alvo é o nosso próprio app — o passo 4 vai recusar. " +
                        "Use \"Capturar em 10s\".",
                    color = Color(0xFFFFAB91), fontSize = 10.sp
                )
            }
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
                    textStyle = TextStyle(color = Color(0xFFE0E0E0), fontSize = 12.sp),
                    modifier = Modifier.width(110.dp)
                )
                val a = area
                Text(
                    if (a != null && sidebarPx != null)
                        "útil $a → barra ${a.left}..${a.left + sidebarPx}, " +
                            "AA ${a.left + sidebarPx}..${a.right}"
                    else
                        "rode o Escanear pra ler a área utilizável",
                    color = Color(0xFF90A4AE), fontSize = 11.sp
                )
            }
            Text(
                "A área útil não é a tela: aqui a barra de sistema come 128px na esquerda " +
                    "(mBounds 0..1792 mas mAppBounds 128..1920), então posicionar em x=0 " +
                    "esconde a barra debaixo dela.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
        }

        // ── 3. Flags globais ─────────────────────────────────────────────
        SplitCard("3 · Flags globais") {
            Text(
                "No Android 9 o system_server só lê force_resizable_activities e " +
                    "enable_freeform_support no boot. Se o probe do passo 0 der NEGADO com " +
                    "as flags já em 1, é porque falta reiniciar — ligue, reinicie, e refaça " +
                    "o probe antes de qualquer coisa.",
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
                "Cada estratégia agora relê e mostra o modo EFETIVO da stack depois de " +
                    "aplicar — é o único jeito de saber se o WM obedeceu ou só disse que sim.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
            val a = area
            val px = sidebarPx
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("A · Freeform", busy) {
                    if (a == null || px == null) addLog("rode o Escanear antes")
                    else run("Aplicando freeform") {
                        WindowModeUtils.applyFreeform(component.trim(), px, a)
                    }
                }
                SplitButton("B · Split", busy) {
                    if (a == null || px == null) addLog("rode o Escanear antes")
                    else run("Aplicando split") {
                        WindowModeUtils.applySplit(component.trim(), px, a)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("C · Só redimensionar", busy) {
                    if (a == null || px == null) addLog("rode o Escanear antes")
                    else run("Redimensionando task") {
                        WindowModeUtils.resizeOnly(component.substringBefore("/").trim(), px, a)
                    }
                }
                SplitButton("Restaurar", busy, accent = Color(0xFF8D6E63)) {
                    run("Restaurando fullscreen") { WindowModeUtils.restore(component.trim()) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("D · Só a barra", busy, accent = Color(0xFF00695C)) {
                    if (a == null || px == null) addLog("rode o Escanear antes")
                    else run("Subindo só a barra") { WindowModeUtils.launchSidebar(px, a) }
                }
                SplitButton("Ver stacks", busy) { run("am stack list") { WindowModeUtils.stacks() } }
            }
            Text(
                "D serve quando o AA já está na direita (a task lembra os bounds) e só a " +
                    "barra falta — foi o caso de 01/09: o AA foi, a barra não subiu.",
                color = Color(0xFF78909C), fontSize = 10.sp
            )
        }

        // ── 5. Comando livre ─────────────────────────────────────────────
        SplitCard("5 · Comando livre") {
            OutlinedTextField(
                value = customCmd,
                onValueChange = { customCmd = it },
                label = { Text("shell (roda como shell, não root)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(
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
                    "suporte efetivo a multi-window) + o logcat do ActivityManager, junta o " +
                    "log desta aba e envia. O log sobrevive a relançamentos agora.",
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
                    val tabLog = AaSplitLog.dump()
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
        SplitCard("Log (${AaSplitLog.lines.size})") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitButton("Copiar", false) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("aa-split", AaSplitLog.dump()))
                    addLog("log copiado")
                }
                SplitButton("Limpar", false) { AaSplitLog.lines.clear() }
            }
            if (AaSplitLog.lines.isEmpty()) {
                Text("vazio", color = Color(0xFF546E7A), fontSize = 11.sp)
            } else {
                AaSplitLog.lines.forEach {
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
