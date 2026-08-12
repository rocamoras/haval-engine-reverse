package br.com.redesurftank.havalenginereverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.weather.OemWeatherClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aba "Clima" — testa ao vivo o bind no serviço de clima OEM
 * (com.beantechs.weatherservice) e mostra a temperatura externa real
 * que a tela da home OEM consome via AIDL.
 *
 * Reversão documentada em docs/weatherservice-reverse.md.
 */
@Composable
fun WeatherAidlTab() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    var connected by remember { mutableStateOf(false) }
    var tempC     by remember { mutableStateOf<String?>(null) }
    var condCode  by remember { mutableStateOf<String?>(null) }
    var condTxt   by remember { mutableStateOf<String?>(null) }
    var rawJson   by remember { mutableStateOf<String?>(null) }
    var cityCode  by remember { mutableStateOf("") }
    var showJson  by remember { mutableStateOf(false) }
    val log       = remember { mutableStateListOf<String>() }

    fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun addLog(s: String) {
        log.add(0, "[${stamp()}] $s")
        if (log.size > 100) log.removeAt(log.size - 1)
    }

    // Client vive enquanto a aba está composta; desconecta ao sair.
    val scope = rememberCoroutineScope()
    var updBusy by remember { mutableStateOf(false) }
    var updMsg  by remember { mutableStateOf("") }

    val client = remember {
        OemWeatherClient(context).apply {
            onStatus = { isConn, msg -> main.post { connected = isConn; addLog(msg) } }
            onNow = { t, c, txt, json ->
                main.post {
                    tempC = t; condCode = c; condTxt = txt; rawJson = json
                    addLog("onNowWeather: tmp=${t ?: "—"}°C  cond=${txt ?: "—"} ($c)")
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { client.disconnect() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Clima OEM via AIDL",
            color = Color(0xFF4FC3F7), fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        Text(
            "Faz bind em com.beantechs.weatherservice e lê a mesma temperatura que a " +
                "tela da home consome. Sem probe, sem overlay — dado real e oficial.",
            color = Color(0xFF90A4AE), fontSize = 11.sp
        )

        // ── Card: instalar/atualizar pela central (telnet root) ──────────
        WxCard {
            Text("Instalar pela central (telnet)", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(
                "Baixa o APK da release/latest e instala via telnet root (127.0.0.1:23) — " +
                    "sem cabo, sem tap do instalador. O app reinicia ao concluir.",
                color = Color(0xFF90A4AE), fontSize = 10.sp
            )
            WxButton(
                if (updBusy) "instalando…" else "baixar + instalar via telnet",
                enabled = !updBusy, Color(0xFF6A1B9A)
            ) {
                if (!updBusy) {
                    updBusy = true; updMsg = "iniciando…"
                    scope.launch {
                        installLatestViaTelnet(context) { m -> updMsg = m }
                        updBusy = false
                    }
                }
            }
            if (updMsg.isNotBlank()) {
                Text(updMsg, color = Color(0xFF4FC3F7), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }

        // ── Card: temperatura ao vivo ────────────────────────────────────
        WxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            if (connected) Color(0xFF66BB6A) else Color(0xFFEF5350),
                            RoundedCornerShape(50)
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (connected) "conectado" else "desconectado",
                    color = if (connected) Color(0xFF81C784) else Color(0xFFEF9A9A),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    tempC ?: "—",
                    color = Color(0xFFE0F7FA), fontSize = 56.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    " °C",
                    color = Color(0xFF80CBC4), fontSize = 22.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Text(
                buildString {
                    append(condTxt ?: "condição —")
                    if (condCode != null) append("  (code $condCode)")
                },
                color = Color(0xFFB0BEC5), fontSize = 12.sp
            )
            Text(
                "campo: data.now.tmp", color = Color(0xFF546E7A), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // ── Card: controles ──────────────────────────────────────────────
        WxCard {
            Text("Conexão", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WxButton("Conectar", enabled = !connected, Color(0xFF2E7D32)) {
                    addLog("connect()…"); client.connect()
                }
                WxButton("Desconectar", enabled = connected, Color(0xFFB71C1C)) {
                    client.disconnect()
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A3E), thickness = 0.5.dp)
            Text("Atualizar", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = cityCode,
                onValueChange = { cityCode = it },
                singleLine = true,
                label = { Text("cityCode (vazio = localização atual)", fontSize = 10.sp) },
                placeholder = { Text("ex.: CN101020100", fontSize = 11.sp) },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFE0E0E0)
                ),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4FC3F7),
                    unfocusedBorderColor = Color(0xFF2A2A3E),
                    focusedLabelColor = Color(0xFF4FC3F7),
                    unfocusedLabelColor = Color(0xFF546E7A)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WxButton("syncWeather / syncNowByLoc", enabled = connected, Color(0xFF1565C0)) {
                    addLog(if (cityCode.isBlank()) "syncWeather(packId)"
                           else "syncNowWeatherByLoc(packId, \"$cityCode\")")
                    client.refresh(cityCode.trim())
                }
            }
        }

        // ── Card: JSON cru ───────────────────────────────────────────────
        if (rawJson != null) {
            WxCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("JSON recebido", color = Color(0xFF80CBC4), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WxButton(if (showJson) "ocultar" else "ver", true, Color(0xFF37474F)) {
                            showJson = !showJson
                        }
                        WxButton("copiar", true, Color(0xFF37474F)) {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("weather-json", rawJson))
                            addLog("JSON copiado")
                        }
                    }
                }
                if (showJson) {
                    Text(
                        rawJson ?: "", color = Color(0xFFB0BEC5), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ── Card: log de eventos ─────────────────────────────────────────
        WxCard {
            Text("Log", color = Color(0xFF80CBC4), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (log.isEmpty()) {
                Text("— sem eventos —", color = Color(0xFF3A3A4E), fontSize = 11.sp)
            } else {
                log.forEach {
                    Text(it, color = Color(0xFF90A4AE), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
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
private fun WxButton(text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = Color(0xFF263238)
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}
