package br.com.redesurftank.havalenginereverse.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/** O que o overlay precisa de quem diz qual Activity está no topo. */
interface TopWatcher {
    val mode: String
    val queryMode: String
    fun start()
    fun poke(reason: String)
    fun quit()
}

/**
 * Topo lido pelo UsageStatsManager, SEM Shizuku.
 *
 * Era o gargalo do spike v2.32.0: o ITaskStackListener e o getTasks passam pelo
 * Shizuku, e o Shizuku desta central só existe depois que o climate-control o sobe
 * por telnet. Os cards ficavam prontos cedo, mas só podiam aparecer depois disso,
 * no mesmo tempo do Frida.
 *
 * O ActivityManager grava um MOVE_TO_FOREGROUND no UsageStats no instante do resume,
 * e o app lê esses eventos com o appop GET_USAGE_STATS. O appop é concedido uma vez
 * pelo Shizuku e persiste, então do boot seguinte em diante nada aqui depende dele.
 *
 * Bônus para a medição: o evento traz o horário real do resume, então
 * evento→visível passa a ser medido a partir da troca de tela de verdade.
 * Antes do desbloqueio do usuário o UsageStats não responde; o poll só segue.
 */
class UsageTopWatcher(
    private val ctx: Context,
    private val onTop: (component: ComponentName?, source: String, eventAt: Long) -> Unit,
    private val log: (String) -> Unit,
    private val onFirstRead: () -> Unit
) : TopWatcher {
    companion object {
        const val POLL_MS = 250L
        /** Releitura de uma janela já lida: um evento gravado logo depois da consulta anterior não escapa. */
        private const val OVERLAP_MS = 1000L

        fun allowed(ctx: Context): Boolean = try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
    }

    private val thread = HandlerThread("mc-overlay-usage").apply { start() }
    private val handler = Handler(thread.looper)
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Volatile private var running = false
    private var since = 0L
    private var current: ComponentName? = null
    private var firstReadDone = false
    private var emptyLogged = false

    override val mode = "UsageStats (sem Shizuku), poll ${POLL_MS}ms"
    @Volatile override var queryMode: String = "aguardando 1ª leitura"
        private set

    override fun start() {
        if (running) return
        running = true
        // Primeira leitura cobre desde o boot: pega a tela que já está aberta.
        since = System.currentTimeMillis() - SystemClock.elapsedRealtime() - OVERLAP_MS
        handler.post(tick)
    }

    override fun poke(reason: String) {
        if (!running) return
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun quit() {
        running = false
        handler.removeCallbacksAndMessages(null)
        handler.post { thread.quitSafely() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                read()
            } catch (t: Throwable) {
                queryMode = "erro: ${t.javaClass.simpleName}"
                if (!emptyLogged) { log("UsageStats falhou: ${t.message}"); emptyLogged = true }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun read() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(since, now)
        if (events == null) {
            queryMode = "sem resposta (usuário ainda bloqueado?)"
            return
        }
        var latest: ComponentName? = null
        var latestAt = 0L
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            @Suppress("DEPRECATION")
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp >= latestAt) {
                val cls = ev.className ?: continue
                latest = ComponentName(ev.packageName, cls)
                latestAt = ev.timeStamp
            }
        }
        since = now - OVERLAP_MS
        if (!firstReadDone) {
            firstReadDone = true
            queryMode = "queryEvents ok"
            log("UsageStats respondendo" + (latest?.let { "; topo desde o boot: ${it.flattenToShortString()}" } ?: "; nenhum evento ainda"))
            onFirstRead()
        }
        if (latest != null && latest != current) {
            current = latest
            // timeStamp é relógio de parede: converte para elapsedRealtime.
            val eventAt = SystemClock.elapsedRealtime() - (now - latestAt)
            onTop(latest, "usage", eventAt)
        }
    }
}
