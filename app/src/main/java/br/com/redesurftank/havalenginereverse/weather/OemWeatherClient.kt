package br.com.redesurftank.havalenginereverse.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.beantechs.weatherservice.remote.CityInfoBean
import com.beantechs.weatherservice.remote.IInterfaceAsBinder
import com.beantechs.weatherservice.remote.IWeatherCallBackListener
import com.beantechs.weatherservice.remote.IWeatherController
import org.json.JSONObject

/**
 * Cliente do serviço de clima OEM (com.beantechs.weatherservice).
 *
 * Descoberto por engenharia reversa do launcher OEM (com.beantechs.launcher).
 * A tela da home NÃO lê a temperatura de uma car-property; ela faz bind neste
 * serviço AIDL e recebe o JSON do clima. Este cliente replica exatamente o
 * fluxo do `BeanWeatherController`/`WeatherController` do launcher.
 *
 * Fluxo:
 *   1. bindService(action="com.beantechs.weatherservice.IWeatherController",
 *                   package="com.beantechs.weatherservice")
 *   2. onServiceConnected -> IWeatherController.Stub.asInterface(binder)
 *   3. registerCallbackListener(packId, listener)
 *   4. syncWeather(packId)  ou  syncNowWeatherByLoc(packId, cityCode)
 *   5. callback onNowWeather(json) -> parse data.now.tmp  (°C)
 *
 * `packId` = "<packageName>||<pid>" (token do chamador, exatamente como o OEM monta).
 */
class OemWeatherClient(private val context: Context) {

    companion object {
        private const val TAG = "OemWeatherClient"
        const val REMOTE_ACTION = "com.beantechs.weatherservice.IWeatherController"
        const val REMOTE_PACKAGE = "com.beantechs.weatherservice"
    }

    /** Temperatura externa em °C (string do OEM, ex.: "24"). Null até o 1º callback. */
    @Volatile var currentTempC: String? = null
        private set

    /** Callback opcional para a UI: (tempC, condCode, condTxt, jsonCru). */
    var onNow: ((tmp: String?, condCode: String?, condTxt: String?, rawJson: String?) -> Unit)? = null

    /** Status/eventos p/ a UI: (conectado, mensagem). Chamado em thread de binder — marshale p/ main. */
    var onStatus: ((connected: Boolean, msg: String) -> Unit)? = null

    private val packId: String = "${context.packageName}||${Process.myPid()}"
    private var controller: IWeatherController? = null
    private var bound = false

    /** Token de binder vazio, igual ao `mIInterfaceAsBinder` do OEM (usado em register()). */
    private val asBinder = object : IInterfaceAsBinder.Stub() {}

    private val callback = object : IWeatherCallBackListener.Stub() {
        override fun onNowWeather(json: String?) = parseNow(json)
        override fun onNowWeatherWithLoc(loc: String?, json: String?) = parseNow(json)
        override fun onUnifiedWeather(city: CityInfoBean?, json: String?) { /* json = UnifiedWeatherBean */ }
        override fun onUnifiedWeatherWithLoc(city: CityInfoBean?, json: String?) {}
        override fun onHourWeather(json: String?) {}
        override fun onHourWeatherWithLoc(loc: String?, json: String?) {}
        override fun onRecentWeather(json: String?) {}
        override fun onRecentWeatherWithLoc(loc: String?, json: String?) {}
        override fun onAlarmSuccess(json: String?) {}
        override fun onAssociateWeatherWord(keyword: String?, wordInfo: String?) {}
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val c = IWeatherController.Stub.asInterface(binder)
            controller = c
            try {
                c.register(packId, asBinder)     // OEM registra o caller antes de tudo
                c.registerCallbackListener(packId, callback)
                onStatus?.invoke(true, "conectado ao WeatherService; pedindo sync…")
                Log.d(TAG, "conectado; pedindo sync…")
                c.syncWeather(packId)            // dispara onNowWeather com o cache/atual
            } catch (e: Exception) {
                Log.e(TAG, "erro no connect", e)
                onStatus?.invoke(true, "erro no connect: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controller = null
            onStatus?.invoke(false, "serviço desconectado")
        }
    }

    fun connect() {
        if (bound) return
        val intent = Intent(REMOTE_ACTION).apply { setPackage(REMOTE_PACKAGE) }
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            onStatus?.invoke(false, "bindService lançou: ${e.message}"); false
        }
        Log.d(TAG, "bindService -> $bound")
        onStatus?.invoke(false, if (bound) "bind solicitado, aguardando conexão…"
                                else "bindService retornou false (serviço não encontrado?)")
    }

    fun disconnect() {
        try { controller?.unregisterCallbackListener(packId, callback) } catch (_: Exception) {}
        try { controller?.unregister(packId, asBinder) } catch (_: Exception) {}
        if (bound) { try { context.unbindService(connection) } catch (_: Exception) {}; bound = false }
        controller = null
        onStatus?.invoke(false, "unbind")
    }

    /** Força atualização por código de cidade HeWeather (ex.: "CN101020100"); "" usa a localização atual. */
    fun refresh(cityCode: String = "") {
        try {
            if (cityCode.isEmpty()) controller?.syncWeather(packId)
            else controller?.syncNowWeatherByLoc(packId, cityCode)
        } catch (e: Exception) { Log.e(TAG, "refresh falhou", e) }
    }

    private fun parseNow(json: String?) {
        if (json.isNullOrEmpty()) return
        try {
            // CommonNowWeather: os getters (tmp, condCode, …) ficam na raiz do objeto.
            val o = JSONObject(json)
            val tmp = o.optString("tmp", o.optJSONObject("now")?.optString("tmp") ?: "")
            val condCode = o.optString("condCode", o.optJSONObject("now")?.optString("condCode") ?: "")
            val condTxt = o.optString("condTxt", o.optJSONObject("now")?.optString("condTxt") ?: "")
            currentTempC = tmp.ifEmpty { null }
            Log.d(TAG, "onNowWeather tmp=$tmp condCode=$condCode")
            onNow?.invoke(currentTempC, condCode, condTxt, json)
        } catch (e: Exception) {
            Log.e(TAG, "parse JSON falhou: $json", e)
        }
    }
}
