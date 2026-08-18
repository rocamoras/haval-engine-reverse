package br.com.redesurftank.havalenginereverse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.utils.FridaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aba "Mídia" — escolhe quais ícones de mídia online aparecem no card da tela
 * principal do app de mídia OEM (com.beantechs.mediacenter).
 *
 * De fábrica a lista é fixa por país (persist.bean.country.code): no Brasil (17)
 * é só [551 Deezer]. Aqui o Frida hooka OnlineOsModelImpl.getMCpList() e devolve
 * a lista que você montar; a Activity é repintada sem reiniciar o app.
 * Detalhes: docs/mediacenter-online-cards.md + res/raw/com_beantechs_mediacenter_cp.js
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
            "Escolhe quais ícones aparecem na fileira de mídia online da tela principal " +
                "do app de mídia. De fábrica o Brasil (country code 17) recebe só o Deezer. " +
                "Requer o APK fat (Frida) + Shizuku ativo.",
            color = Color(0xFF90A4AE), fontSize = 11.sp
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
