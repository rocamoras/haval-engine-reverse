package br.com.redesurftank.havalenginereverse.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import br.com.redesurftank.havalenginereverse.App;
import br.com.redesurftank.havalenginereverse.R;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Injeção de um script Frida no processo do SystemUI para destravar a exibição da
 * temperatura externa real na barra de status (ver res/raw/com_android_systemui.js).
 *
 * Portado (simplificado) da ferramenta de referência. Requer os binários arm64
 * `fridaserver` e `fridainject` em res/raw. No repositório eles são placeholders
 * minúsculos; um build "gordo" de teste substitui pelos binários reais.
 */
public class FridaUtils {
    private static final String TAG = "FridaUtils";

    public static final String FRIDA_SERVER_PATH   = "/data/local/tmp/fridaserver";
    public static final String FRIDA_INJECTOR_PATH = "/data/local/tmp/fridainjector";
    public static final String SCRIPT_PATH         = "/data/local/tmp/com_android_systemui.js";
    private static final String TARGET_PROCESS     = "com.android.systemui";

    // ── Injeção de clima na launcher (enviar temperatura "como se fosse o app") ──
    public static final String LAUNCHER_SCRIPT_PATH = "/data/local/tmp/com_beantechs_launcher_weather.js";
    public static final String WEATHER_CTRL_PATH    = "/data/local/tmp/inject_weather";
    private static final String LAUNCHER_PROCESS    = "com.beantechs.launcher";

    // ── Card de mídia online da MediaCenter (quais CPs aparecem) ──────────────
    public static final String MEDIA_CP_SCRIPT_PATH = "/data/local/tmp/com_beantechs_mediacenter_cp.js";
    public static final String MEDIA_CP_CTRL_PATH   = "/data/local/tmp/inject_media_cp";
    private static final String MEDIACENTER_PROCESS = "com.beantechs.mediacenter";
    private static final String MEDIACENTER_ACTIVITY =
            "com.beantechs.mediacenter/com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity";

    /** Abaixo disso o arquivo em res/raw é um placeholder, não o binário real. */
    private static final long MIN_REAL_BINARY_BYTES = 100_000L;

    /** true se os binários reais do Frida estão embutidos neste APK. */
    public static boolean fridaToolsEmbedded() {
        try {
            return rawResourceSize(R.raw.fridaserver) > MIN_REAL_BINARY_BYTES
                    && rawResourceSize(R.raw.fridainject) > MIN_REAL_BINARY_BYTES;
        } catch (Exception e) {
            return false;
        }
    }

    /** Extrai binários+script, sobe o fridaserver e injeta o hook no SystemUI. Retorna msg de status. */
    public static String startAndInject() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        if (!fridaToolsEmbedded())
            return "Binários do Frida ausentes neste APK (placeholder). Use o APK 'fat/test'.";
        try {
            if (!extract(R.raw.fridaserver, FRIDA_SERVER_PATH)) return "Falha ao extrair fridaserver";
            if (!extract(R.raw.fridainject, FRIDA_INJECTOR_PATH)) return "Falha ao extrair fridainjector";
            if (!extract(R.raw.com_android_systemui, SCRIPT_PATH)) return "Falha ao extrair script";

            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            svc.newProcess(new String[]{"setenforce", "0"}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_SERVER_PATH}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_INJECTOR_PATH}, null, null).waitFor();

            String running = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", "fridaserver"}).trim();
            if (running.isEmpty()) {
                svc.newProcess(new String[]{"/bin/sh", "-c",
                        "setsid " + FRIDA_SERVER_PATH + " >/dev/null 2>&1 < /dev/null &"}, null, null).waitFor();
                Thread.sleep(1500);
            }

            String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                    "ps -A | grep ' " + TARGET_PROCESS + "' | awk '{print $2}'"}).trim();
            if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
            if (pid.isEmpty())
                pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", TARGET_PROCESS}).trim();
            if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
            if (pid.isEmpty()) return "SystemUI não encontrado (pid vazio)";

            // Idempotente: remove injeções anteriores no SystemUI antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_android_systemui"});

            String logFile = "/data/local/tmp/com_android_systemui.log";
            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid + " -s " + SCRIPT_PATH
                    + " > " + logFile + " 2>&1 < /dev/null &";
            svc.newProcess(new String[]{"/bin/sh", "-c", cmd}, null, null).waitFor();
            Log.w(TAG, "[frida] injetado no SystemUI pid=" + pid);
            return "Injetado no SystemUI (pid " + pid + "). Veja a barra.";
        } catch (Exception e) {
            Log.e(TAG, "[frida] erro: " + e.getMessage(), e);
            return "Erro: " + e.getMessage();
        }
    }

    /**
     * Injeta o hook de clima na launcher (com.beantechs.launcher). A partir daí,
     * o valor escrito por {@link #writeWeather} aparece no card de clima da home.
     * O valor em si é atualizado sem reinjetar (o script lê o arquivo de controle).
     */
    public static String injectLauncherWeather() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        if (!fridaToolsEmbedded())
            return "Binários do Frida ausentes neste APK (placeholder). Use o APK 'fat/test'.";
        try {
            if (!extract(R.raw.fridaserver, FRIDA_SERVER_PATH)) return "Falha ao extrair fridaserver";
            if (!extract(R.raw.fridainject, FRIDA_INJECTOR_PATH)) return "Falha ao extrair fridainjector";
            if (!extract(R.raw.com_beantechs_launcher_weather, LAUNCHER_SCRIPT_PATH))
                return "Falha ao extrair script";

            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            svc.newProcess(new String[]{"setenforce", "0"}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_SERVER_PATH}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_INJECTOR_PATH}, null, null).waitFor();

            String running = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", "fridaserver"}).trim();
            if (running.isEmpty()) {
                svc.newProcess(new String[]{"/bin/sh", "-c",
                        "setsid " + FRIDA_SERVER_PATH + " >/dev/null 2>&1 < /dev/null &"}, null, null).waitFor();
                Thread.sleep(1500);
            }

            String pid = launcherPid();
            if (pid.isEmpty()) {
                // A launcher (home) é morta pelo sistema quando nosso app fica em
                // foreground. Acorda ela antes de injetar.
                svc.newProcess(new String[]{"am", "start", "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.HOME"}, null, null).waitFor();
                Thread.sleep(2000);
                pid = launcherPid();
            }
            if (pid.isEmpty()) return "Launcher não encontrada (pid vazio)";

            // Idempotente: remove injeções anteriores na launcher antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_beantechs_launcher_weather"});

            String logFile = "/data/local/tmp/com_beantechs_launcher_weather.log";
            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid + " -s " + LAUNCHER_SCRIPT_PATH
                    + " > " + logFile + " 2>&1 < /dev/null &";
            svc.newProcess(new String[]{"/bin/sh", "-c", cmd}, null, null).waitFor();
            Log.w(TAG, "[frida] injetado na launcher pid=" + pid);
            return "Hook de clima ativo na launcher (pid " + pid + ")";
        } catch (Exception e) {
            Log.e(TAG, "[frida] erro: " + e.getMessage(), e);
            return "Erro: " + e.getMessage();
        }
    }

    /**
     * Injeta o hook do card de mídia online na MediaCenter (com.beantechs.mediacenter).
     * A lista de ícones em si vem do arquivo de controle escrito por
     * {@link #writeMediaCp} — muda sem reinjetar.
     */
    public static String injectMediaCenterCp() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        if (!fridaToolsEmbedded())
            return "Binários do Frida ausentes neste APK (placeholder). Use o APK 'fat/test'.";
        try {
            if (!extract(R.raw.fridaserver, FRIDA_SERVER_PATH)) return "Falha ao extrair fridaserver";
            if (!extract(R.raw.fridainject, FRIDA_INJECTOR_PATH)) return "Falha ao extrair fridainjector";
            if (!extract(R.raw.com_beantechs_mediacenter_cp, MEDIA_CP_SCRIPT_PATH))
                return "Falha ao extrair script";

            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            svc.newProcess(new String[]{"setenforce", "0"}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_SERVER_PATH}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_INJECTOR_PATH}, null, null).waitFor();

            String running = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", "fridaserver"}).trim();
            if (running.isEmpty()) {
                svc.newProcess(new String[]{"/bin/sh", "-c",
                        "setsid " + FRIDA_SERVER_PATH + " >/dev/null 2>&1 < /dev/null &"}, null, null).waitFor();
                Thread.sleep(1500);
            }

            String pid = firstPid(MEDIACENTER_PROCESS);
            if (pid.isEmpty()) {
                // O app de mídia pode estar parado: abre a tela pra criar o processo.
                svc.newProcess(new String[]{"am", "start", "-n", MEDIACENTER_ACTIVITY}, null, null).waitFor();
                Thread.sleep(2500);
                pid = firstPid(MEDIACENTER_PROCESS);
            }
            if (pid.isEmpty()) return "MediaCenter não encontrada (pid vazio)";

            // Idempotente: remove injeções anteriores antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_beantechs_mediacenter_cp"});

            String logFile = "/data/local/tmp/com_beantechs_mediacenter_cp.log";
            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid + " -s " + MEDIA_CP_SCRIPT_PATH
                    + " > " + logFile + " 2>&1 < /dev/null &";
            svc.newProcess(new String[]{"/bin/sh", "-c", cmd}, null, null).waitFor();
            Log.w(TAG, "[frida] injetado na mediacenter pid=" + pid);
            return "Hook do card ativo na MediaCenter (pid " + pid + ")";
        } catch (Exception e) {
            Log.e(TAG, "[frida] erro: " + e.getMessage(), e);
            return "Erro: " + e.getMessage();
        }
    }

    /**
     * Escreve a lista de CPs que deve aparecer no card. CSV de ids 501..600
     * (ex. "553" = só TuneIn). Vazio/"none" = nenhum ícone.
     */
    public static String writeMediaCp(String csv) {
        try {
            String content = (csv == null || csv.trim().isEmpty()) ? "none" : csv.trim();
            File f = new File(App.getContext().getCacheDir(), "inject_media_cp");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(content.getBytes("UTF-8")); }
            ShizukuUtils.runCommandAndGetOutput(new String[]{"cp", "-f", f.getAbsolutePath(), MEDIA_CP_CTRL_PATH});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"chmod", "644", MEDIA_CP_CTRL_PATH});
            return "none".equals(content)
                    ? "Aplicado: nenhum ícone no card"
                    : "Aplicado: [" + content + "]";
        } catch (Exception e) {
            Log.e(TAG, "[frida] writeMediaCp erro: " + e.getMessage(), e);
            return "Erro ao aplicar: " + e.getMessage();
        }
    }

    /** Traz a tela de mídia pra frente (o hook repinta o card sozinho em ~1,5s). */
    public static String openMediaCenter() {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "start", "-n", MEDIACENTER_ACTIVITY});
            return "MediaCenter aberta";
        } catch (Exception e) {
            return "Erro ao abrir: " + e.getMessage();
        }
    }

    /** Remove o arquivo de controle e a injeção — volta ao comportamento de fábrica. */
    public static String stopMediaCenterCp() {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"rm", "-f", MEDIA_CP_CTRL_PATH});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_beantechs_mediacenter_cp"});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", MEDIACENTER_PROCESS});
            return "Hook removido e MediaCenter reiniciada (card volta ao padrão)";
        } catch (Exception e) {
            return "Erro ao parar: " + e.getMessage();
        }
    }

    /**
     * Mata a MediaCenter, reabre a tela e injeta em seguida. Usar quando o hook
     * "não pegou": garante que o injetor entra num processo novo com as classes
     * já carregadas, em vez de disputar com uma Activity criada antes dele.
     */
    public static String restartMediaCenterAndInject() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_beantechs_mediacenter_cp"});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", MEDIACENTER_PROCESS});
            Thread.sleep(800);
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "start", "-n", MEDIACENTER_ACTIVITY});
            Thread.sleep(3500);   // deixa o App/serviço subir antes de atacar
            return "reiniciado · " + injectMediaCenterCp();
        } catch (Exception e) {
            return "Erro ao reiniciar: " + e.getMessage();
        }
    }

    /** Diagnóstico focado no hook do card — é isso que dá pra colar num relato. */
    public static String mediaCpDiag() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível — não consegui ler nada.";
        StringBuilder sb = new StringBuilder();
        sb.append("pid mediacenter = ").append(firstPid(MEDIACENTER_PROCESS)).append('\n');
        sb.append("ps: ").append(sh("ps -A -o PID,NAME 2>/dev/null | grep -i mediacenter | grep -v grep")).append('\n');
        sb.append("injetores: ").append(sh("ps -A -o PID,NAME,ARGS 2>/dev/null | grep -i fridainject | grep -v grep")).append('\n');
        sb.append("fridaserver = ").append(sh("pidof fridaserver")).append('\n');
        sb.append("ctrl (").append(MEDIA_CP_CTRL_PATH).append(") = ").append(readMediaCp()).append('\n');
        sb.append("country code = ").append(sh("getprop persist.bean.country.code")).append('\n');
        sb.append("-- log do hook --\n").append(mediaCpLog());
        return sb.toString();
    }

    /** Conteúdo atual do arquivo de controle (vazio = sem override). */
    public static String readMediaCp() {
        String out = sh("cat " + MEDIA_CP_CTRL_PATH + " 2>/dev/null").trim();
        return out;
    }

    /** Log da injeção na MediaCenter (root:600 — lê via Shizuku). */
    public static String mediaCpLog() {
        return sh("tail -40 /data/local/tmp/com_beantechs_mediacenter_cp.log 2>&1");
    }

    /** Primeiro pid de um processo (pidof pode devolver vários). */
    private static String firstPid(String process) {
        String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", process}).trim();
        if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
        if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
        return pid;
    }

    /** Executa um comando como root via Shizuku e retorna a saída (ou marcador de erro). */
    private static String sh(String cmd) {
        try {
            return ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c", cmd});
        } catch (Exception e) {
            return "(err: " + e.getMessage() + ")";
        }
    }

    /**
     * Coleta um relatório de diagnóstico (logs do Frida, pids, versão, logcat).
     * Os logs em /data/local/tmp são root:600 — lê via Shizuku (root). Blocante.
     */
    public static String collectDiagnostics() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível — não consegui ler os logs.";
        StringBuilder sb = new StringBuilder();
        sb.append("==== FRIDA DIAG ====\n");
        sb.append("app versionName: ").append(appVersion()).append('\n');
        sb.append("\n== frida version ==\n").append(sh(FRIDA_INJECTOR_PATH + " --version 2>&1"));
        sb.append("\n== processos frida ==\n").append(sh("ps -A -o PID,NAME 2>/dev/null | grep -i frida | grep -v grep"));
        sb.append("\n== pids alvo ==\n")
          .append("systemui=").append(sh("pidof com.android.systemui")).append('\n')
          .append("launcher=").append(sh("pidof com.beantechs.launcher")).append('\n')
          .append("mediacenter=").append(sh("pidof " + MEDIACENTER_PROCESS)).append('\n');
        sb.append("\n== inject_weather ==\n").append(sh("cat " + WEATHER_CTRL_PATH + " 2>&1"));
        sb.append("\n== inject_media_cp ==\n").append(sh("cat " + MEDIA_CP_CTRL_PATH + " 2>&1"));
        sb.append("\n== country code ==\n")
          .append("persist.bean.country.code=").append(sh("getprop persist.bean.country.code")).append('\n')
          .append("gwm.special.country.export=")
          .append(sh("getprop persist.vendor.gwm.cfg.special.country.export")).append('\n');
        sb.append("\n== LOG mediacenter (card) ==\n")
          .append(sh("cat /data/local/tmp/com_beantechs_mediacenter_cp.log 2>&1"));
        sb.append("\n== LOG systemui (barra) ==\n").append(sh("cat /data/local/tmp/com_android_systemui.log 2>&1"));
        sb.append("\n== LOG launcher (hiboard) ==\n").append(sh("cat " + "/data/local/tmp/com_beantechs_launcher_weather.log" + " 2>&1"));
        sb.append("\n== logcat (frida/temp/weather, 300) ==\n")
          .append(sh("logcat -d -t 300 2>/dev/null | grep -iE 'frida|outsideTemp|outside_temp|launcher-wx|sysui-outtemp|FridaUtils|BeanCarStatusBar' | tail -120"));
        return sb.toString();
    }

    private static String appVersion() {
        try {
            android.content.Context c = App.getContext();
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) { return "?"; }
    }

    /** pid da launcher (home). Vazio se não estiver rodando. */
    private static String launcherPid() {
        String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", LAUNCHER_PROCESS}).trim();
        if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
        if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
        return pid;
    }

    /** Escreve o valor a exibir no card de clima. Formato: tmp|condCode|condTxt|min|max. */
    public static String writeWeather(String tmp, String code, String txt, String min, String max) {
        try {
            String content = tmp + "|" + code + "|" + txt + "|" + min + "|" + max;
            // cache -> cp via Shizuku (mesmo padrão de extract(), evita escaping/unicode).
            File f = new File(App.getContext().getCacheDir(), "inject_weather");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(content.getBytes("UTF-8")); }
            ShizukuUtils.runCommandAndGetOutput(new String[]{"cp", "-f", f.getAbsolutePath(), WEATHER_CTRL_PATH});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"chmod", "644", WEATHER_CTRL_PATH});
            return "Enviado: " + tmp + "° (" + (txt.isEmpty() ? "sem desc" : txt) + ")";
        } catch (Exception e) {
            Log.e(TAG, "[frida] writeWeather erro: " + e.getMessage(), e);
            return "Erro ao enviar: " + e.getMessage();
        }
    }

    /** Para a injeção de clima na launcher e remove o valor. */
    public static String stopLauncherWeather() {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"rm", "-f", WEATHER_CTRL_PATH});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_beantechs_launcher_weather"});
            return "Injeção parada (o hook cai no próximo restart da launcher)";
        } catch (Exception e) {
            return "Erro ao parar: " + e.getMessage();
        }
    }

    /** Encerra fridaserver/fridainjector. O hook some quando o SystemUI reinicia. */
    public static String stop() {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "fridainjector"});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "fridaserver"});
            return "Frida encerrado (o hook cai no próximo restart do SystemUI)";
        } catch (Exception e) {
            return "Erro ao encerrar: " + e.getMessage();
        }
    }

    private static long rawResourceSize(int resId) throws Exception {
        try (InputStream in = App.getContext().getResources().openRawResource(resId)) {
            long total = 0;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) total += r;
            return total;
        }
    }

    private static boolean extract(int resId, String destPath) {
        try {
            File tmp = new File(App.getContext().getCacheDir(), new File(destPath).getName());
            try (InputStream in = App.getContext().getResources().openRawResource(resId);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            ShizukuUtils.runCommandAndGetOutput(new String[]{"cp", "-f", tmp.getAbsolutePath(), destPath});
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[frida] extract falhou (" + destPath + "): " + e.getMessage(), e);
            return false;
        }
    }
}
