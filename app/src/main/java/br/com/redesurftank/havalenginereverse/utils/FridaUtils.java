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

            String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                    "ps -A | grep ' " + LAUNCHER_PROCESS + "' | awk '{print $2}'"}).trim();
            if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
            if (pid.isEmpty())
                pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", LAUNCHER_PROCESS}).trim();
            if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
            if (pid.isEmpty()) return "Launcher não encontrada (pid vazio)";

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
