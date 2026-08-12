package br.com.redesurftank.havalenginereverse

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
 * Aba "Clima" — ENVIA a temperatura pra central, se passando pelo app de clima OEM.
 *
 * Não lê o serviço: injeta na launcher (com.beantechs.launcher) via Frida e chama
 * HiBoardView.parseWeather com um CommonNowWeather fabricado. O hook ainda
 * sobrescreve os callbacks reais (valor "grudento").
 * Detalhes: docs/weatherservice-reverse.md + res/raw/com_beantechs_launcher_weather.js
 */
@Composable
fun WeatherAidlTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tmp     by remember { mutableStateOf("24") }
    var condTxt by remember { mutableStateOf("Ensolarado") }
    var condCode by remember { mutableStateOf("100") }
    var tmin    by remember { mutableStateOf("") }
    var tmax    by remember { mutableStateOf("") }

    var hooked  by remember { mutableStateOf(false) }
    var busy    by remember { mutableStateOf(false) }
    val log     = remember { mutableStateListOf<String>() }

    fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun addLog(s: String) {
        log.add(0, "[${stamp()}] $s")
        if (log.size > 60) log.removeAt(log.size - 1)
    }

    // Envolve chamadas Shizuku/Frida (bloqueantes) fora da main thread.
    fun run(label: String, block: () -> String) {
        if (busy) return
        busy = true; addLog("$label…")
        scope.launch {
            val res = withContext(Dispatchers.IO) { block() }
            addLog(res)
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Enviar temperatura pra central", color = Color(0xFF4FC3F7),
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Injeta na launcher OEM e pinta o card de clima da home com o seu valor — " +
                "como se fosse o app de clima. Requer o APK fat (Frida) + Shizuku ativo.",
            color = Color(0xFF90A4AE), fontSize = 11.sp
        )

        // ── Card: valores a enviar ───────────────────────────────────────
        WxCard {
            Text("Valores", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            WxField("Temperatura °C", tmp, KeyboardType.Number) { tmp = it.filter { c -> c.isDigit() || c == '-' } }
            WxField("Condição (texto)", condTxt, KeyboardType.Text) { condTxt = it }
            WxField("condCode (ícone, ex. 100=sol, 101=nublado)", condCode, KeyboardType.Number) {
                condCode = it.filter { c -> c.isDigit() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    WxField("mín (opcional)", tmin, KeyboardType.Number) { tmin = it.filter { c -> c.isDigit() || c == '-' } }
                }
                Box(Modifier.weight(1f)) {
                    WxField("máx (opcional)", tmax, KeyboardType.Number) { tmax = it.filter { c -> c.isDigit() || c == '-' } }
                }
            }
        }

        // ── Card: ações ──────────────────────────────────────────────────
        WxCard {
            Text(
                if (hooked) "● hook ativo" else "○ hook inativo",
                color = if (hooked) Color(0xFF81C784) else Color(0xFF90A4AE),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WxButton("1 · ativar hook", enabled = !busy, Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)) {
                    run("ativar hook na launcher") {
                        val r = FridaUtils.injectLauncherWeather()
                        if (r.contains("ativo")) hooked = true
                        r
                    }
                }
                WxButton("parar", enabled = !busy, Color(0xFFB71C1C),
                    modifier = Modifier.weight(1f)) {
                    run("parar injeção") {
                        val r = FridaUtils.stopLauncherWeather(); hooked = false; r
                    }
                }
            }
            WxButton("2 · enviar temperatura", enabled = !busy, Color(0xFF6A1B9A)) {
                val mn = tmin.ifBlank { tmp }
                val mx = tmax.ifBlank { tmp }
                run("enviar $tmp°") { FridaUtils.writeWeather(tmp, condCode, condTxt, mn, mx) }
            }
            Text(
                "Fluxo: ative o hook uma vez (1), depois envie (2) quantas vezes quiser — " +
                    "o valor atualiza sem reinjetar.",
                color = Color(0xFF546E7A), fontSize = 10.sp
            )
        }

        // ── Card: log ────────────────────────────────────────────────────
        WxCard {
            Text("Log", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (log.isEmpty()) Text("— sem eventos —", color = Color(0xFF3A3A4E), fontSize = 11.sp)
            else log.forEach {
                Text(it, color = Color(0xFF90A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // ── Card: instalar/atualizar pela central (telnet root) ──────────
        WxCard {
            Text("Instalar pela central (telnet)", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(
                "Baixa o APK da release/latest e instala via telnet root (127.0.0.1:23) — " +
                    "sem cabo, sem tap. O app reinicia ao concluir.",
                color = Color(0xFF90A4AE), fontSize = 10.sp
            )
            var updBusy by remember { mutableStateOf(false) }
            var updMsg by remember { mutableStateOf("") }
            WxButton(if (updBusy) "instalando…" else "baixar + instalar via telnet",
                enabled = !updBusy, Color(0xFF37474F)) {
                if (!updBusy) {
                    updBusy = true; updMsg = "iniciando…"
                    scope.launch { installLatestViaTelnet(context) { m -> updMsg = m }; updBusy = false }
                }
            }
            if (updMsg.isNotBlank()) Text(updMsg, color = Color(0xFF4FC3F7), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun WxField(label: String, value: String, kb: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, fontSize = 10.sp) },
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFE0E0E0)
        ),
        keyboardOptions = KeyboardOptions(keyboardType = kb, imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF4FC3F7),
            unfocusedBorderColor = Color(0xFF2A2A3E),
            focusedLabelColor = Color(0xFF4FC3F7),
            unfocusedLabelColor = Color(0xFF546E7A)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun WxCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun WxButton(
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
