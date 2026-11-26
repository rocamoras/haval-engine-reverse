package br.com.redesurftank.havalenginereverse.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.platform.ComposeView
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
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cards da fileira de mídia online desenhados por OVERLAY, sem Frida (modo padrão
 * desde a v2.34.0; spike nas v2.32/2.33, docs/mediacenter-online-cards.md §17-§19).
 *
 * O nosso processo põe uma janela TYPE_APPLICATION_OVERLAY, em Compose, exatamente
 * em cima do HorizontalScrollView da fileira, e só a deixa visível enquanto a
 * MediaCenterActivity está no topo. O layout da MediaCenter é todo ancorado no topo
 * com margem fixa, então a posição não muda: um fundo opaco, na cor amostrada da
 * própria tela, cobre o Deezer sem mexer no app do OEM.
 *
 * Nada no caminho do boot depende do Shizuku: a janela usa o appop
 * SYSTEM_ALERT_WINDOW e o topo vem do UsageStats (appop GET_USAGE_STATS), ambos
 * concedidos uma vez pelo Shizuku e persistentes. O Shizuku só entra para conceder
 * os appops, calibrar e alimentar o card de temperatura (que mostra o último valor
 * salvo até lá).
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

    const val KEY_OUTSIDE = "car.basic.outside_temp"
    const val KEY_INSIDE = "car.basic.inside_temp"

    // O nome do arquivo ficou do spike: mantém ligado/calibrado quem já usava.
    private const val PREFS = "mc_overlay_spike"
    private const val K_ENABLED = "enabled"
    private const val K_RECT = "rect"
    private const val K_TITLE_RECT = "title_rect"
    private const val K_CALIBRATED = "calibrated"
    private const val K_BG = "bg_color"
    private const val K_MODE = "mode"
    private const val K_ITEMS = "items"
    private const val K_T_OUT = "temp_out"
    private const val K_T_IN = "temp_in"
    private const val K_LAST_BOOT = "last_boot_report"

    /** CARDS = a fileira vira os nossos cards; HIDE = título e fileira somem. */
    enum class Mode { CARDS, HIDE }

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        override fun toString() = "$x,$y,$w,$h"

        fun union(o: Rect): Rect {
            val l = minOf(x, o.x); val t = minOf(y, o.y)
            val r = maxOf(x + w, o.x + o.w); val b = maxOf(y + h, o.y + o.h)
            return Rect(l, t, r - l, b - t)
        }

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
    private const val DEFAULT_BG = 0xFF0E1116.toInt()
    val DEFAULT_ITEMS = listOf("tunein")

    // ── Estado exposto para a aba e para a UI da janela ───────────────────

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow("desligado")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _rect = MutableStateFlow(DEFAULT_RECT)
    val rect: StateFlow<Rect> = _rect.asStateFlow()

    private val _titleRect = MutableStateFlow<Rect?>(null)
    val titleRect: StateFlow<Rect?> = _titleRect.asStateFlow()

    private val _calibrated = MutableStateFlow(false)
    val calibrated: StateFlow<Boolean> = _calibrated.asStateFlow()

    private val _bgColor = MutableStateFlow(DEFAULT_BG)
    val bgColor: StateFlow<Int> = _bgColor.asStateFlow()

    private val _mode = MutableStateFlow(Mode.CARDS)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _items = MutableStateFlow(DEFAULT_ITEMS)
    val items: StateFlow<List<String>> = _items.asStateFlow()

    /** (externa, interna) como chegam do carro; null = ainda sem valor. */
    private val _temps = MutableStateFlow<Pair<String?, String?>>(null to null)
    val temps: StateFlow<Pair<String?, String?>> = _temps.asStateFlow()

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

    private fun prefs() =
        App.getDeviceProtectedContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lê a configuração salva. Barato e idempotente: a aba chama ao abrir. */
    @JvmStatic
    fun load() {
        val p = prefs()
        _rect.value = Rect.parse(p.getString(K_RECT, null)) ?: DEFAULT_RECT
        _titleRect.value = Rect.parse(p.getString(K_TITLE_RECT, null))
        _calibrated.value = p.getBoolean(K_CALIBRATED, false)
        _bgColor.value = p.getInt(K_BG, DEFAULT_BG)
        _mode.value = if (p.getString(K_MODE, "") == Mode.HIDE.name) Mode.HIDE else Mode.CARDS
        _items.value = p.getString(K_ITEMS, null)?.split(",")?.map { it.trim() }
            ?.filter { CardCatalog.byId(it) != null } ?: DEFAULT_ITEMS
        _temps.value = p.getString(K_T_OUT, null) to p.getString(K_T_IN, null)
        _lastBoot.value = p.getString(K_LAST_BOOT, "") ?: ""
    }

    /**
     * Chamado no onCreate do UniversalMonitorService (que sobe no BOOT_COMPLETED e no
     * LOCKED_BOOT_COMPLETED). Só faz algo se o overlay estiver ligado.
     */
    @JvmStatic
    fun restoreOnBoot(ctx: Context) {
        load()
        if (!prefs().getBoolean(K_ENABLED, false)) return
        start(ctx)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs().edit().putBoolean(K_ENABLED, on).apply()
        if (on) start(ctx) else stop()
    }

    /** Troca o que a janela mostra. Vale na hora, sem reabrir a mídia. */
    fun setConfig(mode: Mode, items: List<String>) {
        val clean = items.filter { CardCatalog.byId(it) != null }.distinct()
        _mode.value = mode
        _items.value = clean
        prefs().edit().putString(K_MODE, mode.name).putString(K_ITEMS, clean.joinToString(",")).apply()
        log("config: ${mode.name} ${if (mode == Mode.CARDS) clean.toString() else ""}")
        applyWindowRect()
    }

    /** Valores do carro, vindos do EngineReverseStateHolder (só existem com Shizuku). */
    @JvmStatic
    fun onVehicleValue(key: String, value: String?) {
        if (value.isNullOrEmpty()) return
        val cur = _temps.value
        when (key) {
            KEY_OUTSIDE -> if (cur.first != value) {
                _temps.value = value to cur.second
                try { prefs().edit().putString(K_T_OUT, value).apply() } catch (_: Throwable) {}
            }
            KEY_INSIDE -> if (cur.second != value) {
                _temps.value = cur.first to value
                try { prefs().edit().putString(K_T_IN, value).apply() } catch (_: Throwable) {}
            }
        }
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
        log("overlay desligado")
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
            else log("Shizuku sem permissão depois de 30s; appops não concedidos")
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

    /** No modo HIDE a janela cobre título + fileira; senão só a fileira. */
    private fun windowRect(): Rect {
        val row = _rect.value
        val title = _titleRect.value
        return if (_mode.value == Mode.HIDE && title != null) row.union(title) else row
    }

    private fun addWindow() {
        val ctx = appCtx ?: return
        if (view != null || !_enabled.value) return
        try {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val r = windowRect()
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
                title = "mc-overlay-cards"
            }
            val o = OverlayOwner().also { it.create() }
            val cv = ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { OverlayContent { openCard(it) } }
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
            _status.value = "janela pronta (invisível) em $r"
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

    private fun applyWindowRect() {
        main.post {
            val v = view ?: return@post
            val p = lp ?: return@post
            val r = windowRect()
            p.x = r.x; p.y = r.y; p.width = r.w; p.height = r.h
            try { wm?.updateViewLayout(v, p) } catch (_: Throwable) {}
            _status.value = (if (visible) "cards VISÍVEIS em " else "janela pronta em ") + r
        }
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
        _status.value = if (want) "cards VISÍVEIS em ${windowRect()}" else "cards escondidos (mídia fora do topo)"
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

    // ── Calibração (uiautomator + screencap, pelo Shizuku, uma vez) ───────

    /**
     * Abre a MediaCenter, tira um dump da hierarquia (uiautomator, uid de shell) e
     * lê os bounds da fileira e do título; depois amostra a cor de fundo da tela em
     * volta da fileira (screencap cru). Bloqueante: chamar fora da main thread.
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
            val bg = sampleBackground(row)
            val p = prefs().edit()
                .putString(K_RECT, row.toString())
                .putBoolean(K_CALIBRATED, true)
            if (title != null) p.putString(K_TITLE_RECT, title.toString())
            if (bg != null) p.putInt(K_BG, bg)
            p.apply()
            _rect.value = row
            if (title != null) _titleRect.value = title
            if (bg != null) _bgColor.value = bg
            _calibrated.value = true
            applyWindowRect()
            val bgTxt = bg?.let { String.format("#%06X", it and 0xFFFFFF) } ?: "não amostrado"
            log("calibrado: fileira=$row título=${title ?: "?"} fundo=$bgTxt")
            return "calibrado: fileira $row" + (title?.let { " · título $it" } ?: "") + " · fundo $bgTxt"
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

    /**
     * Cor de fundo da tela da mídia em volta da fileira. `screencap` sem -p escreve
     * um cabeçalho (largura, altura, formato; +dataspace a partir do Android 10) e os
     * pixels RGBA crus: lemos o stdout do processo do Shizuku só até o último ponto
     * amostrado, sem arquivo (o app não lê /data/local/tmp). Fica com a cor mais
     * frequente entre os pontos.
     */
    private fun sampleBackground(row: Rect): Int? {
        val svc = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val proc = svc.newProcess(arrayOf("screencap"), null, null) ?: return null
        try {
            val input: InputStream = FileInputStream(proc.inputStream.fileDescriptor)
            val header = ByteArray(if (Build.VERSION.SDK_INT >= 29) 16 else 12)
            if (!readFully(input, header)) return null
            val w = le32(header, 0); val h = le32(header, 4); val fmt = le32(header, 8)
            if (w <= 0 || h <= 0 || fmt != 1) { log("screencap: formato $fmt ${w}x$h não suportado"); return null }
            val midY = row.y + row.h / 2
            val pts = listOf(
                row.x - 24 to midY,
                row.x + row.w + 24 to midY,
                row.x + 40 to row.y + row.h + 10,
                row.x + row.w / 2 to row.y + row.h + 10
            ).filter { (x, y) -> x in 0 until w && y in 0 until h }
                .map { (x, y) -> (y.toLong() * w + x) * 4 }
                .sorted()
            if (pts.isEmpty()) return null
            val colors = ArrayList<Int>()
            var pos = 0L
            val px = ByteArray(4)
            for (off in pts) {
                skipFully(input, off - pos)
                if (!readFully(input, px)) break
                pos = off + 4
                val r = px[0].toInt() and 0xFF; val g = px[1].toInt() and 0xFF; val b = px[2].toInt() and 0xFF
                colors.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
            return colors.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        } catch (t: Throwable) {
            log("amostra de fundo falhou: ${t.message}")
            return null
        } finally {
            try { proc.destroy() } catch (_: Throwable) {}
        }
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val r = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (r < 0) throw java.io.EOFException()
            left -= r
        }
    }

    fun setRect(r: Rect) {
        _rect.value = r
        prefs().edit().putString(K_RECT, r.toString()).putBoolean(K_CALIBRATED, true).apply()
        _calibrated.value = true
        applyWindowRect()
    }

    // ── Clique ────────────────────────────────────────────────────────────

    private fun openCard(card: CardDef) {
        val ctx = appCtx ?: return
        val url = card.url ?: return
        log("clique: ${card.label}")
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(H5_UI_PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            log("startActivity falhou (${t.message}); tentando am start pelo Shizuku")
            Thread {
                ShizukuUtils.runCommandAndGetOutput(arrayOf("am", "start", "-a",
                    "android.intent.action.VIEW", "-d", url, "-p", H5_UI_PKG))
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
        try { prefs().edit().putString(K_LAST_BOOT, report).apply() } catch (_: Throwable) {}
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
