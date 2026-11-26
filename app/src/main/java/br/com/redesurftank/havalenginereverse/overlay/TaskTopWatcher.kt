package br.com.redesurftank.havalenginereverse.overlay

import android.app.ActivityManager
import android.content.ComponentName
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import br.com.redesurftank.havalenginereverse.utils.WindowModeUtils
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Diz qual Activity está no topo, sem Frida e sem PACKAGE_USAGE_STATS.
 *
 * Três camadas, da mais rápida para a mais lenta, e cada uma registra se funcionou
 * (é isso que o spike quer medir no carro):
 *
 *  1. **Evento**: um ITaskStackListener registrado no IActivityManager pelo Shizuku
 *     (uid de shell, que tem MANAGE_ACTIVITY_STACKS). O listener é um Binder cru:
 *     qualquer transação que o system_server mandar vira "a pilha mudou, olhe de
 *     novo". Não precisamos da interface oculta, só do descritor.
 *  2. **Consulta por binder**: IActivityManager.getTasks(1) pelo Shizuku. Custa uma
 *     chamada de binder, sem criar processo.
 *  3. **Shell**: `dumpsys activity activities` (o WindowModeUtils.topActivity), que
 *     cria processo e leva centenas de ms. Só entra se a 2 falhar.
 *
 * Um polling lento (POLL_MS) roda sempre como rede de segurança; se o listener não
 * registrar, ele acelera para POLL_FAST_MS.
 */
class TaskTopWatcher(
    private val onTop: (component: ComponentName?, source: String, eventAt: Long) -> Unit,
    private val log: (String) -> Unit
) : TopWatcher {
    companion object {
        private const val TAG = "TaskTopWatcher"
        private const val LISTENER_DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 1500L
        const val POLL_FAST_MS = 300L
    }

    private val thread = HandlerThread("mc-overlay-watch").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false
    private var am: Any? = null
    private var getTasks: java.lang.reflect.Method? = null
    private var listenerBinder: IBinder? = null
    private var listenerProxy: Any? = null

    /** Como o topo está sendo lido — vai para o painel de medição. */
    @Volatile override var mode: String = "parado"
        private set
    @Volatile override var queryMode: String = "?"
        private set

    private var lastComponent: ComponentName? = null
    private var reportedOnce = false

    override fun start() {
        if (running) return
        running = true
        handler.post {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (t: Throwable) {
                log("hiddenapibypass falhou: ${t.message}")
            }
            bindActivityManager()
            val listenerOk = registerListener()
            mode = if (listenerOk) "evento (ITaskStackListener) + poll ${POLL_MS}ms"
            else "só poll ${POLL_FAST_MS}ms (listener recusado)"
            log("watcher: $mode")
            check("inicial", SystemClock.elapsedRealtime())
            schedulePoll()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        handler.post { unregisterListener() }
    }

    override fun quit() {
        stop()
        handler.post { thread.quitSafely() }
    }

    /** Força uma releitura agora (ex.: depois de calibrar). */
    override fun poke(reason: String) {
        if (!running) return
        val at = SystemClock.elapsedRealtime()
        handler.post { check(reason, at) }
    }

    // ── IActivityManager pelo Shizuku ──────────────────────────────────────

    private fun bindActivityManager() {
        try {
            val raw = SystemServiceHelper.getSystemService("activity")
                ?: throw IllegalStateException("serviço 'activity' nulo")
            val wrapped = ShizukuBinderWrapper(raw)
            val stub = Class.forName("android.app.IActivityManager\$Stub")
            am = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
            getTasks = am!!.javaClass.methods.firstOrNull {
                it.name == "getTasks" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            log("IActivityManager via Shizuku ok; getTasks(int) ${if (getTasks != null) "achado" else "AUSENTE"}")
        } catch (t: Throwable) {
            am = null
            log("IActivityManager via Shizuku falhou: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun registerListener(): Boolean {
        val am = am ?: return false
        return try {
            val listenerCls = Class.forName(LISTENER_DESCRIPTOR)
            val reg = am.javaClass.methods.firstOrNull {
                it.name == "registerTaskStackListener" && it.parameterTypes.size == 1
            } ?: throw NoSuchMethodException("registerTaskStackListener")

            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(LISTENER_DESCRIPTOR)
                        return true
                    }
                    // Todos os métodos do ITaskStackListener são oneway: não há reply.
                    val at = SystemClock.elapsedRealtime()
                    handler.removeCallbacks(eventCheck)
                    lastEventAt = at
                    lastEventCode = code
                    handler.post(eventCheck)
                    return true
                }
            }
            // Stub.asInterface de um Binder sem attachInterface devolve um Proxy em
            // volta dele: é o objeto que o system_server aceita como listener.
            val stub = Class.forName("$LISTENER_DESCRIPTOR\$Stub")
            val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            if (!listenerCls.isInstance(proxy)) throw IllegalStateException("proxy não é ITaskStackListener")
            reg.invoke(am, proxy)
            listenerBinder = binder
            listenerProxy = proxy
            log("ITaskStackListener registrado")
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            log("ITaskStackListener recusado: ${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    private fun unregisterListener() {
        val am = am ?: return
        val proxy = listenerProxy ?: return
        try {
            am.javaClass.methods.firstOrNull {
                it.name == "unregisterTaskStackListener" && it.parameterTypes.size == 1
            }?.invoke(am, proxy)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister: ${t.message}")
        }
        listenerProxy = null
        listenerBinder = null
    }

    @Volatile private var lastEventAt = 0L
    @Volatile private var lastEventCode = 0
    private val eventCheck = Runnable { check("evento#$lastEventCode", lastEventAt) }

    private fun schedulePoll() {
        if (!running) return
        val every = if (listenerBinder != null) POLL_MS else POLL_FAST_MS
        handler.postDelayed({
            if (!running) return@postDelayed
            check("poll", SystemClock.elapsedRealtime())
            schedulePoll()
        }, every)
    }

    // ── Leitura do topo ───────────────────────────────────────────────────

    private fun check(source: String, eventAt: Long) {
        if (!running) return
        val top = readTop()
        val changed = top != lastComponent
        if (changed || !reportedOnce) {
            lastComponent = top
            reportedOnce = true
            onTop(top, source, eventAt)
        }
    }

    private fun readTop(): ComponentName? {
        val m = getTasks
        if (m != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val list = m.invoke(am, 1) as? List<ActivityManager.RunningTaskInfo>
                val top = list?.firstOrNull()?.topActivity
                if (top != null) {
                    queryMode = "getTasks(1) por binder"
                    return top
                }
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                if (queryMode != "dumpsys (getTasks falhou)") {
                    log("getTasks falhou (${cause.javaClass.simpleName}: ${cause.message}); caindo para dumpsys")
                }
                getTasks = null
            }
        }
        if (!Shizuku.pingBinder()) return null
        queryMode = "dumpsys (getTasks falhou)"
        val t = WindowModeUtils.topActivity() ?: return null
        val cls = if (t.activity.startsWith(".")) t.pkg + t.activity else t.activity
        return ComponentName(t.pkg, cls)
    }
}
