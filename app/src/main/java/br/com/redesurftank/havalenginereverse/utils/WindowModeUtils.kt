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

    /**
     * FLAG_ACTIVITY_NEW_TASK como o `am start` do Android 9 aceita. A forma longa
     * `--activity-new-task` não existe nessa versão (Intent.parseCommandArgs lança
     * "Unknown option") — foi isso que impediu a barra de subir na rodada de 01/09.
     */
    const val FLAG_NEW_TASK = "-f 0x10000000"

    /** Palavras que costumam aparecer no pacote de um receiver de projeção. */
    private val PROJECTION_HINTS = listOf(
        "auto", "link", "project", "gearhead", "carlife", "carplay",
        "mirror", "cast", "hicar", "easyconn", "zlink", "phone"
    )

    data class TopActivity(val pkg: String, val activity: String, val taskId: Int) {
        val component get() = "$pkg/$activity"
    }

    data class ScreenSize(val width: Int, val height: Int)

    /**
     * Área realmente utilizável. Não é a tela: nesta central mBounds é
     * 0,0-1792,720 e mAppBounds é 128,0-1920,720 — existe uma barra permanente de
     * 128px na esquerda. Posicionar em x=0 joga a barra debaixo dela.
     */
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        override fun toString() = "$left,$top-$right,$bottom (${width}x$height)"
    }

    data class TaskInfo(
        val taskId: Int,
        val stackId: Int,
        val component: String,
        val visible: Boolean
    ) {
        val pkg get() = component.substringBefore("/")
    }

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

    /**
     * Área utilizável, lida do mAppBounds do primeiro stack. Cai pro tamanho da
     * tela se o dump não trouxer mAppBounds.
     */
    fun usableArea(): Area? {
        val m = Regex("""mAppBounds=Rect\((\d+), (\d+) - (\d+), (\d+)\)""").find(stacks())
        if (m != null) {
            return Area(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].toInt(), m.groupValues[4].toInt()
            )
        }
        val s = screenSize() ?: return null
        return Area(0, 0, s.width, s.height)
    }

    /**
     * Todas as tasks abertas, com stack e visibilidade.
     *
     * É o jeito confiável de achar a task do Android Auto: ao contrário do
     * "activity no topo", uma task continua listada depois que você sai dela
     * (visible=false), então dá pra abrir o AA, voltar pro nosso app e escolher.
     */
    fun listTasks(): List<TaskInfo> {
        val out = stacks()
        val result = mutableListOf<TaskInfo>()
        var stackId = -1
        out.lines().forEach { line ->
            Regex("""^Stack id=(\d+)""").find(line.trim())?.let {
                stackId = it.groupValues[1].toInt()
            }
            val t = Regex("""taskId=(\d+): (\S+)""").find(line) ?: return@forEach
            val visible = Regex("""visible=(\w+)""").find(line)?.groupValues?.get(1) == "true"
            result.add(TaskInfo(t.groupValues[1].toInt(), stackId, t.groupValues[2], visible))
        }
        return result
    }

    /**
     * O modo que a stack tem DE VERDADE. No `am stack list` o mWindowingMode vem
     * na linha de configuration, logo abaixo do "Stack id=N".
     */
    fun stackWindowingMode(dump: String, stackId: Int): String {
        val lines = dump.lines()
        val i = lines.indexOfFirst { it.trim().startsWith("Stack id=$stackId ") }
        if (i < 0) return "?"
        for (j in i until minOf(i + 3, lines.size)) {
            Regex("""mWindowingMode=(\w+)""").find(lines[j])?.let { return it.groupValues[1] }
        }
        return "?"
    }

    /**
     * Descobre empiricamente o que o WindowManager desta central concede, usando a
     * nossa própria barra como cobaia — nada de mexer no AA antes de saber.
     *
     * O `am start --windowingMode N` devolve exit 0 sempre; quando o modo não é
     * suportado o WM rebaixa a stack pra fullscreen em silêncio. Então o teste é:
     * pedir o modo, reler o `am stack list` e ver o que a stack virou de verdade.
     */
    fun probeMultiWindow(): String {
        val s = Steps()
        s.say("== suporte declarado ==")
        s.exec("pm list features | grep -i -E \"freeform|multiwindow|picture|split\"")
        s.exec("dumpsys activity settings | grep -i -E \"resizable|freeform|multiwindow|split\"")
        s.say("(flags: " + readFlags() + ")")
        s.say("")

        listOf(
            MODE_FREEFORM to "freeform",
            MODE_SPLIT_PRIMARY to "split-primary"
        ).forEach { (mode, name) ->
            s.say("== probe $name (modo $mode) ==")
            s.exec("am force-stop $SIDEBAR_PKG")
            s.exec("am start $FLAG_NEW_TASK --windowingMode $mode -n $SIDEBAR_COMPONENT")
            val dump = stacks()
            val task = listTasks().firstOrNull { it.pkg == SIDEBAR_PKG }
            if (task == null) {
                s.say("!! a barra não abriu — nada a concluir sobre $name")
            } else {
                val effective = stackWindowingMode(dump, task.stackId)
                s.say("barra na task ${task.taskId}, stack ${task.stackId}")
                s.say(
                    if (effective.startsWith(name.substringBefore("-")))
                        ">> $name CONCEDIDO (mWindowingMode=$effective)"
                    else
                        ">> $name NEGADO — o WM entregou \"$effective\""
                )
            }
            s.say("")
        }
        s.exec("am force-stop $SIDEBAR_PKG")
        return s.toString()
    }

    /**
     * Tudo que preciso ver quando uma das estratégias falha.
     *
     * O item que mais importa é o logcat do ActivityManager/ActivityTaskManager: é
     * lá que sai o motivo de um resize ser recusado ("Activity is not resizeable",
     * "Can not enter split-screen"), e não na saída do `am`, que devolve exit 0
     * mesmo quando o WM ignora o pedido.
     */
    fun collectDiagnostics(component: String, tabLog: String): String = buildString {
        val pkg = component.substringBefore("/")
        appendLine("==== AA SPLIT DIAG ====")
        appendLine("alvo: " + component.ifBlank { "(vazio)" })
        appendLine(androidVersion())
        appendLine("fingerprint: " + sh("getprop ro.build.fingerprint").trim())
        appendLine(readFlags())
        appendLine()
        appendLine("== wm ==")
        appendLine(sh("wm size"))
        appendLine(sh("wm density"))
        appendLine()
        appendLine("== area utilizavel ==")
        appendLine("usableArea = " + (usableArea()?.toString() ?: "?"))
        appendLine()
        appendLine("== features de multi-window ==")
        appendLine(sh("pm list features | grep -i -E \"freeform|multiwindow|picture|split\""))
        appendLine()
        appendLine("== suporte efetivo (dumpsys activity settings) ==")
        appendLine(sh("dumpsys activity settings | grep -i -E \"resizable|freeform|multiwindow|split\""))
        appendLine(sh("dumpsys window | grep -i -E \"mSupports|freeform|multiwindow\""))
        appendLine()
        appendLine("== config de multi-window do overlay ==")
        appendLine(sh("getprop | grep -i -E \"freeform|multiwindow|multi_window\""))
        appendLine()
        appendLine("== am stack list ==")
        appendLine(stacks())
        appendLine()
        appendLine("== stacks / tasks / bounds ==")
        appendLine(
            sh(
                "dumpsys activity activities | grep -E " +
                    "\"Stack #|TaskRecord|ActivityRecord|mResumedActivity|mFocusedActivity" +
                    "|mBounds|WindowingMode|mResizeMode|supportsSplitScreen|isSleeping\""
            )
        )
        appendLine()
        appendLine("== resizeMode declarado pelo alvo ==")
        if (pkg.isNotBlank()) {
            appendLine(
                sh(
                    "dumpsys package " + pkg +
                        " | grep -i -E \"resizeMode|resizeable|versionName|targetSdk|flags=\""
                )
            )
        }
        appendLine()
        appendLine("== logcat AM/WM (400) ==")
        appendLine(sh("logcat -d -t 400 -s ActivityManager:V ActivityTaskManager:V WindowManager:V"))
        appendLine()
        appendLine("== log da aba ==")
        appendLine(tabLog)
    }

    // ── Aplicação ───────────────────────────────────────────────────────────

    /**
     * AA em freeform ocupando a faixa direita, barra em freeform na esquerda.
     *
     * @param component pkg/activity do receiver de AA
     * @param sidebarPx largura da barra em pixels
     */
    fun applyFreeform(component: String, sidebarPx: Int, area: Area): String {
        val s = Steps()
        val pkg = component.substringBefore("/")
        if (pkg == SIDEBAR_PKG) {
            s.say("!! o alvo é o nosso próprio app — escolha a task do Android Auto no passo 1")
            return s.toString()
        }

        val split = area.left + sidebarPx
        s.say("área utilizável: $area → barra ${area.left}..$split, AA $split..${area.right}")

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
        s.exec("am task resize $aaTask $split ${area.top} ${area.right} ${area.bottom}", "AA na direita")
        s.say("modo efetivo do AA: " + modeOfPkg(pkg))

        launchSidebarInto(s, area, split)
        return s.toString()
    }

    /**
     * Sobe só a barra, em freeform, na faixa esquerda — pra quando o AA já está
     * posicionado (ele lembra os bounds da task) e só a barra sumiu.
     */
    fun launchSidebar(sidebarPx: Int, area: Area): String {
        val s = Steps()
        launchSidebarInto(s, area, area.left + sidebarPx)
        return s.toString()
    }

    private fun launchSidebarInto(s: Steps, area: Area, split: Int) {
        // NEW_TASK + singleInstance no manifesto: sem os dois a barra entra na MESMA
        // task do MainActivity e não pode ser posicionada sozinha.
        s.exec(
            "am start $FLAG_NEW_TASK --windowingMode $MODE_FREEFORM -n $SIDEBAR_COMPONENT",
            "barra no ar"
        )
        val barTask = listTasks().firstOrNull { it.component.contains("SidebarActivity") }
        if (barTask == null) {
            s.say("!! não achei a task da barra")
            return
        }
        s.say("task da barra = ${barTask.taskId} (stack ${barTask.stackId})")
        s.exec("am task resizeable ${barTask.taskId} 2")
        s.exec(
            "am task resize ${barTask.taskId} ${area.left} ${area.top} $split ${area.bottom}",
            "barra na esquerda"
        )
        s.say(
            "barra: modo " + stackWindowingMode(stacks(), barTask.stackId) +
                ", bounds " + boundsOfTask(barTask.taskId)
        )
    }

    /** Modo efetivo da stack onde o pacote está — o que o WM concedeu de fato. */
    fun modeOfPkg(pkg: String): String {
        val dump = stacks()
        val t = listTasks().firstOrNull { it.pkg == pkg } ?: return "?"
        return stackWindowingMode(dump, t.stackId)
    }

    /**
     * Split clássico: a barra vira docked (primary), o AA vai pro secondary.
     * A ordem importa — o docked precisa existir antes do secondary.
     */
    fun applySplit(component: String, sidebarPx: Int, area: Area): String {
        val s = Steps()
        val pkg = component.substringBefore("/")
        if (pkg == SIDEBAR_PKG) {
            s.say("!! o alvo é o nosso próprio app — escolha a task do Android Auto no passo 1")
            return s.toString()
        }

        taskIdOf(pkg)?.let { s.exec("am task resizeable $it 2", "task do AA redimensionável") }

        s.exec(
            "am start $FLAG_NEW_TASK --windowingMode $MODE_SPLIT_PRIMARY -n $SIDEBAR_COMPONENT",
            "barra como docked"
        )
        val bar = listTasks().firstOrNull { it.component.contains("SidebarActivity") }
        s.say(
            "modo efetivo da barra: " +
                (bar?.let { stackWindowingMode(stacks(), it.stackId) } ?: "?")
        )
        // No Android 9 o comando exige DOIS retângulos: o do docked e o da task
        // dentro dele. Com quatro números ele lança "Argument expected after".
        val dock = "${area.left} ${area.top} ${area.left + sidebarPx} ${area.bottom}"
        s.exec("am stack resize-docked-stack $dock $dock", "largura do docked")

        if (component.contains("/")) {
            s.exec("am start --windowingMode $MODE_SPLIT_SECONDARY -n $component", "AA ao lado")
            s.say("modo efetivo do AA: " + modeOfPkg(pkg))
        }
        s.say("")
        s.say(stacks())
        return s.toString()
    }

    /** Só redimensiona a task que já está aberta, sem relançar nada. */
    fun resizeOnly(pkg: String, sidebarPx: Int, area: Area): String {
        val s = Steps()
        if (pkg == SIDEBAR_PKG) {
            s.say("!! o alvo é o nosso próprio app — escolha a task do Android Auto no passo 1")
            return s.toString()
        }
        val task = taskIdOf(pkg)
        if (task == null) {
            s.say("!! não achei a task de $pkg")
            return s.toString()
        }
        s.say("task = $task")
        s.exec("am task resizeable $task 2")
        s.exec(
            "am task resize $task ${area.left + sidebarPx} ${area.top} ${area.right} ${area.bottom}"
        )
        s.say("modo efetivo: " + modeOfPkg(pkg))
        s.say("bounds agora: " + (listTasks().firstOrNull { it.pkg == pkg }?.let { boundsOfTask(it.taskId) } ?: "?"))
        return s.toString()
    }

    /** Bounds atuais de uma task, do `am stack list`. */
    fun boundsOfTask(taskId: Int): String {
        val line = stacks().lines().firstOrNull { it.contains("taskId=$taskId:") } ?: return "?"
        return Regex("""bounds=(\S+)""").find(line)?.groupValues?.get(1) ?: "?"
    }

    /**
     * Volta tudo pro fullscreen e derruba a barra.
     *
     * Não relança o nosso MainActivity: era isso que empilhava uma activity nova a
     * cada tentativa (11 na primeira rodada) e zerava o log da aba junto.
     */
    fun restore(component: String): String {
        val s = Steps()
        s.exec("am force-stop $SIDEBAR_PKG")
        val pkg = component.substringBefore("/")
        if (pkg.isBlank() || pkg == SIDEBAR_PKG) return s.toString()
        val task = taskIdOf(pkg)
        val area = usableArea()
        if (task != null && area != null) {
            s.exec("am task resize $task ${area.left} ${area.top} ${area.right} ${area.bottom}")
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
