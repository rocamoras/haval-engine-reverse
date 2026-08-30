package br.com.redesurftank.havalenginereverse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalenginereverse.ui.theme.HavalEngineReverseTheme
import br.com.redesurftank.havalenginereverse.utils.WindowModeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A barra da esquerda que fica ao lado do Android Auto.
 *
 * É uma activity comum, redimensionável — quem a coloca na faixa esquerda é o
 * WindowModeUtils, por shell. Ela não sabe nada sobre geometria de propósito:
 * se o WM decidir dar outro tamanho, ela só se adapta.
 */
class SidebarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalEngineReverseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    SidebarContent(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SidebarContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun bg(block: () -> Unit) = scope.launch(Dispatchers.IO) { block() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Ações",
            color = Color(0xFF4FC3F7),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        BarButton("⏮  Anterior") { bg { WindowModeUtils.sh("input keyevent 88") } }
        BarButton("⏯  Play / Pause") { bg { WindowModeUtils.sh("input keyevent 85") } }
        BarButton("⏭  Próxima") { bg { WindowModeUtils.sh("input keyevent 87") } }

        Spacer(Modifier.height(6.dp))

        BarButton("🏠  Home") { bg { WindowModeUtils.sh("input keyevent 3") } }
        BarButton("◀  Voltar") { bg { WindowModeUtils.sh("input keyevent 4") } }

        Spacer(Modifier.height(6.dp))

        BarButton("⛶  AA tela cheia", accent = Color(0xFF8D6E63)) {
            val target = AaSplitPrefs.component(context)
            bg { WindowModeUtils.restore(target) }
        }
        BarButton("✕  Fechar barra", accent = Color(0xFF6D4C41)) { onClose() }
    }
}

@Composable
private fun BarButton(
    label: String,
    accent: Color = Color(0xFF1E3A5F),
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp,
            vertical = 12.dp
        )
    ) {
        Text(label, color = Color(0xFFE0E0E0), fontSize = 13.sp, maxLines = 1)
    }
}
