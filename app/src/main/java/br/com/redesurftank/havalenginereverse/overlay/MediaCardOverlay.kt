package br.com.redesurftank.havalenginereverse.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import br.com.redesurftank.havalenginereverse.App
import br.com.redesurftank.havalenginereverse.utils.ShizukuUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SPIKE — cards da fileira de mídia online desenhados por OVERLAY, sem Frida.
 *
 * Em vez de injetar no processo com.beantechs.mediacenter e remontar as views em
 * JS (docs/HANDOFF-cards-midia-online.md), o nosso processo põe uma janela
 * TYPE_APPLICATION_OVERLAY, em Compose, exatamente em cima do HorizontalScrollView
 * da fileira, e só a deixa visível enquanto a MediaCenterActivity está no topo.
 * O layout da MediaCenter é todo ancorado no topo com margem fixa, então a posição
 * da fileira não muda: um fundo opaco cobre o Deezer sem mexer no app do OEM.
 *
 * O que o spike mede (tudo em SystemClock.elapsedRealtime, ou seja, desde o boot):
 * início do serviço, janela adicionada, primeiro frame (pré-aquecido, invisível),
 * Shizuku pronto, watcher pronto, primeira vez que a mídia aparece no topo e
 * primeira vez que os cards ficam visíveis — além da latência evento→visível de
 * cada aparição.
 *
 * A janela fica sempre adicionada; esconder = alpha 0 + FLAG_NOT_TOUCHABLE. Assim
 * mostrar é só um updateViewLayout, sem compor nem desenhar nada na hora.
 */
object MediaCardOverlay {
    private const val TAG = "MediaCardOverlay"

    const val MEDIA_PKG = "com.beantechs.mediacenter"
    const val MEDIA_ACTIVITY = "com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity"
    private const val H5_UI_PKG = "com.beantechs.mediacenter.h5.ui"
    private const val ID_ROW = "$MEDIA_PKG:id/horizontalScrollView"
    private const val ID_TITLE = "$MEDIA_PKG:id/online_music"
    private const val DUMP_PATH = "/data/local/tmp/mc_overlay_ui.xml"

    private const val PREFS = "mc_overlay_spike"
    private const val K_ENABLED = "enabled"
    private const val K_RECT = "rect"
    private const val K_LAST_BOOT = "last_boot_report"

    data class Card(val label: String, val sub: String, val accent: Long, val url: String)

    /** Os cards do spike: abrem a PWA direto no content shell, sem provisionamento. */
    val CARDS = listOf(
        Card("TuneIn", "rádio", 0xFF14D8CC, "npwas://tunein.com/radio/music/"),
        Card("Radioline", "rádio", 0xFFFF7043, "npwas://access-bt-gwm-9zcb4r.radioline.co/"),
        Card("Deezer", "música", 0xFFA238FF, "npwas://ver.netrange.com/twine4car.deezer/production/v1/index.html"),
        Card("YouTube", "vídeo", 0xFFFF0033, "npwas://www.youtube.com/?app=desktop&persist_app=1&ua=default")
    )

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        override fun toString() = "$x,$y,$w,$h"

        companion object {
            fun parse(s: String?): Rect? {
                val p = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
                if (p.size != 4 || p[2] <= 0 || p[3] <= 0) return null
                return Rect(p[0], p[1], p[2], p[3])
            }
        }
    }

    /** Placeholder até calibrar: fileira do layout-1792x1080 (margens 188/164), achatada. */
    private val DEFAULT_RECT = Rect(316, 164, 1500, 324)

    // ── Estado exposto para a aba ─────────────────────────────────────────

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow("desligado")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _rect = MutableStateFlow(DEFAULT_RECT)
    val rect: StateFlow<Rect> = _rect.asStateFlow()

    private val _timings = MutableStateFlow<List<String>>(emptyList())
    val timings: StateFlow<List<String>> = _timings.asStateFlow()

    private val _lastBoot = MutableStateFlow("")
    val lastBoot: StateFlow<String> = _lastBoot.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _forceVisible = MutableStateFlow(false)
    val forceVisible: StateFlow<Boolean> = _forceVisible.asStateFlow()

    // ── Medição ───────────────────────────────────────────────────────────

    private val marks = LinkedHashMap<String, Long>()
    private val showLatencies = ArrayList<Long>()
    private var startedAt = 0L
    private var bootReportSaved = false

    @Synchronized
    private fun mark(key: String, at: Long = SystemClock.elapsedRealtime()) {
        if (marks.containsKey(key)) return
        marks[key] = at
        log("⏱ $key = ${fmtS(at)} desde o boot")
        publishTimings()
    }

    @Synchronized
    private fun publishTimings() {
        val out = ArrayList<String>()
        marks.forEach { (k, v) ->
            val rel = if (startedAt > 0) "  (+${v - startedAt} ms do serviço)" else ""
            out.add("${k.padEnd(22)} ${fmtS(v).padStart(8)}$rel")
        }
        if (showLatencies.isNotEmpty()) {
            val last = showLatencies.takeLast(10)
            out.add("evento→visível (últimas ${last.size}): ${last.joinToString(" ")} ms")
            out.add("  mediana ${last.sorted()[last.size / 2]} ms · pior ${last.max()} ms")
        }
        watcher?.let {
            out.add("detecção: ${it.mode}")
            out.add("leitura do topo: ${it.queryMode}")
        }
        _timings.value = out
    }

    private fun fmtS(ms: Long) = String.format(Locale.US, "%.2fs", ms / 1000.0)

    fun log(s: String) {
        Log.w(TAG, s)
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _log.value = (listOf("[$stamp] $s") + _log.value).take(80)
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var owner: OverlayOwner? = null
    private var watcher: TopWatcher? = null
    private var mediaOnTop = false
    private var visible = false

    private fun prefs(ctx: Context) =
        App.getDeviceProtectedContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Chamado no onCreate do UniversalMonitorService (que sobe no BOOT_COMPLETED e no
     * LOCKED_BOOT_COMPLETED). Só faz algo se o spike estiver ligado.
     */
    @JvmStatic
    fun restoreOnBoot(ctx: Context) {
        val p = prefs(ctx)
        _rect.value = Rect.parse(p.getString(K_RECT, null)) ?: DEFAULT_RECT
        _lastBoot.value = p.getString(K_LAST_BOOT, "") ?: ""
        if (!p.getBoolean(K_ENABLED, false)) return
        start(ctx)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply()
        if (on) start(ctx) else stop()
    }

    @Synchronized
    private fun start(ctx: Context) {
        if (_enabled.value) return
        appCtx = ctx.applicationContext
        _enabled.value = true
        startedAt = SystemClock.elapsedRealtime()
        marks.clear(); showLatencies.clear(); bootReportSaved = false
        mark("serviço/start", startedAt)
        _status.value = "iniciando"

        // A permissão de overlay é um appop persistente: depois de concedida uma vez
        // (pelo Shizuku), no boot seguinte a janela sobe sem esperar o Shizuku.
        if (Settings.canDrawOverlays(appCtx)) {
            main.post { addWindow() }
        } else {
            log("sem permissão de overlay ainda; concedo quando o Shizuku chegar")
        }
        // Mesmo raciocínio para saber quem está no topo: com o appop GET_USAGE_STATS
        // já concedido, a detecção não espera o Shizuku (era o gargalo da v2.32.0).
        if (UsageTopWatcher.allowed(appCtx!!)) startUsageWatcher()
        else log("sem GET_USAGE_STATS ainda; concedo quando o Shizuku chegar")
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    @Synchronized
    private fun startUsageWatcher() {
        if (watcher is UsageTopWatcher) return
        val ctx = appCtx ?: return
        watcher?.quit()
        watcher = UsageTopWatcher(ctx, ::onTopChanged, ::log) { mark("usagestats respondendo") }
            .also { it.start() }
        mark("watcher pronto")
    }

    @Synchronized
    fun stop() {
        _enabled.value = false
        Shizuku.removeBinderReceivedListener(binderListener)
        watcher?.quit(); watcher = null
        main.post { removeWindow() }
        _status.value = "desligado"
        log("spike desligado")
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Thread { onShizukuReady(0) }.start()
    }

    private fun onShizukuReady(attempt: Int) {
        if (!_enabled.value) return
        val granted = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) { false }
        if (!granted) {
            // Quem pede a permissão é o UniversalMonitorService; aqui só esperamos.
            if (attempt < 30) main.postDelayed({ Thread { onShizukuReady(attempt + 1) }.start() }, 1000)
            else log("Shizuku sem permissão depois de 30s; watcher não sobe")
            return
        }
        mark("shizuku pronto")
        val ctx = appCtx ?: return
        if (!Settings.canDrawOverlays(ctx)) {
            ShizukuUtils.runCommandAndGetOutput(
                arrayOf("appops", "set", ctx.packageName, "SYSTEM_ALERT_WINDOW", "allow"))
            log("appop SYSTEM_ALERT_WINDOW concedido: ${Settings.canDrawOverlays(ctx)}")
            main.post { addWindow() }
        }
        if (!UsageTopWatcher.allowed(ctx)) {
            ShizukuUtils.runCommandAndGetOutput(
                arrayOf("appops", "set", ctx.packageName, "GET_USAGE_STATS", "allow"))
            log("appop GET_USAGE_STATS concedido: ${UsageTopWatcher.allowed(ctx)}")
        }
        synchronized(this) {
            when {
                // Caminho normal: detecção sem Shizuku, já rodando ou liberada agora.
                watcher is UsageTopWatcher -> Unit
                UsageTopWatcher.allowed(ctx) -> startUsageWatcher()
                // Plano B: appop recusado. Um binder novo do Shizuku invalida o
                // IActivityManager e o listener anteriores, então recria.
                else -> {
                    watcher?.quit()
                    watcher = TaskTopWatcher(::onTopChanged, ::log).also { it.start() }
                    mark("watcher pronto")
                }
            }
        }
    }

    // ── Topo mudou ────────────────────────────────────────────────────────

    private fun onTopChanged(top: ComponentName?, source: String, eventAt: Long) {
        val isMedia = top?.packageName == MEDIA_PKG && top.className == MEDIA_ACTIVITY
        log("topo: ${top?.flattenToShortString() ?: "?"} ($source)")
        if (isMedia) mark("mídia no topo (1ª vez)")
        mediaOnTop = isMedia
        publishTimings()
        main.post { applyVisibility(eventAt) }
    }

    fun setForceVisible(on: Boolean) {
        _forceVisible.value = on
        main.post { applyVisibility(SystemClock.elapsedRealtime()) }
    }

    // ── Janela ────────────────────────────────────────────────────────────

    private fun addWindow() {
        val ctx = appCtx ?: return
        if (view != null || !_enabled.value) return
        try {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val r = _rect.value
            val params = WindowManager.LayoutParams(
                r.w, r.h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = r.x; y = r.y
                alpha = 0f
                title = "mc-overlay-spike"
            }
            val o = OverlayOwner().also { it.create() }
            val cv = ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { OverlayCards(CARDS) { openCard(it) } }
            }
            // Primeiro frame do pré-aquecimento (ainda invisível): mede quanto custa
            // compor e desenhar a UI, separado da latência de mostrar.
            val firstDraw = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    mark("1º frame (invisível)")
                    main.post { cv.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
            cv.viewTreeObserver.addOnDrawListener(firstDraw)
            wm!!.addView(cv, params)
            o.resume()
            view = cv; lp = params; owner = o; visible = false
            mark("janela adicionada")
            _status.value = "janela pronta (invisível) em ${_rect.value}"
            applyVisibility(SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            log("addView falhou: ${t.javaClass.simpleName}: ${t.message}")
            _status.value = "erro ao adicionar janela: ${t.message}"
        }
    }

    private fun removeWindow() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Throwable) {}
        owner?.destroy()
        view = null; lp = null; owner = null; visible = false
    }

    private fun applyVisibility(eventAt: Long) {
        val v = view ?: return
        val p = lp ?: return
        val want = _enabled.value && (mediaOnTop || _forceVisible.value)
        if (want == visible) return
        visible = want
        p.alpha = if (want) 1f else 0f
        p.flags = if (want) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            log("updateViewLayout falhou: ${t.message}")
            return
        }
        _status.value = if (want) "cards VISÍVEIS em ${_rect.value}" else "cards escondidos (mídia fora do topo)"
        if (!want) return
        // O alpha da janela é propriedade da surface: vale na próxima transação do
        // WM. O próximo vsync é a melhor aproximação de "apareceu" que o app tem.
        Choreographer.getInstance().postFrameCallback {
            val now = SystemClock.elapsedRealtime()
            if (!_forceVisible.value || mediaOnTop) {
                synchronized(this) { showLatencies.add(now - eventAt) }
            }
            if (mediaOnTop) {
                mark("cards visíveis (1ª vez)", now)
                saveBootReport()
            }
            publishTimings()
        }
    }

    // ── Calibração por uiautomator (sem Frida) ────────────────────────────

    /**
     * Abre a MediaCenter, tira um dump da hierarquia (uiautomator, uid de shell) e
     * lê os bounds do HorizontalScrollView da fileira. Bloqueante: chamar fora da
     * main thread.
     */
    fun calibrate(): String {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível"
        val wasForced = _forceVisible.value
        _forceVisible.value = false
        main.post { applyVisibility(SystemClock.elapsedRealtime()) }
        try {
            ShizukuUtils.runCommandAndGetOutput(arrayOf("sh", "-c",
                "am start -n $MEDIA_PKG/$MEDIA_ACTIVITY >/dev/null 2>&1"))
            Thread.sleep(2500)
            var xml = ""
            var last = ""
            for (i in 1..3) {
                last = ShizukuUtils.runCommandAndGetOutput(arrayOf("sh", "-c",
                    "uiautomator dump $DUMP_PATH 2>&1; cat $DUMP_PATH 2>/dev/null; rm -f $DUMP_PATH"))
                val at = last.indexOf("<?xml")
                if (at >= 0) { xml = last.substring(at); break }
                Thread.sleep(1200)
            }
            if (xml.isEmpty()) return "uiautomator não gerou o dump: ${last.take(200)}"
            val row = boundsOf(xml, ID_ROW)
                ?: return "fileira ($ID_ROW) não achada no dump — a mídia estava no topo?"
            val title = boundsOf(xml, ID_TITLE)
            setRect(row)
            log("calibrado: fileira=$row título=${title ?: "?"}")
            return "calibrado: fileira $row" + (title?.let { " · título $it" } ?: "")
        } catch (t: Throwable) {
            return "calibração falhou: ${t.message}"
        } finally {
            if (wasForced) setForceVisible(true)
            watcher?.poke("pós-calibração")
        }
    }

    private fun boundsOf(xml: String, resId: String): Rect? {
        val node = Regex("<node [^>]*resource-id=\"" + Regex.escape(resId) + "\"[^>]*>").find(xml)
            ?: return null
        val b = Regex("""bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""").find(node.value) ?: return null
        val (l, t, r, bt) = b.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt() - l.toInt(), bt.toInt() - t.toInt())
    }

    fun setRect(r: Rect) {
        _rect.value = r
        prefs(App.getContext()).edit().putString(K_RECT, r.toString()).apply()
        main.post {
            val v = view ?: return@post
            val p = lp ?: return@post
            p.x = r.x; p.y = r.y; p.width = r.w; p.height = r.h
            try { wm?.updateViewLayout(v, p) } catch (_: Throwable) {}
            _status.value = (if (visible) "cards VISÍVEIS em " else "janela pronta em ") + r
        }
    }

    // ── Clique ────────────────────────────────────────────────────────────

    private fun openCard(card: Card) {
        val ctx = appCtx ?: return
        log("clique: ${card.label}")
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(card.url))
                .setPackage(H5_UI_PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            log("startActivity falhou (${t.message}); tentando am start pelo Shizuku")
            Thread {
                ShizukuUtils.runCommandAndGetOutput(arrayOf("am", "start", "-a",
                    "android.intent.action.VIEW", "-d", card.url, "-p", H5_UI_PKG))
            }.start()
        }
    }

    // ── Relatório do boot ─────────────────────────────────────────────────

    private fun saveBootReport() {
        if (bootReportSaved) return
        bootReportSaved = true
        val bootId = try { File("/proc/sys/kernel/random/boot_id").readText().trim().take(8) }
        catch (_: Throwable) { "?" }
        val sb = StringBuilder()
        sb.append("boot ").append(bootId).append(" · ")
            .append(SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())).append('\n')
        synchronized(this) {
            marks.forEach { (k, v) -> sb.append("  ").append(k).append(" = ").append(fmtS(v)).append('\n') }
            if (showLatencies.isNotEmpty()) sb.append("  evento→visível = ").append(showLatencies.last()).append(" ms\n")
        }
        watcher?.let { sb.append("  ").append(it.mode).append(" · ").append(it.queryMode) }
        val report = sb.toString().trimEnd()
        _lastBoot.value = report
        try { prefs(App.getContext()).edit().putString(K_LAST_BOOT, report).apply() } catch (_: Throwable) {}
    }
}

/** LifecycleOwner + SavedStateRegistryOwner mínimos para um ComposeView fora de Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        savedState.performRestore(null as Bundle?)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

@Composable
private fun OverlayCards(cards: List<MediaCardOverlay.Card>, onClick: (MediaCardOverlay.Card) -> Unit) {
    // Fundo opaco: cobre o ícone do OEM que está embaixo, inclusive nos vãos.
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1116))) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            cards.forEach { c ->
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onClick(c) },
                    color = Color(0xFF1A1F29),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(2.dp, Color(c.accent).copy(alpha = 0.55f))
                ) {
                    Column(Modifier.fillMaxSize().padding(22.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(8.dp)
                                .background(Color(c.accent), RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.weight(1f))
                        Text(c.label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(c.sub, color = Color(0xFF9AA4B2), fontSize = 20.sp)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.align(Alignment.End)) {
                            Text("abrir ›", color = Color(c.accent), fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}
