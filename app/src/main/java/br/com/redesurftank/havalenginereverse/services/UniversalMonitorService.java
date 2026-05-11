package br.com.redesurftank.havalenginereverse.services;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IInterface;
import java.io.FileDescriptor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService;
import com.beantechs.intelligentvehiclecontrol.sdk.IListener;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.redesurftank.havalenginereverse.App;
import br.com.redesurftank.havalenginereverse.EngineReverseStateHolder;
import br.com.redesurftank.havalenginereverse.broadcastReceivers.RestartReceiver;
import br.com.redesurftank.havalenginereverse.utils.IPTablesUtils;
import br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

@SuppressLint("PrivateApi")
public class UniversalMonitorService extends Service implements Shizuku.OnBinderDeadListener {

    private static final String TAG = "UniversalMonitorService";

    private static final String CHANNEL_ID     = "EngineReverseChannel";
    private static final int    NOTIFICATION_ID = 1;
    private static final String PREFS_NAME      = "engine_reverse_prefs";
    private static final String KEY_SHIZUKU_LIB = "shizuku_lib_location";

    /**
     * Estratégia 5 — Active Probe.
     * Candidatos a testar via fetchDatas(). Qualquer um que retorne valor não-nulo
     * é uma chave real no serviço → será adicionada ao listener "probe".
     */
    private static final String[] PROBE_CANDIDATES = {
        // car.basic
        "car.basic.inside_temp", "car.basic.outside_temp", "car.basic.vehicle_speed",
        "car.basic.engine_rpm", "car.basic.fuel_level", "car.basic.battery_voltage",
        "car.basic.odometer", "car.basic.gear", "car.basic.gear_position",
        "car.basic.driving_mode", "car.basic.eco_mode", "car.basic.sport_mode",
        "car.basic.charge_level", "car.basic.range", "car.basic.power_status",
        // car.engine
        "car.engine.rpm", "car.engine.oil_temp", "car.engine.coolant_temp",
        "car.engine.throttle", "car.engine.load", "car.engine.torque",
        "car.engine.power", "car.engine.fuel_consumption", "car.engine.instantaneous_consumption",
        "car.engine.average_consumption", "car.engine.range", "car.engine.status",
        "car.engine.oil_pressure", "car.engine.air_intake_temp", "car.engine.turbo_pressure",
        // car.body - doors
        "car.body.door_fl_open", "car.body.door_fr_open", "car.body.door_rl_open", "car.body.door_rr_open",
        "car.body.door_fl_locked", "car.body.door_fr_locked", "car.body.door_rl_locked", "car.body.door_rr_locked",
        "car.body.trunk_open", "car.body.trunk_locked", "car.body.hood_open",
        // car.body - windows
        "car.body.window_fl", "car.body.window_fr", "car.body.window_rl", "car.body.window_rr",
        "car.body.window_fl_position", "car.body.window_fr_position",
        "car.body.window_rl_position", "car.body.window_rr_position",
        "car.body.sunroof_open", "car.body.sunroof_tilt", "car.body.sunroof_position",
        // car.body - lights
        "car.body.hazard_lights", "car.body.fog_lights_front", "car.body.fog_lights_rear",
        "car.body.headlights", "car.body.headlights_auto", "car.body.daytime_running_lights",
        "car.body.turn_signal_left", "car.body.turn_signal_right",
        "car.body.interior_light", "car.body.ambient_light", "car.body.ambient_light_color",
        // car.body - wipers
        "car.body.wiper_front", "car.body.wiper_rear", "car.body.wiper_front_speed",
        // car.body - misc
        "car.body.central_lock", "car.body.horn", "car.body.vehicle_posture",
        // car.safety
        "car.safety.airbag_status", "car.safety.abs_active", "car.safety.esp_active",
        "car.safety.tpms_fl", "car.safety.tpms_fr", "car.safety.tpms_rl", "car.safety.tpms_rr",
        "car.safety.tpms_fl_pressure", "car.safety.tpms_fr_pressure",
        "car.safety.tpms_rl_pressure", "car.safety.tpms_rr_pressure",
        "car.safety.seatbelt_driver", "car.safety.seatbelt_passenger",
        "car.safety.seatbelt_rl", "car.safety.seatbelt_rr",
        "car.safety.collision_warning", "car.safety.lane_departure",
        "car.safety.blind_spot_left", "car.safety.blind_spot_right",
        "car.safety.parking_radar_front", "car.safety.parking_radar_rear",
        "car.safety.reversing_radar", "car.safety.360_camera",
        // car.hvac (extra além dos conhecidos)
        "car.hvac.passenger_temperature", "car.hvac.rear_temperature",
        "car.hvac.rear_fan_speed", "car.hvac.rear_blow_mode",
        "car.hvac.rear_ac_enable", "car.hvac.ionizer_enable",
        "car.hvac.fragrance_enable", "car.hvac.fragrance_level",
        "car.hvac.air_quality", "car.hvac.co2_value", "car.hvac.tvoc_value",
        // car.comfort_setting (extra além dos conhecidos)
        "car.comfort_setting.driver_seat_heat_level",
        "car.comfort_setting.passenger_seat_heat_level",
        "car.comfort_setting.steering_heat_enable",
        "car.comfort_setting.driver_seat_massage_level",
        "car.comfort_setting.passenger_seat_massage_level",
        "car.comfort_setting.driver_seat_massage_mode",
        "car.comfort_setting.driver_seat_position_backrest",
        "car.comfort_setting.driver_seat_position_cushion",
        "car.comfort_setting.driver_seat_position_height",
        "car.comfort_setting.driver_seat_position_lumbar",
        "car.comfort_setting.passenger_seat_position_backrest",
        "car.comfort_setting.passenger_seat_position_cushion",
        // car.configure (extra)
        "car.configure.auto_lock", "car.configure.auto_unlock",
        "car.configure.welcome_mode", "car.configure.approach_light",
        "car.configure.one_key_start", "car.configure.remote_start",
        "car.configure.auto_hold", "car.configure.hill_assist",
        "car.configure.brake_hold", "car.configure.electric_park_brake",
        // car.navi
        "car.navi.destination", "car.navi.remaining_distance",
        "car.navi.remaining_time", "car.navi.current_road",
        "car.navi.latitude", "car.navi.longitude", "car.navi.heading",
        // car.media
        "car.media.source", "car.media.volume", "car.media.track",
        "car.media.status", "car.media.artist", "car.media.album",
        // car.phone
        "car.phone.call_status", "car.phone.signal_strength", "car.phone.battery",
        // car.adas
        "car.adas.acc_status", "car.adas.acc_speed", "car.adas.lka_status",
        "car.adas.aeb_status", "car.adas.tja_status",
        // car.charge (NEV/PHEV)
        "car.charge.status", "car.charge.level", "car.charge.remaining_time",
        "car.charge.power", "car.charge.current", "car.charge.voltage",
    };

    // Propriedades conhecidas (do climate control) — usadas como seed inicial
    private static final String[] KNOWN_PROPS = {
        "car.hvac.auto_enable", "car.basic.inside_temp",
        "car.hvac.driver_temperature", "car.hvac.power_mode",
        "car.hvac.ac_enable", "car.hvac.front_defrost_enable",
        "car.hvac.heating_enable", "car.hvac.Intelligent_switch_enable",
        "car.hvac.setting.limit_enable", "car.hvac.front_temperature_range",
        "car.hvac.Intelligent_temperature_range", "car.hvac.pm2.5_value",
        "car.hvac.setting.comfort_curve",
        "car.comfort_setting.chair_memory.auto_enable",
        "car.configure.ass_memory_setting",
        "car.comfort_setting.chair_mem_pos_set_action",
        "car.comfort_setting.chair_mem_pos_set_feedback",
        "car.comfort_setting.driver_seat_ventilation_level",
        "car.comfort_setting.passenger_seat_ventilation_level"
    };

    private static Method getServiceMethod;

    static {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getServiceMethod = sm.getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, "Failed to get android.os.ServiceManager.getService", e);
        }
    }

    private static IBinder getServiceBinder(String serviceName) {
        try {
            return (IBinder) Objects.requireNonNull(getServiceMethod.invoke(null, serviceName));
        } catch (IllegalAccessException | InvocationTargetException | NullPointerException e) {
            throw new RuntimeException("Failed to get system service: " + serviceName, e);
        }
    }

    private HandlerThread handlerThread;
    private Handler       backgroundHandler;

    private boolean isShizukuInitialized = false;
    private boolean isServiceRunning     = false;

    private IIntelligentVehicleControlService controlService;

    /**
     * Listener principal — recebe eventos do Beantechs.
     * Registrado SEM chamar addListenerKey primeiro (estratégia 1):
     * hipótese — o serviço envia TODOS os eventos sem filtro.
     *
     * Também sobrescreve onTransact() para capturar transações raw
     * (estratégia 2 — captura dados antes do AIDL deserializar).
     */
    private final IListener vehicleDataListener = new IListener.Stub() {

        @Override
        public void onDataChanged(String key, String value) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(key, value, "listener");
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            // Captura o Parcel raw antes do processamento AIDL padrão
            tryParseRawParcel(code, data);
            return super.onTransact(code, data, reply, flags);
        }
    };

    /**
     * Listener registrado via addListenerKey com array vazio (estratégia 3a).
     * Se o serviço interpretar array vazio como "todas as chaves", receberemos tudo.
     */
    private final IListener emptyKeyListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(key, value, "empty-key");
        }
    };

    /**
     * Listener registrado via addListenerKey com ["*"] (estratégia 3b).
     */
    private final IListener wildcardListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(key, value, "wildcard");
        }
    };

    /**
     * Listener para as chaves descobertas pelo Active Probe (estratégia 5).
     */
    private final IListener probeListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(key, value, "probe");
        }
    };

    /**
     * Listener para as chaves descobertas pelo APK String Scan (estratégia 6).
     */
    private final IListener apkScanListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(key, value, "apk-scan");
        }
    };

    /**
     * Proxy do IBinder do serviço Beantechs — intercepta todas as chamadas de saída
     * e as respostas recebidas (estratégia 4: BinderProxy).
     */
    private IBinder createBinderProxy(IBinder original) {
        return new IBinder() {
            @Override
            public String getInterfaceDescriptor() throws RemoteException {
                return original.getInterfaceDescriptor();
            }

            @Override
            public boolean pingBinder() {
                return original.pingBinder();
            }

            @Override
            public boolean isBinderAlive() {
                return original.isBinderAlive();
            }

            @Override
            public IInterface queryLocalInterface(String descriptor) {
                return null; // força o uso do proxy
            }

            @Override
            public void dump(FileDescriptor fd, String[] args) throws RemoteException {
                original.dump(fd, args);
            }

            @Override
            public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
                original.dumpAsync(fd, args);
            }

            @Override
            public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                boolean result = original.transact(code, data, reply, flags);
                // Tenta extrair strings da resposta do serviço
                tryParseReplyParcel(code, reply);
                return result;
            }

            @Override
            public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
                original.linkToDeath(recipient, flags);
            }

            @Override
            public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
                return original.unlinkToDeath(recipient, flags);
            }
        };
    }

    /**
     * Tenta extrair pares key/value de um Parcel raw recebido via onTransact.
     * Parcels AIDL têm: token de interface (string), depois os argumentos.
     */
    private void tryParseRawParcel(int code, Parcel data) {
        try {
            int savedPos = data.dataPosition();
            data.setDataPosition(0);
            String interfaceToken = data.readString();
            // código 1 = primeiro método = onDataChanged(key, value)
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                String key   = data.readString();
                String value = data.readString();
                if (key != null && !key.isEmpty()) {
                    EngineReverseStateHolder.INSTANCE.onEventReceived(key, value != null ? value : "", "raw-transact");
                }
            } else {
                // Outros códigos — tenta ler qualquer string que esteja no Parcel
                extractAllStrings(data, "transact-code-" + code);
            }
            data.setDataPosition(savedPos);
        } catch (Exception e) {
            Log.v(TAG, "tryParseRawParcel failed: " + e.getMessage());
        }
    }

    /**
     * Tenta extrair informações da resposta do serviço após uma chamada.
     */
    private void tryParseReplyParcel(int code, Parcel reply) {
        try {
            if (reply == null || reply.dataSize() == 0) return;
            int savedPos = reply.dataPosition();
            reply.setDataPosition(0);
            extractAllStrings(reply, "reply-code-" + code);
            reply.setDataPosition(savedPos);
        } catch (Exception e) {
            Log.v(TAG, "tryParseReplyParcel failed: " + e.getMessage());
        }
    }

    /**
     * Extrai todas as strings legíveis de um Parcel e as registra como eventos descobertos.
     * Strings que parecem chaves Beantechs (padrão car.xxx.yyy) são priorizadas.
     */
    private void extractAllStrings(Parcel p, String source) {
        try {
            while (p.dataAvail() >= 4) {
                try {
                    String s = p.readString();
                    if (s != null && s.length() >= 4 && s.contains(".")) {
                        // Parece uma chave no padrão car.xxx.yyy
                        if (s.startsWith("car.") || s.startsWith("cmd.")) {
                            EngineReverseStateHolder.INSTANCE.onEventReceived(s, "[discovered]", source);
                        }
                    }
                } catch (Exception ignored) {
                    // Posição inválida no Parcel — pula 4 bytes e tenta novamente
                    if (p.dataAvail() >= 4) p.readInt();
                    else break;
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "extractAllStrings failed: " + e.getMessage());
        }
    }

    /**
     * Estratégia 5 — Active Probe.
     * Chama fetchDatas() em lotes para todos os candidatos.
     * Chaves que retornam valor não-nulo são reais → registra listener para elas.
     */
    private void runActiveProbe() {
        if (controlService == null) return;
        Log.w(TAG, "[S5] Iniciando probe ativo com " + PROBE_CANDIDATES.length + " candidatos...");
        EngineReverseStateHolder.INSTANCE.setConnected(true, "Probe ativo em andamento...");

        List<String> discovered = new ArrayList<>();
        int batchSize = 20;

        for (int i = 0; i < PROBE_CANDIDATES.length; i += batchSize) {
            if (controlService == null) break;
            String[] batch = Arrays.copyOfRange(PROBE_CANDIDATES, i,
                    Math.min(i + batchSize, PROBE_CANDIDATES.length));
            try {
                String[] values = controlService.fetchDatas(batch);
                if (values != null) {
                    for (int j = 0; j < batch.length && j < values.length; j++) {
                        if (values[j] != null && !values[j].isEmpty()) {
                            // Chave existe no serviço — registra e reporta
                            discovered.add(batch[j]);
                            EngineReverseStateHolder.INSTANCE.onEventReceived(
                                    batch[j], values[j], "probe");
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "[S5] Erro no lote " + i + ": " + e.getMessage());
            }
        }

        Log.w(TAG, "[S5] Probe concluído: " + discovered.size() + " chaves novas encontradas");

        if (!discovered.isEmpty() && controlService != null) {
            try {
                String[] keys = discovered.toArray(new String[0]);
                controlService.addListenerKey(getPackageName() + ".probe", keys);
                controlService.registerDataChangedListener(getPackageName() + ".probe", probeListener);
                Log.w(TAG, "[S5] Listener registrado para " + discovered.size() + " chaves descobertas");
            } catch (Exception e) {
                Log.w(TAG, "[S5] Erro ao registrar probe listener: " + e.getMessage());
            }
        }

        int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
        EngineReverseStateHolder.INSTANCE.setConnected(true,
                "Conectado — " + total + " chaves (" + discovered.size() + " via probe)");
    }

    // ── Estratégia 6 helpers ─────────────────────────────────────────

    /** Tamanho máximo de uma entrada ZIP a ser escaneada (4 MB descomprimido). */
    private static final long MAX_SCAN_ENTRY_BYTES = 4 * 1024 * 1024L;

    /**
     * Lê até MAX_SCAN_ENTRY_BYTES de um InputStream.
     * Retorna null se o entry for muito grande (evita OOM em hardware limitado).
     */
    private byte[] readBytesLimited(java.io.InputStream is, long uncompressedSize)
            throws java.io.IOException {
        if (uncompressedSize > MAX_SCAN_ENTRY_BYTES) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                uncompressedSize > 0 ? (int) uncompressedSize : 65536);
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            if (total > MAX_SCAN_ENTRY_BYTES) return null; // bail
        }
        return out.toByteArray();
    }

    /**
     * Varre um APK (ZIP) em busca de strings car.* / cmd.* dentro dos
     * arquivos DEX, .so e assets/ — que ficam COMPRIMIDOS no ZIP e por isso
     * não seriam encontrados com grep no binário bruto.
     *
     * Entradas maiores que MAX_SCAN_ENTRY_BYTES são ignoradas para não
     * causar OOM em hardware com RAM limitada.
     */
    private void scanZipForKeys(String apkPath, Pattern pat, Set<String> out) {
        try {
            ZipFile zip = new ZipFile(apkPath);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                boolean scan = name.matches("classes\\d*\\.dex")
                        || name.endsWith(".so")
                        || name.startsWith("assets/");
                if (!scan) continue;
                try (java.io.InputStream is = zip.getInputStream(entry)) {
                    byte[] bytes = readBytesLimited(is, entry.getSize());
                    if (bytes == null) {
                        Log.w(TAG, "[S6] Entrada muito grande, ignorada: " + name
                                + " (" + entry.getSize() / 1024 + " KB)");
                        continue;
                    }
                    // ISO-8859-1: mapeamento 1:1 byte→char — preserva bytes binários
                    String content = new String(bytes, StandardCharsets.ISO_8859_1);
                    bytes = null; // libera referência antes do regex
                    Matcher m = pat.matcher(content);
                    while (m.find()) {
                        String key = m.group();
                        // Exige ao menos dois pontos: car.categoria.chave
                        long dots = key.chars().filter(c -> c == '.').count();
                        if (dots >= 2 && !key.endsWith(".")) out.add(key);
                    }
                } catch (OutOfMemoryError oom) {
                    Log.e(TAG, "[S6] OOM ao processar " + name + " — pulando", oom);
                } catch (Exception e) {
                    Log.v(TAG, "[S6] Entrada ignorada " + name + ": " + e.getMessage());
                }
            }
            zip.close();
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "[S6] OOM ao abrir " + apkPath, oom);
        } catch (Exception e) {
            Log.w(TAG, "[S6] Erro ao abrir " + apkPath + ": " + e.getMessage());
        }
    }

    /**
     * Estratégia 6 — DEX String Scan.
     *
     * Correção em relação à versão anterior (que rodava grep no APK raw):
     * APK é um ZIP e o código DEX fica COMPRIMIDO — grep no binário não vê
     * nada dentro das classes comprimidas, por isso só achava 1 string.
     *
     * Esta versão usa ZipFile para descomprimir cada classes*.dex / .so em
     * memória e aplica regex no bytecode descomprimido. Também varre TODOS
     * os pacotes Beantechs instalados (não só intelligentvehiclecontrol).
     *
     * Fluxo:
     *   1. PackageManager → lista todos os pacotes que contêm "beantechs"
     *   2. Para cada APK: abre como ZipFile, varre DEX + .so + assets
     *   3. Regex car\.xxx\.yyy / cmd\.xxx\.yyy no bytecode descomprimido
     *   4. fetchDatas em lote → confirma chaves com valor real no serviço
     *   5. addListenerKey + registerDataChangedListener (source "apk-scan")
     */
    private void runApkStringScan() {
        try {
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S6: varrendo DEX de pacotes Beantechs...");

            // 1. Localiza todos os APKs de pacotes Beantechs
            Set<String> apkPaths = new HashSet<>();
            List<ApplicationInfo> apps = getPackageManager()
                    .getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo app : apps) {
                if (!app.packageName.toLowerCase().contains("beantechs")) continue;
                if (app.sourceDir != null) apkPaths.add(app.sourceDir);
                if (app.splitSourceDirs != null)
                    apkPaths.addAll(Arrays.asList(app.splitSourceDirs));
                Log.w(TAG, "[S6] Pacote encontrado: " + app.packageName);
            }

            if (apkPaths.isEmpty()) {
                Log.w(TAG, "[S6] Nenhum pacote Beantechs encontrado");
                return;
            }

            // 2. Extrai strings car.* / cmd.* dos DEX descomprimidos
            Pattern keyPat = Pattern.compile(
                    "(?:car|cmd)\\.[a-zA-Z_][a-zA-Z0-9_.]{2,60}");
            Set<String> allCandidates = new TreeSet<>();
            for (String apkPath : apkPaths) {
                Log.w(TAG, "[S6] Varrendo: " + apkPath);
                scanZipForKeys(apkPath, keyPat, allCandidates);
            }
            Log.w(TAG, "[S6] " + allCandidates.size() + " candidatos totais extraídos dos DEX");

            // 3. Remove os já conhecidos
            List<String> newCandidates = new ArrayList<>();
            for (String k : allCandidates) {
                if (!EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().containsKey(k))
                    newCandidates.add(k);
            }
            Log.w(TAG, "[S6] " + newCandidates.size() + " candidatos novos para testar");

            if (newCandidates.isEmpty() || controlService == null) {
                EngineReverseStateHolder.INSTANCE.setConnected(true,
                        "S6 concluído: sem chaves novas");
                return;
            }

            // 4. Confirma via fetchDatas em lotes de 20
            List<String> confirmed = new ArrayList<>();
            for (int i = 0; i < newCandidates.size(); i += 20) {
                if (controlService == null) break;
                List<String> batch = newCandidates.subList(i,
                        Math.min(i + 20, newCandidates.size()));
                try {
                    String[] vals = controlService.fetchDatas(batch.toArray(new String[0]));
                    if (vals != null) {
                        for (int j = 0; j < batch.size() && j < vals.length; j++) {
                            if (vals[j] != null && !vals[j].isEmpty()) {
                                confirmed.add(batch.get(j));
                                EngineReverseStateHolder.INSTANCE.onEventReceived(
                                        batch.get(j), vals[j], "apk-scan");
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "[S6] Erro lote fetch: " + e.getMessage());
                }
            }
            Log.w(TAG, "[S6] " + confirmed.size() + " chaves confirmadas via APK scan");

            // 5. Registra listener para as chaves confirmadas
            if (!confirmed.isEmpty() && controlService != null) {
                try {
                    controlService.addListenerKey(getPackageName() + ".apkscan",
                            confirmed.toArray(new String[0]));
                    controlService.registerDataChangedListener(
                            getPackageName() + ".apkscan", apkScanListener);
                    Log.w(TAG, "[S6] Listener apkscan registrado: " + confirmed.size() + " chaves");
                } catch (Exception e) {
                    Log.w(TAG, "[S6] Erro ao registrar listener: " + e.getMessage());
                }
            }

            int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "Conectado — " + total + " chaves (" + confirmed.size() + " via APK scan)");

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "[S6] OOM durante DEX scan — abortando", oom);
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S6 abortado: memória insuficiente");
        } catch (Exception e) {
            Log.e(TAG, "[S6] Erro no DEX scan: " + e.getMessage(), e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handlerThread = new HandlerThread("EngineReverseThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (isServiceRunning) {
            Log.w(TAG, "Service already running, skipping start.");
            return START_STICKY;
        }

        try {
            isServiceRunning = true;

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Haval Engine Reverse")
                    .setContentText("Monitorando eventos do barramento Beantechs")
                    .setSmallIcon(android.R.drawable.ic_notification_overlay)
                    .build();
            startForeground(NOTIFICATION_ID, notification);

            SharedPreferences prefs = App.getDeviceProtectedContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            boolean needsBootstrap = true;
            try {
                var selfInfo = getApplicationContext().getPackageManager()
                        .getApplicationInfo(getApplicationContext().getPackageName(), 0);
                if (selfInfo.uid > 10999) {
                    Log.w(TAG, "UID > 10999, skipping Shizuku bootstrap, waiting for existing binder...");
                    needsBootstrap = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get application info: " + e.getMessage(), e);
            }

            final String cachedLibLocation = prefs.getString(KEY_SHIZUKU_LIB, "");

            final Runnable timeoutRunnable = () -> {
                if (!isShizukuInitialized) {
                    Log.w(TAG, "Timeout waiting for Shizuku binder, restarting...");
                    restart();
                }
            };

            if (!needsBootstrap) {
                Shizuku.addBinderReceivedListenerSticky(this::onShizukuBinderReceived);
                backgroundHandler.postDelayed(timeoutRunnable, 10000);
            } else {
                backgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            TelnetClientWrapper telnetClient = new TelnetClientWrapper();
                            telnetClient.connect("127.0.0.1", 23);
                            String filePath = cachedLibLocation;
                            if (filePath.isEmpty()) {
                                filePath = telnetClient.executeCommand("find /data/app -name libshizuku.so");
                                if (filePath.isEmpty()) throw new RuntimeException("libshizuku.so not found");
                                prefs.edit().putString(KEY_SHIZUKU_LIB, filePath).apply();
                                Log.w(TAG, "libshizuku.so found at: " + filePath);
                            }

                            String result = telnetClient.executeCommand(filePath);
                            if (Pattern.compile("killed \\d+ \\(shizuku_server\\)").matcher(result).find()) {
                                Log.w(TAG, "Old Shizuku process killed, waiting 5s...");
                                Thread.sleep(5000);
                            }
                            telnetClient.disconnect();

                            Shizuku.addBinderReceivedListenerSticky(UniversalMonitorService.this::onShizukuBinderReceived);
                            backgroundHandler.postDelayed(timeoutRunnable, 5000);
                        } catch (Exception e) {
                            Log.e(TAG, "Error bootstrapping Shizuku: " + e.getMessage(), e);
                            backgroundHandler.postDelayed(this, 1000);
                        }
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand: " + e.getMessage(), e);
            isServiceRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    private synchronized void onShizukuBinderReceived() {
        if (!isServiceRunning) return;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Log.w(TAG, "Shizuku binder received");
        isShizukuInitialized = true;
        backgroundHandler.removeCallbacksAndMessages(null);
        checkAndInitialize();
    }

    private void checkAndInitialize() {
        if (!isShizukuInitialized) return;

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Requesting Shizuku permission...");
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode == 0 && grantResult == PackageManager.PERMISSION_GRANTED) {
                    checkAndInitialize();
                } else {
                    Log.e(TAG, "Shizuku permission denied");
                    EngineReverseStateHolder.INSTANCE.setConnected(false, "Shizuku: permissão negada");
                }
            });
            Shizuku.requestPermission(0);
            return;
        }

        try {
            IPTablesUtils.unlockInputOutputAll();
        } catch (Exception e) {
            Log.e(TAG, "Error unlocking iptables: " + e.getMessage(), e);
        }

        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    IPTablesUtils.unlockInputOutputAll();
                    backgroundHandler.postDelayed(this, 15000);
                } catch (Exception e) {
                    backgroundHandler.postDelayed(this, 5000);
                }
            }
        });

        if (!connectToVehicleService()) {
            Log.e(TAG, "Failed to connect to vehicle service, restarting...");
            restart();
            return;
        }

        IntentFilter filter = new IntentFilter("com.beantechs.intelligentvehiclecontrol.INIT_COMPLETED");
        ContextCompat.registerReceiver(App.getContext(), new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isServiceRunning) {
                    Log.w(TAG, "intelligentvehiclecontrol restarted, reconnecting...");
                    restart();
                } else {
                    checkAndInitialize();
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private boolean connectToVehicleService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                EngineReverseStateHolder.INSTANCE.setConnected(false, "Shizuku indisponível");
                return false;
            }

            IBinder rawBinder = getServiceBinder("com.beantechs.intelligentvehiclecontrol");
            // Envolve o binder com ShizukuBinderWrapper (permissões) + BinderProxy (interceptação)
            IBinder shizukuBinder = new ShizukuBinderWrapper(rawBinder);
            IBinder proxiedBinder = createBinderProxy(shizukuBinder);

            if (!shizukuBinder.pingBinder()) {
                Log.e(TAG, "IntelligentVehicleControlService binder not alive");
                EngineReverseStateHolder.INSTANCE.setConnected(false, "Binder Beantechs não responde");
                return false;
            }

            controlService = IIntelligentVehicleControlService.Stub.asInterface(proxiedBinder);

            // Estratégia 1: registra listener SEM addListenerKey
            // Hipótese: recebe TODOS os eventos sem filtro
            controlService.registerDataChangedListener(getPackageName() + ".nokey", vehicleDataListener);
            Log.w(TAG, "[S1] Listener registrado SEM addListenerKey");

            // Estratégia 3a: tenta addListenerKey com array vazio
            try {
                controlService.addListenerKey(getPackageName() + ".empty", new String[]{});
                controlService.registerDataChangedListener(getPackageName() + ".empty", emptyKeyListener);
                Log.w(TAG, "[S3a] Listener registrado com array vazio");
            } catch (Exception e) {
                Log.w(TAG, "[S3a] Falhou com array vazio: " + e.getMessage());
            }

            // Estratégia 3b: tenta addListenerKey com wildcard "*"
            try {
                controlService.addListenerKey(getPackageName() + ".wildcard", new String[]{"*"});
                controlService.registerDataChangedListener(getPackageName() + ".wildcard", wildcardListener);
                Log.w(TAG, "[S3b] Listener registrado com wildcard *");
            } catch (Exception e) {
                Log.w(TAG, "[S3b] Falhou com wildcard: " + e.getMessage());
            }

            // Carrega valores iniciais das chaves conhecidas (seed)
            String[] values = controlService.fetchDatas(KNOWN_PROPS);
            if (values != null) {
                for (int i = 0; i < KNOWN_PROPS.length && i < values.length; i++) {
                    if (values[i] != null) {
                        EngineReverseStateHolder.INSTANCE.onEventReceived(KNOWN_PROPS[i], values[i], "initial-fetch");
                    }
                }
            }

            Shizuku.addBinderDeadListener(this);
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "Conectado — " + EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size()
                    + " chaves iniciais — probe em 3s...");
            Log.w(TAG, "Conectado ao barramento Beantechs com sucesso");

            // Estratégia 5: probe ativo após estabilização
            backgroundHandler.postDelayed(() -> {
                runActiveProbe();
                // Estratégia 6: APK scan logo após o probe (probe demora ~1-2s)
                backgroundHandler.postDelayed(() -> runApkStringScan(), 5000);
            }, 3000);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error connecting to vehicle service: " + e.getMessage(), e);
            EngineReverseStateHolder.INSTANCE.setConnected(false, "Erro: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onBinderDead() {
        Log.w(TAG, "Shizuku binder died");
        isShizukuInitialized = false;
        EngineReverseStateHolder.INSTANCE.setConnected(false, "Shizuku desconectado");
        restart();
    }

    private void restart() {
        isServiceRunning = false;
        try {
            controlService = null;
        } catch (Exception ignored) {}

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent restartIntent = new Intent(this, RestartReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1,
                restartIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 2000, pi);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Engine Reverse Monitor", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Monitoramento de eventos do barramento Beantechs");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        if (handlerThread != null) handlerThread.quitSafely();
    }
}
