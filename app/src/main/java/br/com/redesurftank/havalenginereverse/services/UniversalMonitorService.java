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

    public static final String ACTION_TRIGGER_PROBE      = "br.com.redesurftank.havalenginereverse.TRIGGER_PROBE";
    public static final String ACTION_TRIGGER_APK_SCAN   = "br.com.redesurftank.havalenginereverse.TRIGGER_APK_SCAN";
    public static final String ACTION_TRIGGER_DUMPSYS    = "br.com.redesurftank.havalenginereverse.TRIGGER_DUMPSYS";
    public static final String ACTION_TRIGGER_LOGCAT     = "br.com.redesurftank.havalenginereverse.TRIGGER_LOGCAT";
    public static final String ACTION_TRIGGER_SERVICES   = "br.com.redesurftank.havalenginereverse.TRIGGER_SERVICES";
    public static final String ACTION_TRIGGER_BRUTE      = "br.com.redesurftank.havalenginereverse.TRIGGER_BRUTE";
    public static final String ACTION_TRIGGER_DATA_FILES = "br.com.redesurftank.havalenginereverse.TRIGGER_DATA_FILES";

    /** Envia request(action, key, value) ao serviço Beantechs. */
    public static final String ACTION_SEND_REQUEST = "br.com.redesurftank.havalenginereverse.SEND_REQUEST";
    public static final String EXTRA_REQ_ACTION    = "req_action";
    public static final String EXTRA_REQ_KEY       = "req_key";
    public static final String EXTRA_REQ_VALUE     = "req_value";

    /** Ações: verificar e alternar estado do pacote Speech. */
    public static final String ACTION_CHECK_SPEECH_PACKAGE  = "br.com.redesurftank.havalenginereverse.CHECK_SPEECH_PACKAGE";
    public static final String ACTION_TOGGLE_SPEECH_PACKAGE = "br.com.redesurftank.havalenginereverse.TOGGLE_SPEECH_PACKAGE";

    /** Trilho 1 — espelha o sensor real de temperatura externa numa chave que a tela OEM lê. */
    public static final String ACTION_SET_TEMP_MIRROR      = "br.com.redesurftank.havalenginereverse.SET_TEMP_MIRROR";
    public static final String EXTRA_MIRROR_ENABLED        = "mirror_enabled";
    public static final String EXTRA_MIRROR_TARGET_KEY     = "mirror_target_key";
    /** Escreve um valor de teste numa chave (probe) — usado para descobrir qual chave a tela lê. */
    public static final String ACTION_PROBE_TEMP_KEY       = "br.com.redesurftank.havalenginereverse.PROBE_TEMP_KEY";

    /** Trilho 2 — copia os APKs OEM (weatherservice/launcher/systemui) para pasta acessível. */
    public static final String ACTION_EXPORT_OEM_APKS      = "br.com.redesurftank.havalenginereverse.EXPORT_OEM_APKS";

    private static final String SPEECH_PACKAGE = "com.iflytek.cutefly.speechclient.hmi";

    private static final String OUTSIDE_TEMP_SENSOR_KEY   = "car.basic.outside_temp";
    private static final String DEFAULT_MIRROR_TARGET_KEY = "car.configure.outside_temp_display";
    private static final String PREF_MIRROR_ENABLED        = "mirror_temp_enabled";
    private static final String PREF_MIRROR_TARGET         = "mirror_temp_target";
    /** Reaplica o valor periodicamente para sobreviver a sobrescritas do OEM. */
    private static final long   MIRROR_REAPPLY_INTERVAL_MS = 30_000L;

    // Fonte da verdade do espelhamento (estática p/ ser acessível a partir do StateHolder).
    private static volatile UniversalMonitorService sInstance;
    private static volatile boolean sMirrorActive = false;
    private static volatile String  sMirrorTargetKey = DEFAULT_MIRROR_TARGET_KEY;
    private volatile String lastOutsideTemp = null;

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
        if (EngineReverseStateHolder.INSTANCE.getProbeRunning()) {
            Log.w(TAG, "[S5] Probe já em andamento, ignorado.");
            return;
        }
        EngineReverseStateHolder.INSTANCE.setProbeRunning(true);
        Log.w(TAG, "[S5] Iniciando probe ativo com " + PROBE_CANDIDATES.length + " candidatos...");
        EngineReverseStateHolder.INSTANCE.setConnected(true, "S5: probe ativo em andamento...");

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
        EngineReverseStateHolder.INSTANCE.setProbeRunning(false);
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
        if (EngineReverseStateHolder.INSTANCE.getApkScanRunning()) {
            Log.w(TAG, "[S6] APK scan já em andamento, ignorado.");
            return;
        }
        EngineReverseStateHolder.INSTANCE.setApkScanRunning(true);
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
        } finally {
            EngineReverseStateHolder.INSTANCE.setApkScanRunning(false);
        }
    }

    // ── Listeners S7-S11 ─────────────────────────────────────────────

    private final IListener dumpsysListener = new IListener.Stub() {
        @Override public void onDataChanged(String k, String v) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(k, v, "dumpsys"); }
    };
    private final IListener logcatListener = new IListener.Stub() {
        @Override public void onDataChanged(String k, String v) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(k, v, "logcat"); }
    };
    private final IListener servicesListener = new IListener.Stub() {
        @Override public void onDataChanged(String k, String v) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(k, v, "services"); }
    };
    private final IListener bruteListener = new IListener.Stub() {
        @Override public void onDataChanged(String k, String v) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(k, v, "brute"); }
    };
    private final IListener dataFilesListener = new IListener.Stub() {
        @Override public void onDataChanged(String k, String v) {
            EngineReverseStateHolder.INSTANCE.onEventReceived(k, v, "data-files"); }
    };

    // ── Helpers compartilhados ────────────────────────────────────────

    /**
     * Executa um comando shell via telnet (Shizuku), gravando a saída em
     * arquivo temporário para suportar outputs grandes sem timeout.
     * Retorna string vazia em caso de erro.
     */
    private String runShell(String cmd, long timeoutMs) {
        TelnetClientWrapper telnet = null;
        try {
            telnet = new TelnetClientWrapper();
            telnet.connect("127.0.0.1", 23);
            String tmp = "/data/local/tmp/bk_out.txt";
            telnet.executeCommand(cmd + " > " + tmp + " 2>/dev/null; echo ok", timeoutMs);
            String result = telnet.executeCommand("cat " + tmp + " 2>/dev/null", 10000);
            telnet.executeCommand("rm " + tmp + " 2>/dev/null");
            return result;
        } catch (Exception e) {
            Log.w(TAG, "runShell failed: " + e.getMessage());
            return "";
        } finally {
            if (telnet != null) try { telnet.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** Extrai chaves no padrão car.x.y ou cmd.x.y de qualquer texto. */
    private List<String> extractKeys(String text) {
        Pattern pat = Pattern.compile("(?:car|cmd)\\.[a-zA-Z_][a-zA-Z0-9_.]{2,60}");
        Set<String> found = new TreeSet<>();
        Matcher m = pat.matcher(text);
        while (m.find()) {
            String k = m.group();
            if (k.chars().filter(c -> c == '.').count() >= 2 && !k.endsWith("."))
                found.add(k);
        }
        return new ArrayList<>(found);
    }

    /**
     * Filtra candidatos não conhecidos, confirma via fetchDatas em lote,
     * reporta os confirmados e registra listener para receber mudanças.
     */
    private List<String> probeAndRegister(List<String> candidates,
            String pkgSuffix, String source, IListener listener) {
        List<String> newOnly = new ArrayList<>();
        for (String k : candidates) {
            if (!EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().containsKey(k))
                newOnly.add(k);
        }
        if (newOnly.isEmpty() || controlService == null) return new ArrayList<>();

        List<String> confirmed = new ArrayList<>();
        for (int i = 0; i < newOnly.size(); i += 20) {
            if (controlService == null) break;
            List<String> batch = newOnly.subList(i, Math.min(i + 20, newOnly.size()));
            try {
                String[] vals = controlService.fetchDatas(batch.toArray(new String[0]));
                if (vals != null) {
                    for (int j = 0; j < batch.size() && j < vals.length; j++) {
                        if (vals[j] != null && !vals[j].isEmpty()) {
                            confirmed.add(batch.get(j));
                            EngineReverseStateHolder.INSTANCE.onEventReceived(
                                    batch.get(j), vals[j], source);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "[" + pkgSuffix + "] batch fetch: " + e.getMessage());
            }
        }
        if (!confirmed.isEmpty() && controlService != null) {
            try {
                controlService.addListenerKey(getPackageName() + pkgSuffix,
                        confirmed.toArray(new String[0]));
                controlService.registerDataChangedListener(
                        getPackageName() + pkgSuffix, listener);
            } catch (Exception e) {
                Log.w(TAG, "[" + pkgSuffix + "] listener reg: " + e.getMessage());
            }
        }
        return confirmed;
    }

    // ── Estratégia 7 — Dumpsys ───────────────────────────────────────

    /**
     * Chama `dumpsys com.beantechs.intelligentvehiclecontrol`.
     * Se o serviço implementar dump(), o output contém estado interno completo
     * com pares chave=valor. Extrai os pares direto e proba o restante.
     */
    private void runDumpsys() {
        if (EngineReverseStateHolder.INSTANCE.getDumpsysRunning()) return;
        EngineReverseStateHolder.INSTANCE.setDumpsysRunning(true);
        try {
            EngineReverseStateHolder.INSTANCE.setConnected(true, "S7: dumpsys...");
            String out = runShell("dumpsys com.beantechs.intelligentvehiclecontrol", 15000);
            if (out.isEmpty()) {
                EngineReverseStateHolder.INSTANCE.setConnected(true, "S7: sem saída do dumpsys");
                return;
            }
            // Extrai pares explícitos key=value / key: value
            Pattern kvPat = Pattern.compile(
                    "((?:car|cmd)\\.[a-zA-Z_][a-zA-Z0-9_.]{2,60})\\s*[=:]\\s*([^\\n\\r]{1,120})");
            Set<String> kvKeys = new HashSet<>();
            Matcher kv = kvPat.matcher(out);
            while (kv.find()) {
                String key = kv.group(1);
                String val = kv.group(2).trim();
                EngineReverseStateHolder.INSTANCE.onEventReceived(key, val, "dumpsys");
                kvKeys.add(key);
            }
            // Proba chaves mencionadas sem valor explícito
            List<String> rest = extractKeys(out);
            rest.removeAll(kvKeys);
            List<String> confirmed = probeAndRegister(rest, ".dumpsys", "dumpsys", dumpsysListener);
            int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S7 ok — " + total + " chaves (" + (kvKeys.size() + confirmed.size()) + " via dumpsys)");
        } catch (Exception e) {
            Log.e(TAG, "[S7] " + e.getMessage(), e);
        } finally {
            EngineReverseStateHolder.INSTANCE.setDumpsysRunning(false);
        }
    }

    // ── Estratégia 8 — Logcat Scan ───────────────────────────────────

    /**
     * Varre os logs recentes (logcat -d) em busca de qualquer menção a car.*
     * O serviço Beantechs frequentemente loga chaves ao processar eventos CAN.
     * Quanto mais o usuário interagir com o painel antes de apertar o botão,
     * mais chaves aparecem no log.
     */
    private void runLogcatScan() {
        if (EngineReverseStateHolder.INSTANCE.getLogcatRunning()) return;
        EngineReverseStateHolder.INSTANCE.setLogcatRunning(true);
        try {
            EngineReverseStateHolder.INSTANCE.setConnected(true, "S8: varrendo logcat...");
            String out = runShell(
                "logcat -d 2>/dev/null | grep -o 'car\\.[a-zA-Z_][a-zA-Z0-9_.]*' | sort -u",
                20000);
            if (out.isEmpty()) {
                EngineReverseStateHolder.INSTANCE.setConnected(true, "S8: nada no logcat");
                return;
            }
            List<String> candidates = extractKeys(out);
            List<String> confirmed = probeAndRegister(candidates, ".logcat", "logcat", logcatListener);
            int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S8 ok — " + total + " chaves (" + confirmed.size() + " via logcat)");
        } catch (Exception e) {
            Log.e(TAG, "[S8] " + e.getMessage(), e);
        } finally {
            EngineReverseStateHolder.INSTANCE.setLogcatRunning(false);
        }
    }

    // ── Estratégia 9 — Enumeração de serviços ────────────────────────

    /**
     * Lista todos os serviços registrados no ServiceManager e tenta conectar
     * aos que contêm "beantechs" no nome (exceto o que já estamos conectados).
     * Para cada serviço encontrado, tenta usar a mesma interface AIDL e faz
     * fetchDatas com os candidatos conhecidos.
     */
    private void runServiceEnum() {
        if (EngineReverseStateHolder.INSTANCE.getServicesRunning()) return;
        EngineReverseStateHolder.INSTANCE.setServicesRunning(true);
        try {
            EngineReverseStateHolder.INSTANCE.setConnected(true, "S9: enumerando serviços...");
            String out = runShell("service list 2>/dev/null | grep -i beantechs", 8000);
            if (out.isEmpty()) {
                EngineReverseStateHolder.INSTANCE.setConnected(true, "S9: nenhum serviço extra encontrado");
                return;
            }
            int newKeys = 0;
            for (String line : out.split("\n")) {
                // Formato: "N  nome.do.servico: [interface]"
                String svcName = line.replaceAll("^\\d+\\s+", "").replaceAll(":.*", "").trim();
                if (svcName.isEmpty() || svcName.equals("com.beantechs.intelligentvehiclecontrol"))
                    continue;
                Log.w(TAG, "[S9] Tentando serviço: " + svcName);
                try {
                    IBinder raw = getServiceBinder(svcName);
                    IBinder wrapped = new ShizukuBinderWrapper(raw);
                    if (!wrapped.pingBinder()) continue;
                    IIntelligentVehicleControlService svc =
                            IIntelligentVehicleControlService.Stub.asInterface(wrapped);
                    // Testa com todos os candidatos conhecidos
                    List<String> allCandidates = new ArrayList<>(Arrays.asList(KNOWN_PROPS));
                    allCandidates.addAll(Arrays.asList(PROBE_CANDIDATES));
                    for (int i = 0; i < allCandidates.size(); i += 20) {
                        List<String> batch = allCandidates.subList(i,
                                Math.min(i + 20, allCandidates.size()));
                        try {
                            String[] vals = svc.fetchDatas(batch.toArray(new String[0]));
                            if (vals != null) {
                                for (int j = 0; j < batch.size() && j < vals.length; j++) {
                                    if (vals[j] != null && !vals[j].isEmpty()) {
                                        EngineReverseStateHolder.INSTANCE.onEventReceived(
                                                batch.get(j), vals[j], "services:" + svcName);
                                        newKeys++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    Log.w(TAG, "[S9] " + svcName + " falhou: " + e.getMessage());
                }
            }
            int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S9 ok — " + total + " chaves (" + newKeys + " via serviços extras)");
        } catch (Exception e) {
            Log.e(TAG, "[S9] " + e.getMessage(), e);
        } finally {
            EngineReverseStateHolder.INSTANCE.setServicesRunning(false);
        }
    }

    // ── Estratégia 10 — Brute force de transaction codes ────────────

    /**
     * Tenta transaction codes além dos 6 conhecidos (7 a 20).
     * Analisa os bytes de cada resposta em busca de strings car.*.
     * Pode revelar métodos não documentados como listAllKeys(), getPropertyCount(), etc.
     */
    private void runTransactBrute() {
        if (EngineReverseStateHolder.INSTANCE.getBruteRunning()) return;
        EngineReverseStateHolder.INSTANCE.setBruteRunning(true);
        try {
            EngineReverseStateHolder.INSTANCE.setConnected(true, "S10: brute force de códigos...");
            IBinder binder = controlService.asBinder();
            String descriptor = binder.getInterfaceDescriptor();
            Set<String> found = new TreeSet<>();

            for (int code = IBinder.FIRST_CALL_TRANSACTION + 6;
                 code <= IBinder.FIRST_CALL_TRANSACTION + 20; code++) {
                // Variação 1: só o token de interface
                tryTransact(binder, descriptor, code, null, found);
                // Variação 2: token + nome do package
                tryTransact(binder, descriptor, code, getPackageName(), found);
                // Variação 3: token + prefixo "car."
                tryTransact(binder, descriptor, code, "car.", found);
            }
            List<String> confirmed = probeAndRegister(new ArrayList<>(found), ".brute", "brute", bruteListener);
            int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S10 ok — " + total + " chaves (" + confirmed.size() + " via brute)");
        } catch (Exception e) {
            Log.e(TAG, "[S10] " + e.getMessage(), e);
        } finally {
            EngineReverseStateHolder.INSTANCE.setBruteRunning(false);
        }
    }

    private void tryTransact(IBinder binder, String descriptor, int code,
                              String extraStr, Set<String> out) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (extraStr != null) data.writeString(extraStr);
            binder.transact(code, data, reply, 0);
            byte[] bytes = reply.marshall();
            if (bytes != null && bytes.length > 0) {
                String content = new String(bytes, StandardCharsets.ISO_8859_1);
                out.addAll(extractKeys(content));
            }
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── Estratégia 11 — Arquivos de dados do app ─────────────────────

    /**
     * Lê SharedPreferences e arquivos de assets do Beantechs em busca de
     * definições de chaves. SharedPreferences XML frequentemente contém
     * listas de propriedades configuradas no sistema.
     */
    private void runDataFilesScan() {
        if (EngineReverseStateHolder.INSTANCE.getDataFilesRunning()) return;
        EngineReverseStateHolder.INSTANCE.setDataFilesRunning(true);
        try {
            EngineReverseStateHolder.INSTANCE.setConnected(true, "S11: lendo arquivos de dados...");
            String dataDir = "/data/data/com.beantechs.intelligentvehiclecontrol";
            // Lê SharedPreferences XML
            String prefs = runShell("cat " + dataDir + "/shared_prefs/*.xml 2>/dev/null", 15000);
            // Lê arquivos em databases (dump strings de SQLite)
            String db = runShell(
                "find " + dataDir + "/databases -type f 2>/dev/null | " +
                "xargs grep -oa 'car\\.[a-zA-Z_][a-zA-Z0-9_.]*' 2>/dev/null | sort -u",
                20000);
            // Lê assets dentro do APK (já extraído pelo S6, mas aqui via shell)
            String assets = runShell(
                "find " + dataDir + " -name '*.json' -o -name '*.xml' -o -name '*.conf' " +
                "2>/dev/null | xargs cat 2>/dev/null | grep -o 'car\\.[a-zA-Z_][a-zA-Z0-9_.]*' | sort -u",
                15000);

            String combined = prefs + "\n" + db + "\n" + assets;
            if (combined.trim().isEmpty()) {
                EngineReverseStateHolder.INSTANCE.setConnected(true, "S11: sem arquivos acessíveis");
                return;
            }
            List<String> candidates = extractKeys(combined);
            List<String> confirmed = probeAndRegister(candidates, ".datafiles", "data-files", dataFilesListener);
            int total = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size();
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "S11 ok — " + total + " chaves (" + confirmed.size() + " via arquivos)");
        } catch (Exception e) {
            Log.e(TAG, "[S11] " + e.getMessage(), e);
        } finally {
            EngineReverseStateHolder.INSTANCE.setDataFilesRunning(false);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
        handlerThread = new HandlerThread("EngineReverseThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());

        // Restaura config de espelhamento persistida (sobrevive a reboot/OTA via BootReceiver).
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sMirrorActive = p.getBoolean(PREF_MIRROR_ENABLED, false);
        sMirrorTargetKey = p.getString(PREF_MIRROR_TARGET, DEFAULT_MIRROR_TARGET_KEY);
        EngineReverseStateHolder.INSTANCE.persistMirrorConfig(sMirrorActive, sMirrorTargetKey);
        if (sMirrorActive) {
            EngineReverseStateHolder.INSTANCE.setMirrorTempStatus("Espelhamento ligado — aguardando sensor…");
            backgroundHandler.postDelayed(mirrorReapplyRunnable, MIRROR_REAPPLY_INTERVAL_MS);
        }
    }

    /**
     * Chamado pelo StateHolder a cada novo valor do sensor real de temperatura externa.
     * Guarda o último valor e, se o espelhamento estiver ligado, escreve na chave-alvo.
     */
    public static void onOutsideTempSensorChanged(String value) {
        UniversalMonitorService s = sInstance;
        if (s == null || value == null || value.isEmpty()) return;
        s.lastOutsideTemp = value;
        if (sMirrorActive) s.applyMirrorWrite(value);
    }

    /** Escreve o valor na chave-alvo via AIDL (mesmo caminho de cmd.common.request.set). */
    private void applyMirrorWrite(String value) {
        if (backgroundHandler == null) return;
        backgroundHandler.post(() -> {
            try {
                if (controlService == null) {
                    EngineReverseStateHolder.INSTANCE.setMirrorTempStatus("Sem conexão — não aplicado");
                    return;
                }
                controlService.request("cmd.common.request.set", sMirrorTargetKey, value);
                EngineReverseStateHolder.INSTANCE.onEventReceived(sMirrorTargetKey, value, "mirror");
                EngineReverseStateHolder.INSTANCE.setMirrorTempStatus(
                        "Aplicado: " + sMirrorTargetKey + " = " + value + "°");
                Log.w(TAG, "[mirror] " + sMirrorTargetKey + " = " + value);
            } catch (Exception e) {
                Log.e(TAG, "[mirror] falhou: " + e.getMessage(), e);
                EngineReverseStateHolder.INSTANCE.setMirrorTempStatus("Erro: " + e.getMessage());
            }
        });
    }

    /** Reaplica o último valor conhecido a cada intervalo — enquanto o espelhamento estiver ligado. */
    private final Runnable mirrorReapplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sMirrorActive) return;
            String v = lastOutsideTemp;
            if (v == null) {
                // Ainda não recebeu evento? tenta o valor já descoberto na grid.
                v = EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().get(OUTSIDE_TEMP_SENSOR_KEY);
            }
            if (v != null && !v.isEmpty()) applyMirrorWrite(v);
            if (backgroundHandler != null) backgroundHandler.postDelayed(this, MIRROR_REAPPLY_INTERVAL_MS);
        }
    };

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        // Trata intents de disparo manual das estratégias de busca
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TRIGGER_PROBE.equals(action)) {
                if (controlService != null) {
                    backgroundHandler.post(this::runActiveProbe);
                } else {
                    EngineReverseStateHolder.INSTANCE.setConnected(false,
                            "Probe: serviço não conectado ainda");
                }
                return START_STICKY;
            }
            if (ACTION_TRIGGER_APK_SCAN.equals(action)) {
                if (controlService != null) backgroundHandler.post(this::runApkStringScan);
                else EngineReverseStateHolder.INSTANCE.setConnected(false, "APK Scan: não conectado");
                return START_STICKY;
            }
            if (ACTION_TRIGGER_DUMPSYS.equals(action)) {
                backgroundHandler.post(this::runDumpsys);
                return START_STICKY;
            }
            if (ACTION_TRIGGER_LOGCAT.equals(action)) {
                backgroundHandler.post(this::runLogcatScan);
                return START_STICKY;
            }
            if (ACTION_TRIGGER_SERVICES.equals(action)) {
                backgroundHandler.post(this::runServiceEnum);
                return START_STICKY;
            }
            if (ACTION_TRIGGER_BRUTE.equals(action)) {
                if (controlService != null) backgroundHandler.post(this::runTransactBrute);
                else EngineReverseStateHolder.INSTANCE.setConnected(false, "Brute: não conectado");
                return START_STICKY;
            }
            if (ACTION_TRIGGER_DATA_FILES.equals(action)) {
                backgroundHandler.post(this::runDataFilesScan);
                return START_STICKY;
            }
            if (ACTION_SEND_REQUEST.equals(action)) {
                String reqAction = intent.getStringExtra(EXTRA_REQ_ACTION);
                String reqKey    = intent.getStringExtra(EXTRA_REQ_KEY);
                String reqValue  = intent.getStringExtra(EXTRA_REQ_VALUE);
                if (reqAction == null) reqAction = "cmd.common.request.set";
                if (reqKey != null && !reqKey.isEmpty()) {
                    final String fa = reqAction;
                    final String fk = reqKey;
                    final String fv = reqValue != null ? reqValue : "";
                    backgroundHandler.post(() -> {
                        try {
                            if (controlService == null) {
                                EngineReverseStateHolder.INSTANCE.setConnected(false,
                                        "request: serviço não conectado");
                                return;
                            }
                            controlService.request(fa, fk, fv);
                            // Atualiza o valor local imediatamente para feedback visual
                            EngineReverseStateHolder.INSTANCE.onEventReceived(fk, fv, "request");
                            Log.w(TAG, "[request] " + fa + " " + fk + "=" + fv + " → ok");
                            EngineReverseStateHolder.INSTANCE.setConnected(true,
                                    "request ok: " + fk + " = " + fv);
                        } catch (Exception e) {
                            Log.e(TAG, "[request] falhou: " + e.getMessage(), e);
                            EngineReverseStateHolder.INSTANCE.setConnected(
                                    controlService != null,
                                    "Erro ao enviar: " + e.getMessage());
                        }
                    });
                }
                return START_STICKY;
            }
            if (ACTION_CHECK_SPEECH_PACKAGE.equals(action)) {
                backgroundHandler.post(this::checkSpeechPackage);
                return START_STICKY;
            }
            if (ACTION_TOGGLE_SPEECH_PACKAGE.equals(action)) {
                backgroundHandler.post(this::toggleSpeechPackage);
                return START_STICKY;
            }
            if (ACTION_SET_TEMP_MIRROR.equals(action)) {
                boolean enabled = intent.getBooleanExtra(EXTRA_MIRROR_ENABLED, false);
                String target = intent.getStringExtra(EXTRA_MIRROR_TARGET_KEY);
                if (target != null && !target.isEmpty()) sMirrorTargetKey = target;
                sMirrorActive = enabled;
                SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                p.edit().putBoolean(PREF_MIRROR_ENABLED, enabled)
                        .putString(PREF_MIRROR_TARGET, sMirrorTargetKey).apply();
                EngineReverseStateHolder.INSTANCE.persistMirrorConfig(enabled, sMirrorTargetKey);
                backgroundHandler.removeCallbacks(mirrorReapplyRunnable);
                if (enabled) {
                    if (lastOutsideTemp == null) {
                        lastOutsideTemp = EngineReverseStateHolder.INSTANCE
                                .getDiscoveredKeys().get(OUTSIDE_TEMP_SENSOR_KEY);
                    }
                    if (lastOutsideTemp != null) applyMirrorWrite(lastOutsideTemp);
                    else EngineReverseStateHolder.INSTANCE
                            .setMirrorTempStatus("Ligado — aguardando 1ª leitura do sensor…");
                    backgroundHandler.postDelayed(mirrorReapplyRunnable, MIRROR_REAPPLY_INTERVAL_MS);
                } else {
                    EngineReverseStateHolder.INSTANCE.setMirrorTempStatus("Espelhamento desligado");
                }
                return START_STICKY;
            }
            if (ACTION_PROBE_TEMP_KEY.equals(action)) {
                String key = intent.getStringExtra(EXTRA_REQ_KEY);
                String value = intent.getStringExtra(EXTRA_REQ_VALUE);
                if (key != null && !key.isEmpty()) {
                    final String fk = key;
                    final String fv = value != null ? value : "";
                    backgroundHandler.post(() -> {
                        try {
                            if (controlService == null) {
                                EngineReverseStateHolder.INSTANCE
                                        .setMirrorTempStatus("Probe: sem conexão");
                                return;
                            }
                            controlService.request("cmd.common.request.set", fk, fv);
                            EngineReverseStateHolder.INSTANCE.onEventReceived(fk, fv, "probe-temp");
                            EngineReverseStateHolder.INSTANCE
                                    .setMirrorTempStatus("Probe enviado: " + fk + " = " + fv
                                            + " — veja se a tela mudou");
                            Log.w(TAG, "[probe-temp] " + fk + " = " + fv);
                        } catch (Exception e) {
                            EngineReverseStateHolder.INSTANCE
                                    .setMirrorTempStatus("Probe erro: " + e.getMessage());
                        }
                    });
                }
                return START_STICKY;
            }
            if (ACTION_EXPORT_OEM_APKS.equals(action)) {
                backgroundHandler.post(this::runExportOemApks);
                return START_STICKY;
            }
        }

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

            Shizuku.addBinderDeadListener(this);
            EngineReverseStateHolder.INSTANCE.setConnected(true,
                    "Conectado — " + EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size()
                    + " chaves — use os botões para buscar mais");
            Log.w(TAG, "Conectado ao barramento Beantechs com sucesso");
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
        if (backgroundHandler != null) backgroundHandler.removeCallbacks(mirrorReapplyRunnable);
        if (sInstance == this) sInstance = null;
        if (handlerThread != null) handlerThread.quitSafely();
    }

    // ── Ações: pacote Speech ──────────────────────────────────────────────

    private void checkSpeechPackage() {
        EngineReverseStateHolder.INSTANCE.setSpeechPackageLoading(true);
        try {
            // pm list packages -d lista apenas pacotes desabilitados
            String out = runShell("pm list packages -d 2>/dev/null | grep " + SPEECH_PACKAGE, 8000);
            boolean disabled = out.contains(SPEECH_PACKAGE);
            EngineReverseStateHolder.INSTANCE.setSpeechPackageState(!disabled);
            Log.w(TAG, "[speech] status: " + (!disabled ? "ativo" : "desativado"));
        } catch (Exception e) {
            Log.e(TAG, "[speech] checkSpeechPackage falhou: " + e.getMessage(), e);
            EngineReverseStateHolder.INSTANCE.setSpeechPackageState(null);
        } finally {
            EngineReverseStateHolder.INSTANCE.setSpeechPackageLoading(false);
        }
    }

    private void toggleSpeechPackage() {
        EngineReverseStateHolder.INSTANCE.setSpeechPackageLoading(true);
        try {
            Boolean current = EngineReverseStateHolder.INSTANCE.getSpeechPackageEnabled();
            if (current == null) {
                checkSpeechPackage();
                return;
            }
            String cmd = current
                    ? "pm disable-user --user 0 " + SPEECH_PACKAGE
                    : "pm enable --user 0 " + SPEECH_PACKAGE;
            runShell(cmd, 8000);
            Log.w(TAG, "[speech] toggle → " + cmd);
            // Re-verifica o estado real após o comando
            checkSpeechPackage();
        } catch (Exception e) {
            Log.e(TAG, "[speech] toggleSpeechPackage falhou: " + e.getMessage(), e);
            EngineReverseStateHolder.INSTANCE.setSpeechPackageLoading(false);
        }
    }

    // ── Trilho 2: exportar APKs OEM para engenharia reversa ───────────────

    private void runExportOemApks() {
        if (EngineReverseStateHolder.INSTANCE.getOemApkExportRunning()) return;
        EngineReverseStateHolder.INSTANCE.setOemApkExportRunning(true);
        EngineReverseStateHolder.INSTANCE.setOemApkExportResult("Procurando pacotes OEM…");
        String destDir = "/sdcard/Download/haval-oem-apks";
        StringBuilder report = new StringBuilder();
        try {
            // Alvos explícitos + descoberta por palavra-chave (nome do pacote).
            Set<String> targets = new TreeSet<>(Arrays.asList(
                    "com.beantechs.weatherservice",
                    "com.beantechs.launcher",
                    "com.android.systemui"));
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            String[] kw = {"weather", "launcher", "systemui", "statusbar", "desktop", "hmi"};
            for (ApplicationInfo app : apps) {
                String n = app.packageName.toLowerCase();
                for (String k : kw) { if (n.contains(k)) { targets.add(app.packageName); break; } }
            }

            runShell("mkdir -p " + destDir + " && chmod 777 " + destDir, 8000);
            int ok = 0;
            for (String pkg : targets) {
                ApplicationInfo ai;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (Exception notInstalled) {
                    continue; // pacote não existe neste head unit
                }
                List<String> srcs = new ArrayList<>();
                if (ai.sourceDir != null) srcs.add(ai.sourceDir);
                if (ai.splitSourceDirs != null) srcs.addAll(Arrays.asList(ai.splitSourceDirs));
                for (int i = 0; i < srcs.size(); i++) {
                    String src = srcs.get(i);
                    String outName = pkg + (i == 0 ? ".apk" : "_split" + i + ".apk");
                    String dst = destDir + "/" + outName;
                    runShell("cp -f '" + src + "' '" + dst + "' && chmod 644 '" + dst + "'", 20000);
                    String ls = runShell("ls -la '" + dst + "' 2>/dev/null", 6000).trim();
                    if (!ls.isEmpty()) {
                        ok++;
                        report.append("✓ ").append(outName).append('\n')
                              .append("   ").append(ls).append('\n');
                    } else {
                        report.append("✗ falhou: ").append(pkg).append(" (").append(src).append(")\n");
                    }
                }
            }
            String header = ok + " APK(s) copiado(s) para:\n" + destDir + "\n\n" +
                    "Puxe com:\n  adb pull " + destDir + "\n\n";
            EngineReverseStateHolder.INSTANCE.setOemApkExportResult(header + report);
            Log.w(TAG, "[export-apks] " + ok + " apks → " + destDir);
        } catch (Exception e) {
            Log.e(TAG, "[export-apks] erro: " + e.getMessage(), e);
            EngineReverseStateHolder.INSTANCE.setOemApkExportResult(
                    "Erro: " + e.getMessage() + "\n" + report);
        } finally {
            EngineReverseStateHolder.INSTANCE.setOemApkExportRunning(false);
        }
    }
}
