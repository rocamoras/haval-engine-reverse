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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
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
                    "Conectado — " + EngineReverseStateHolder.INSTANCE.getDiscoveredKeys().size() + " chaves iniciais");
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
        if (handlerThread != null) handlerThread.quitSafely();
    }
}
