package br.com.redesurftank.havalenginereverse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.overlay.CardCatalog
import br.com.redesurftank.havalenginereverse.overlay.MediaCardOverlay
import br.com.redesurftank.havalenginereverse.utils.FridaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aba "Mídia" — o que aparece na fileira de mídia online da tela principal do app
 * de mídia OEM (com.beantechs.mediacenter). De fábrica o Brasil (country code 17)
 * recebe só o Deezer.
 *
 * Modo padrão (v2.34.0): overlay, sem Frida — overlay/MediaCardOverlay.kt.
 * Modo antigo: Frida hooka OnlineOsModelImpl.getMCpList() e repinta a Activity
 * (res/raw/com_beantechs_mediacenter_cp.js), escondido atrás de um botão.
 * Detalhes: docs/mediacenter-online-cards.md
 */

/** Um CP que o loadOnlineMusicCard() sabe desenhar. */
private data class Cp(
    val id: Int,
    val label: String,
    /** como o clique é resolvido nesta central (ver docs/mediacenter-online-cards.md §10) */
    val how: String
)

/**
 * `how` vem do PROVIDER_TYPE_MAP real, lido do h5.core na central:
 * provisionados abrem pelo caminho OEM (minibar/sessão de mídia); o resto abre
 * por Intent npwas:// no content shell.
 */
private val CPS = listOf(
    Cp(551, "Deezer", "OEM"),
    Cp(552, "YouTube", "OEM"),
    Cp(555, "Reuters TV", "OEM"),
    Cp(557, "ESPN", "OEM"),
    Cp(553, "TuneIn", "intent"),
    Cp(556, "Radioline", "intent"),
    Cp(550, "Amazon Music", "intent"),
    Cp(554, "DAZN", "intent"),
    Cp(503, "JOOX", "—"),
    Cp(504, "myTuner", "—")
)

@Composable
fun MediaCardTab() {
    val scope = rememberCoroutineScope()

    // Preset inicial: o que o usuário pediu — troca o Deezer pelo TuneIn.
    val selected = remember { mutableStateListOf(553) }
    var hooked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showFrida by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var limitMode by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun addLog(s: String) {
        log.add(0, "[${stamp()}] $s")
        if (log.size > 60) log.removeAt(log.size - 1)
    }

    /** Shizuku/Frida bloqueiam — sempre fora da main thread. */
    fun run(label: String, block: () -> String) {
        if (busy) return
        busy = true; addLog("$label…")
        scope.launch {
            val res = withContext(Dispatchers.IO) { block() }
            addLog(res)
            current = withContext(Dispatchers.IO) { FridaUtils.readMediaCp() }
            busy = false
        }
    }

    /** Ativa o hook (se preciso) e escreve o arquivo de controle em um passo. */
    fun applyRaw(csv: String, what: String) {
        if (busy) return
        busy = true; addLog("aplicar $what…")
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                if (!hooked) sb.append(FridaUtils.injectMediaCenterCp()).append(" · ")
                sb.append(FridaUtils.writeMediaCp(csv))
                sb.toString()
            }
            if (res.contains("ativo")) hooked = true
            addLog(res)
            current = withContext(Dispatchers.IO) { FridaUtils.readMediaCp() }
            busy = false
        }
    }

    fun apply(ids: List<Int>, what: String) = applyRaw(ids.joinToString(","), what)

    /** Grava bean_video_limit_mode e relê para confirmar. */
    fun setLimitMode(mode: Int, what: String) {
        if (busy) return
        busy = true; addLog("aviso de vídeo: $what…")
        scope.launch {
            val res = withContext(Dispatchers.IO) { FridaUtils.writeVideoLimitMode(mode) }
            addLog(res)
            limitMode = withContext(Dispatchers.IO) { FridaUtils.readVideoLimitMode() }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        current = withContext(Dispatchers.IO) { FridaUtils.readMediaCp() }
        limitMode = withContext(Dispatchers.IO) { FridaUtils.readVideoLimitMode() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Card de mídia online", color = Color(0xFF4FC3F7),
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Escolhe o que aparece na fileira de mídia online da tela principal do app de " +
                "mídia. De fábrica o Brasil (country code 17) recebe só o Deezer.",
            color = Color(0xFF90A4AE), fontSize = 11.sp
        )

        OverlayCardsSection()

        // ── Card: aviso de vídeo em movimento ────────────────────────────
        McCard {
            Text("Aviso de vídeo em movimento", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(
                "\"A velocidade do veículo > 0 km/h, por favor, não assista a vídeos.\" " +
                    "Não é patch: é o modo do próprio OEM em " +
                    "Settings.System.bean_video_limit_mode, lido ao vivo por ContentObserver. " +
                    "Vale para o content shell (PWAs) e para o player de vídeo USB.",
                color = Color(0xFF90A4AE), fontSize = 10.sp
            )
            Text(
                "modo atual: " + when (limitMode) {
                    "-1" -> "-1 · aviso desligado"
                    "0" -> "0 · qualquer movimento (padrão)"
                    "1" -> "1 · a partir de 15 km/h"
                    "" -> "(não lido — Shizuku ativo?)"
                    else -> limitMode
                },
                color = Color(0xFFB0BEC5), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            McButton("1 · avisar só a partir de 15 km/h (recomendado)", !busy, Color(0xFF2E7D32)) {
                setLimitMode(1, "a partir de 15 km/h")
            }
            McButton("0 · avisar com qualquer movimento (fábrica)", !busy, Color(0xFF37474F)) {
                setLimitMode(0, "qualquer movimento")
            }
            McButton("-1 · desligar o aviso em qualquer velocidade", !busy, Color(0xFFB71C1C)) {
                setLimitMode(-1, "desligado")
            }
            Text(
                "O modo 1 tem histerese: liga o aviso em 15 km/h e libera abaixo de 12 — resolve " +
                    "parado no trânsito e manobra, mantendo o bloqueio em movimento. O -1 libera " +
                    "vídeo na tela do motorista a qualquer velocidade; o risco e a exposição legal " +
                    "são seus. Tudo reversível: é só um setting.",
                color = Color(0xFF546E7A), fontSize = 10.sp
            )
        }

        // ── Modo antigo: Frida dentro do processo da mídia ───────────────
        McButton(
            if (showFrida) "esconder o modo antigo (Frida)"
            else "modo antigo (Frida) — só se o overlay não servir",
            true, Color(0xFF263238)
        ) { showFrida = !showFrida }
        if (showFrida) {
        Text(
            "Requer o APK fat (Frida) + Shizuku ativo e não sobe sozinho no boot. Se o overlay " +
                "estiver ligado, ele fica por cima do que o Frida desenhar.",
            color = Color(0xFF546E7A), fontSize = 10.sp
        )

        // ── Card: as duas opções que você pediu ──────────────────────────
        McCard {
            Text("Opções rápidas", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            McButton("A · remover Deezer, colocar TuneIn", !busy, Color(0xFF2E7D32)) {
                selected.clear(); selected.add(553)
                apply(listOf(553), "só TuneIn")
            }
            McButton("B · remover todos (card vazio)", !busy, Color(0xFFB71C1C)) {
                selected.clear()
                apply(emptyList(), "nenhum ícone")
            }
            McButton("C · esconder o menu inteiro (título + fileira)", !busy, Color(0xFF6D4C41)) {
                selected.clear()
                applyRaw("off", "bloco escondido")
            }
            McButton("D · widget de temperatura no lugar da fileira", !busy, Color(0xFF0277BD)) {
                selected.clear()
                applyRaw("widget", "widget de temperatura")
            }
            McButton("voltar ao padrão (só Deezer)", !busy, Color(0xFF37474F)) {
                selected.clear(); selected.add(551)
                apply(listOf(551), "só Deezer")
            }
            Text(
                "A aplicação é instantânea: o hook repinta a fileira em ~1,5s, sem reiniciar " +
                    "o app de mídia. Se a tela de mídia estiver fechada, vale no próximo abrir.",
                color = Color(0xFF546E7A), fontSize = 10.sp
            )
            Text(
                "No D, a externa sai de car.basic.outside_temp (mesma fonte da barra de " +
                    "status). A chave da interna não é conhecida: o script sonda candidatas " +
                    "e o log diz qual respondeu — se vier \"--\", me manda esse log.",
                color = Color(0xFF546E7A), fontSize = 10.sp
            )
        }

        // ── Card: montar a lista à mão ───────────────────────────────────
        McCard {
            Text("Montar a lista", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(
                "A ordem dos ícones segue a ordem de seleção. \"OEM\" = provisionado no " +
                    "h5.core desta central, abre pelo fluxo original (minibar, sessão de " +
                    "mídia). \"intent\" = não provisionado, o hook abre a PWA por " +
                    "npwas:// no content shell. \"—\" = sem app correspondente aqui.",
                color = Color(0xFF90A4AE), fontSize = 10.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CPS.forEach { cp ->
                    val on = selected.contains(cp.id)
                    FilterChip(
                        selected = on,
                        onClick = {
                            if (on) selected.remove(cp.id) else selected.add(cp.id)
                        },
                        label = {
                            Text(
                                cp.label + "  ${cp.id} · ${cp.how}",
                                fontSize = 11.sp
                            )
                        }
                    )
                }
            }
            Text(
                "seleção: " + if (selected.isEmpty()) "(vazia)" else selected.joinToString(","),
                color = Color(0xFFB0BEC5), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            McButton("aplicar seleção", !busy, Color(0xFF6A1B9A)) {
                apply(selected.toList(), "[" + selected.joinToString(",") + "]")
            }
        }

        // ── Card: estado / manutenção ────────────────────────────────────
        McCard {
            Text(
                if (hooked) "● hook ativo" else "○ hook inativo (é ativado ao aplicar)",
                color = if (hooked) Color(0xFF81C784) else Color(0xFF90A4AE),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                "inject_media_cp = " + current.ifBlank { "(vazio — sem override)" },
                color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McButton("abrir mídia", !busy, Color(0xFF00695C), Modifier.weight(1f)) {
                    run("abrir MediaCenter") { FridaUtils.openMediaCenter() }
                }
                McButton("diagnóstico", !busy, Color(0xFF37474F), Modifier.weight(1f)) {
                    run("diagnóstico") { FridaUtils.mediaCpDiag() }
                }
            }
            McButton("testar TuneIn direto (npwas://)", !busy, Color(0xFF4527A0)) {
                run("abrir TuneIn por intent") {
                    FridaUtils.openPwaDirect("npwas://tunein.com/radio/music/")
                }
            }
            McButton("não pegou? reiniciar mídia + reinjetar", !busy, Color(0xFF1565C0)) {
                run("reiniciar + reinjetar") {
                    val r = FridaUtils.restartMediaCenterAndInject()
                    if (r.contains("ativo")) hooked = true
                    r
                }
            }
            McButton("remover hook (volta ao de fábrica)", !busy, Color(0xFF455A64)) {
                run("remover hook") {
                    val r = FridaUtils.stopMediaCenterCp(); hooked = false; r
                }
            }
            Text(
                "O diagnóstico mostra pid da mídia, injetores rodando, o arquivo de controle e " +
                    "o log do hook (\"hook OK …\" / \"autoteste: getCPList() atual = …\"). " +
                    "Se não houver linha de hook, a injeção não subiu.",
                color = Color(0xFF546E7A), fontSize = 10.sp
            )
        }

        // ── Card: log ────────────────────────────────────────────────────
        McCard {
            Text("Log", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (log.isEmpty()) Text("— sem eventos —", color = Color(0xFF3A3A4E), fontSize = 11.sp)
            else log.forEach {
                Text(it, color = Color(0xFF90A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        }
    }
}

/**
 * Cards da mídia por overlay, sem Frida (overlay/MediaCardOverlay.kt) — o modo
 * padrão. Mesmas opções A-D do modo antigo, mas desenhadas por uma janela nossa
 * por cima da fileira, visível só com a mídia no topo. O Shizuku só entra para
 * conceder os appops, calibrar e alimentar a temperatura.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayCardsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by MediaCardOverlay.enabled.collectAsState()
    val status by MediaCardOverlay.status.collectAsState()
    val rect by MediaCardOverlay.rect.collectAsState()
    val calibrated by MediaCardOverlay.calibrated.collectAsState()
    val mode by MediaCardOverlay.mode.collectAsState()
    val items by MediaCardOverlay.items.collectAsState()
    val bg by MediaCardOverlay.bgColor.collectAsState()
    val temps by MediaCardOverlay.temps.collectAsState()
    val timings by MediaCardOverlay.timings.collectAsState()
    val lastBoot by MediaCardOverlay.lastBoot.collectAsState()
    val log by MediaCardOverlay.log.collectAsState()
    val forced by MediaCardOverlay.forceVisible.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var rectText by remember(rect) { mutableStateOf(rect.toString()) }
    var result by remember { mutableStateOf("") }
    var showDiag by remember { mutableStateOf(false) }
    val sel = remember(items) { mutableStateListOf(*items.toTypedArray()) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MediaCardOverlay.load() } }

    fun applyCfg(m: MediaCardOverlay.Mode, list: List<String>, what: String) {
        MediaCardOverlay.setConfig(m, list)
        if (!on) MediaCardOverlay.setEnabled(ctx, true)
        result = "aplicado: $what"
    }

    McCard {
        Text("Cards da mídia · overlay (padrão)", color = Color(0xFFFFD54F), fontSize = 12.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(
            "Uma janela nossa cobre a fileira de mídia online só enquanto a tela de mídia está " +
                "aberta. Nada é injetado no app do OEM e o boot não espera o Shizuku (ele só " +
                "concede as permissões na 1ª vez, calibra e alimenta a temperatura).",
            color = Color(0xFF90A4AE), fontSize = 10.sp
        )
        Text(
            (if (on) "● ligado" else "○ desligado (mídia de fábrica)") + " · " + status,
            color = if (on) Color(0xFF81C784) else Color(0xFF90A4AE),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )
        if (!calibrated) Text(
            "⚠ posição ainda não calibrada: toque em \"calibrar\" (abre a mídia e mede a fileira).",
            color = Color(0xFFFFB74D), fontSize = 11.sp
        )
        Text(
            "atual: " + when (mode) {
                MediaCardOverlay.Mode.HIDE -> "título e fileira escondidos"
                MediaCardOverlay.Mode.CARDS ->
                    if (items.isEmpty()) "fileira vazia"
                    else items.mapNotNull { CardCatalog.byId(it)?.label }.joinToString(", ")
            },
            color = Color(0xFFB0BEC5), fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )

        Text("Opções rápidas", color = Color(0xFF80CBC4), fontSize = 12.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        McButton("A · remover Deezer, colocar TuneIn", !busy, Color(0xFF2E7D32)) {
            applyCfg(MediaCardOverlay.Mode.CARDS, listOf("tunein"), "só TuneIn")
        }
        McButton("B · fileira vazia (some o Deezer, título fica)", !busy, Color(0xFFB71C1C)) {
            applyCfg(MediaCardOverlay.Mode.CARDS, emptyList(), "fileira vazia")
        }
        McButton("C · esconder o menu inteiro (título + fileira)", !busy, Color(0xFF6D4C41)) {
            applyCfg(MediaCardOverlay.Mode.HIDE, emptyList(), "bloco escondido")
        }
        McButton("D · temperatura no lugar da fileira", !busy, Color(0xFF0277BD)) {
            applyCfg(MediaCardOverlay.Mode.CARDS, listOf(CardCatalog.TEMP), "temperatura")
        }
        McButton(if (on) "desligar (volta a mídia de fábrica)" else "ligar", !busy,
            if (on) Color(0xFF37474F) else Color(0xFF2E7D32)) {
            MediaCardOverlay.setEnabled(ctx, !on)
        }

        Text("Montar os cards", color = Color(0xFF80CBC4), fontSize = 12.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(
            "A ordem segue a ordem de seleção. Todos abrem a PWA por npwas:// no content shell, " +
                "sem depender de provisionamento. \"Temperatura\" mostra externa/interna.",
            color = Color(0xFF90A4AE), fontSize = 10.sp
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CardCatalog.ALL.forEach { c ->
                val picked = sel.contains(c.id)
                FilterChip(
                    selected = picked,
                    onClick = { if (picked) sel.remove(c.id) else sel.add(c.id) },
                    label = { Text(c.label + "  · " + c.sub, fontSize = 11.sp) }
                )
            }
        }
        McButton("aplicar seleção", !busy, Color(0xFF6A1B9A)) {
            applyCfg(MediaCardOverlay.Mode.CARDS, sel.toList(),
                if (sel.isEmpty()) "fileira vazia" else sel.joinToString(","))
        }
        Text(
            "temperatura: externa ${formatTempLabel(temps.first)} · interna ${formatTempLabel(temps.second)}" +
                " (último valor salvo; atualiza ao vivo com o Shizuku)",
            color = Color(0xFF546E7A), fontSize = 10.sp
        )

        Text("Ajuste", color = Color(0xFF80CBC4), fontSize = 12.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McButton("calibrar (abre a mídia)", !busy && on, Color(0xFF0277BD), Modifier.weight(1f)) {
                busy = true; result = "calibrando…"
                scope.launch {
                    result = withContext(Dispatchers.IO) { MediaCardOverlay.calibrate() }
                    busy = false
                }
            }
            McButton(if (forced) "parar de forçar" else "forçar visível", on, Color(0xFF6D4C41),
                Modifier.weight(1f)) {
                MediaCardOverlay.setForceVisible(!forced)
            }
            McButton("abrir mídia", !busy, Color(0xFF00695C), Modifier.weight(1f)) {
                scope.launch { result = withContext(Dispatchers.IO) { FridaUtils.openMediaCenter() } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rectText, onValueChange = { rectText = it },
                label = { Text("fileira: x,y,largura,altura (px de tela)", fontSize = 10.sp) },
                singleLine = true, modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE0E0E0))
            )
            McButton("aplicar", true, Color(0xFF6A1B9A), Modifier.width(110.dp)) {
                val r = MediaCardOverlay.Rect.parse(rectText)
                if (r == null) result = "retângulo inválido: use x,y,w,h"
                else { MediaCardOverlay.setRect(r); result = "retângulo $r" }
            }
        }
        Text(
            String.format("fundo amostrado: #%06X", bg and 0xFFFFFF),
            color = Color(0xFF546E7A), fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        if (result.isNotEmpty()) Text(result, color = Color(0xFFB0BEC5), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)

        McButton(if (showDiag) "esconder diagnóstico" else "diagnóstico (tempos e log)", true,
            Color(0xFF37474F)) { showDiag = !showDiag }
        if (showDiag) {
            Text("Tempos (desde o boot)", color = Color(0xFF80CBC4), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (timings.isEmpty()) Text("— ligue o overlay —", color = Color(0xFF3A3A4E), fontSize = 10.sp)
            else timings.forEach {
                Text(it, color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            if (lastBoot.isNotEmpty()) {
                Text("Último boot medido", color = Color(0xFF80CBC4), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(lastBoot, color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text("Log", color = Color(0xFF80CBC4), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            log.take(25).forEach {
                Text(it, color = Color(0xFF90A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun formatTempLabel(raw: String?): String =
    raw?.toFloatOrNull()?.let { "${Math.round(it)}°" } ?: "--"

@Composable
private fun McCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        border = BorderStroke(1.dp, Color(0xFF2A2A3E)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun McButton(
    text: String,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = Color(0xFF263238)
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Text(text, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}
