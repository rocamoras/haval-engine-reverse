package br.com.redesurftank.havalenginereverse.utils

import rikka.shizuku.Shizuku

/**
 * Força o receiver de Android Auto a sair do fullscreen para caber ao lado de uma
 * barra de ações nossa.
 *
 * O AA na central é um receiver de projeção: ele desenha numa Surface do próprio
 * processo, então NÃO dá pra embutir a projeção dentro de uma View nossa. O que dá
 * é convencer o WindowManager a redimensionar a task dele — é isso que este
 * arquivo faz, tudo por shell do Shizuku.
 *
 * Carro de referência: msmnile, Android 9 (API 28). Os windowing modes abaixo são
 * os de WindowConfiguration nessa versão.
 *
 * Duas estratégias, porque nenhuma é garantida:
 *  - FREEFORM: a task do AA vira janela livre e aceita bounds arbitrários. Mais
 *    controle sobre a geometria, mais chance do AA renegociar o stream e piscar.
 *  - SPLIT: a barra vira split-primary (docked), o AA vai pra split-secondary.
 *    Mais "nativo", mas a geometria é a que o WM quiser.
 *
 * `am task resizeable <id> 2` é o truque central: marca a task como redimensionável
 * em runtime, sem depender do resizeableActivity do manifesto do AA e sem reboot.
 * As flags globais (force_resizable_activities / enable_freeform_support) são o
 * plano B — no Android 9 elas só são lidas no retrieveSettings() do system_server,
 * ou seja, exigem reboot pra valer.
 */
object WindowModeUtils {

    // WindowConfiguration — Android 9 (API 28)
    const val MODE_FULLSCREEN = 1
    const val MODE_SPLIT_PRIMARY = 3
    const val MODE_SPLIT_SECONDARY = 4
    const val MODE_FREEFORM = 5

    const val SIDEBAR_PKG = "br.com.redesurftank.havalenginereverse"
    const val SIDEBAR_COMPONENT = "$SIDEBAR_PKG/.SidebarActivity"

    /** Palavras que costumam aparecer no pacote de um receiver de projeção. */
    private val PROJECTION_HINTS = listOf(
        "auto", "link", "project", "gearhead", "carlife", "carplay",
        "mirror", "cast", "hicar", "easyconn", "zlink", "phone"
    )

    data class TopActivity(val pkg: String, val activity: String, val taskId: Int) {
        val component get() = "$pkg/$activity"
    }

    data class ScreenSize(val width: Int, val height: Int)

    fun shizukuReady(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    /**
     * Comandos de janela falham em silêncio o tempo todo ("Error: task not found",
     * "Bad windowing mode") e o erro sai em stderr — que o ShizukuUtils não lê.
     * Daí o 2>&1.
     */
    fun sh(cmd: String): String {
        if (!shizukuReady()) return "Shizuku indisponível"
        return ShizukuUtils.runCommandAndGetOutput(arrayOf("sh", "-c", cmd + " 2>&1"))
    }

    // ── Diagnóstico ─────────────────────────────────────────────────────────

    fun screenSize(): ScreenSize? {
        val out = sh("wm size")
        // "Physical size: 1920x720" + opcionalmente "Override size: 1600x600".
        // Override manda, quando existe.
        val lines = out.lines().filter { it.contains("size", true) }
        val chosen = lines.firstOrNull { it.contains("Override", true) } ?: lines.firstOrNull()
        val m = chosen?.let { Regex("""(\d+)x(\d+)""").find(it) } ?: return null
        return ScreenSize(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    fun androidVersion(): String {
        val rel = sh("getprop ro.build.version.release").trim()
        val sdk = sh("getprop ro.build.version.sdk").trim()
        return "Android $rel (SDK $sdk)"
    }

    fun readFlags(): String {
        val fr = sh("settings get global force_resizable_activities").trim()
        val ff = sh("settings get global enable_freeform_support").trim()
        return "force_resizable_activities=$fr   enable_freeform_support=$ff"
    }

    /** Liga as flags globais. Só valem depois de reboot no Android 9. */
    fun enableFlags(): String {
        sh("settings put global development_settings_enabled 1")
        sh("settings put global force_resizable_activities 1")
        sh("settings put global enable_freeform_support 1")
        return readFlags()
    }

    fun disableFlags(): String {
        sh("settings put global force_resizable_activities 0")
        sh("settings put global enable_freeform_support 0")
        return readFlags()
    }

    /**
     * Quem está no topo agora. É o jeito de descobrir o pacote do AA sem chutar:
     * abre o Android Auto na central e chama isto.
     */
    fun topActivity(): TopActivity? {
        val out = sh(
            "dumpsys activity activities | grep -E " +
                "\"mResumedActivity|mFocusedActivity|topResumedActivity\""
        )
        // ActivityRecord{4a3f0e u0 com.pkg/.AlgumaActivity t42}
        val m = Regex("""u\d+ ([^ /]+)/([^ }]+) t(\d+)""").find(out) ?: return null
        return TopActivity(m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt())
    }

    /** Task atual de um pacote — o id muda a cada relançamento, então relemos sempre. */
    fun taskIdOf(pkg: String): Int? {
        if (pkg.isBlank()) return null
        val out = sh("dumpsys activity activities | grep TaskRecord | grep \"" + pkg + "\"")
        val m = Regex("""#(\d+)""").find(out) ?: return null
        return m.groupValues[1].toInt()
    }

    /** Pacotes que cheiram a receiver de projeção — ponto de partida pro scan. */
    fun candidatePackages(): List<String> {
        val out = sh("pm list packages")
        return out.lines()
            .map { it.removePrefix("package:").trim() }
            .filter { p -> p.isNotEmpty() && PROJECTION_HINTS.any { p.contains(it, true) } }
            .sorted()
    }

    /** Activity de entrada de um pacote, pra montar o -n quando só temos o pacote. */
    fun launchComponentOf(pkg: String): String? {
        val out = sh(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " + pkg
        )
        return out.lines().map { it.trim() }.firstOrNull { it.contains("/") && !it.contains(" ") }
    }

    fun stacks(): String = sh("am stack list")

    // ── Aplicação ───────────────────────────────────────────────────────────

    /**
     * AA em freeform ocupando a faixa direita, barra em freeform na esquerda.
     *
     * @param component pkg/activity do receiver de AA
     * @param sidebarPx largura da barra em pixels
     */
    fun applyFreeform(component: String, sidebarPx: Int, screen: ScreenSize): String {
        val s = Steps()
        val pkg = component.substringBefore("/")

        if (component.contains("/")) {
            s.exec("am start --windowingMode $MODE_FREEFORM -n $component", "AA relançado em freeform")
        }

        val aaTask = taskIdOf(pkg)
        if (aaTask == null) {
            s.say("!! não achei a task de $pkg — abra o Android Auto e tente de novo")
            return s.toString()
        }
        s.say("task do AA = $aaTask")

        // Sem isto o resize é ignorado quando o receiver declara resizeableActivity=false.
        s.exec("am task resizeable $aaTask 2", "task marcada como redimensionável")
        s.exec(
            "am task resize $aaTask $sidebarPx 0 ${screen.width} ${screen.height}",
            "AA na faixa direita"
        )

        s.exec("am start --windowingMode $MODE_FREEFORM -n $SIDEBAR_COMPONENT", "barra no ar")
        val barTask = taskIdOf(SIDEBAR_PKG)
        if (barTask == null) {
            s.say("!! não achei a task da barra")
        } else {
            s.say("task da barra = $barTask")
            s.exec("am task resize $barTask 0 0 $sidebarPx ${screen.height}", "barra na esquerda")
        }
        return s.toString()
    }

    /**
     * Split clássico: a barra vira docked (primary), o AA vai pro secondary.
     * A ordem importa — o docked precisa existir antes do secondary.
     */
    fun applySplit(component: String, sidebarPx: Int, screen: ScreenSize): String {
        val s = Steps()
        val pkg = component.substringBefore("/")

        taskIdOf(pkg)?.let { s.exec("am task resizeable $it 2", "task do AA redimensionável") }

        s.exec("am start --windowingMode $MODE_SPLIT_PRIMARY -n $SIDEBAR_COMPONENT", "barra como docked")
        s.exec("am stack resize-docked-stack 0 0 $sidebarPx ${screen.height}", "largura do docked")

        if (component.contains("/")) {
            s.exec("am start --windowingMode $MODE_SPLIT_SECONDARY -n $component", "AA ao lado")
        }
        s.say("")
        s.say(stacks())
        return s.toString()
    }

    /** Só redimensiona a task que já está aberta, sem relançar nada. */
    fun resizeOnly(pkg: String, sidebarPx: Int, screen: ScreenSize): String {
        val s = Steps()
        val task = taskIdOf(pkg)
        if (task == null) {
            s.say("!! não achei a task de $pkg")
            return s.toString()
        }
        s.say("task = $task")
        s.exec("am task resizeable $task 2")
        s.exec("am task resize $task $sidebarPx 0 ${screen.width} ${screen.height}")
        return s.toString()
    }

    /** Volta tudo pro fullscreen e derruba a barra. */
    fun restore(component: String): String {
        val s = Steps()
        s.exec("am force-stop $SIDEBAR_PKG")
        val pkg = component.substringBefore("/")
        val task = taskIdOf(pkg)
        val screen = screenSize()
        if (task != null && screen != null) {
            s.exec("am task resize $task 0 0 ${screen.width} ${screen.height}")
        }
        if (component.contains("/")) {
            s.exec("am start --windowingMode $MODE_FULLSCREEN -n $component")
        }
        return s.toString()
    }
}

/**
 * Acumulador de passos: guarda o comando, a saída crua e uma nota curta. O log
 * bruto é o produto principal desta feature — na primeira rodada ainda não
 * sabemos quais comandos a central aceita.
 */
private class Steps {
    private val sb = StringBuilder()
    fun exec(cmd: String, note: String = "") {
        sb.append("\$ ").append(cmd).append('\n')
        sb.append(WindowModeUtils.sh(cmd).ifBlank { "(sem saída)" }).append('\n')
        if (note.isNotEmpty()) sb.append("— ").append(note).append('\n')
    }
    fun say(line: String) { sb.append(line).append('\n') }
    override fun toString() = sb.toString()
}
