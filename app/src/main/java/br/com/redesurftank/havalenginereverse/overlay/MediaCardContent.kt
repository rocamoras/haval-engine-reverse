package br.com.redesurftank.havalenginereverse.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Um card da fileira. `url == null` é o card de temperatura; os outros abrem uma
 * PWA por npwas:// no content shell do h5.ui, que não exige provisionamento
 * (docs/mediacenter-online-cards.md §9). URLs do catálogo embutido (§6).
 */
data class CardDef(
    val id: String,
    val label: String,
    val sub: String,
    val accent: Long,
    val url: String?
)

object CardCatalog {
    const val TEMP = "temp"

    val ALL = listOf(
        CardDef("tunein", "TuneIn", "rádio", 0xFF14D8CC, "npwas://tunein.com/radio/music/"),
        CardDef("radioline", "Radioline", "rádio", 0xFFFF7043, "npwas://access-bt-gwm-9zcb4r.radioline.co/"),
        CardDef("deezer", "Deezer", "música", 0xFFA238FF,
            "npwas://ver.netrange.com/twine4car.deezer/production/v1/index.html"),
        CardDef("youtube", "YouTube", "vídeo", 0xFFFF0033,
            "npwas://www.youtube.com/?app=desktop&persist_app=1&ua=default"),
        CardDef("amazon", "Amazon Music", "música", 0xFF25D1DA, "npwas://music.amazon.com/?ua=mobile"),
        CardDef("reuters", "Reuters TV", "notícias", 0xFFFF8000, "npwas://www.reuters.com/video/?ua=mobile"),
        CardDef("cnn", "CNN", "notícias", 0xFFCC0000, "npwas://edition.cnn.com/videos"),
        CardDef("pluto", "Pluto TV", "vídeo", 0xFFFFF200, "npwas://pluto.tv/"),
        CardDef("dazn", "DAZN", "esporte", 0xFFF7FF1A, "npwas://dazn.com"),
        CardDef(TEMP, "Temperatura", "externa / interna", 0xFF4FC3F7, null)
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

/** Conteúdo da janela de overlay. Lê o estado direto do MediaCardOverlay. */
@Composable
internal fun OverlayContent(onClick: (CardDef) -> Unit) {
    val mode by MediaCardOverlay.mode.collectAsState()
    val items by MediaCardOverlay.items.collectAsState()
    val bg by MediaCardOverlay.bgColor.collectAsState()
    val temps by MediaCardOverlay.temps.collectAsState()

    // Fundo opaco na cor amostrada da tela da mídia: cobre o ícone do OEM, inclusive
    // nos vãos, e no modo "esconder" some com o bloco inteiro.
    val cards = if (mode == MediaCardOverlay.Mode.CARDS) items.mapNotNull { CardCatalog.byId(it) }
    else emptyList()
    Box(Modifier.fillMaxSize().background(Color(bg))) {
        if (cards.isNotEmpty()) Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            cards.forEach { c ->
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .then(if (c.url != null) Modifier.clickable { onClick(c) } else Modifier),
                    color = Color(0xFF1A1F29),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(2.dp, Color(c.accent).copy(alpha = 0.55f))
                ) {
                    if (c.url == null) TempCard(c, temps.first, temps.second) else LinkCard(c)
                }
            }
        }
    }
}

@Composable
private fun LinkCard(c: CardDef) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        AccentBar(c)
        Spacer(Modifier.weight(1f))
        Text(c.label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(c.sub, color = Color(0xFF9AA4B2), fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.align(Alignment.End)) {
            Text("abrir ›", color = Color(c.accent), fontSize = 20.sp)
        }
    }
}

@Composable
private fun TempCard(c: CardDef, outside: String?, inside: String?) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        AccentBar(c)
        Spacer(Modifier.weight(1f))
        TempLine("Externa", outside)
        Spacer(Modifier.height(6.dp))
        TempLine("Interna", inside)
    }
}

@Composable
private fun TempLine(label: String, raw: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, color = Color(0xFF9AA4B2), fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text(formatTemp(raw), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AccentBar(c: CardDef) {
    Box(
        Modifier.fillMaxWidth().height(8.dp)
            .background(Color(c.accent), RoundedCornerShape(4.dp))
    )
}

internal fun formatTemp(raw: String?): String {
    val v = raw?.toFloatOrNull() ?: return "--°"
    if (v < -50f || v > 90f) return "--°"
    return "${Math.round(v)}°"
}
