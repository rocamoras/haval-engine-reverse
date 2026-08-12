# Reverse: `com.beantechs.weatherservice` (temperatura externa OEM)

APK analisado: `apks_oem/oem_20260812_195004_com.beantechs.weatherservice.apk`
Versão: `1.0.0.2023.11.06` · minSdk 26 · targetSdk 28 · `sharedUserId=android.uid.system`

## TL;DR — a descoberta que muda a direção do projeto

A tela da home **não lê a temperatura de uma `car.*` property**. O launcher OEM
(`com.beantechs.launcher`, classe `HiBoardView`) faz **bind num serviço AIDL** do
WeatherService e recebe o **JSON do clima** por callback. Então, em vez de tentar
adivinhar/injetar numa car-property (`car.weather.temperature`, probe, overlay, hook
de SystemUI), dá pra **fazer o mesmo bind e ler a temperatura real e oficial** direto
do serviço OEM.

> Campo da temperatura externa: **`data.now.tmp`** (°C, string). Ex.: `"24"`.

## Fato do APK: sem código

O APK **não tem `classes.dex`** — é app de sistema pré-compilado; o bytecode vive em
`oat/arm64/*.vdex/.odex` na partição do sistema. `adb pull` do APK **não traz a lógica**
(endpoint da API, appid, intervalo de refresh). Para recuperar isso, puxar o `.vdex` do
carro e usar `vdexExtractor`/`jadx`. O contrato AIDL abaixo foi reconstruído a partir do
**dex do launcher** (o cliente), que tem o código completo.

## Componentes (AndroidManifest)

- **Service (exportado):** `com.beantechs.weatherservice.service.WeatherService`
  - action p/ bind: `com.beantechs.weatherservice.IWeatherController`
- Provider (não exportado): `com.beantechs.network.InitProvider`
- Permissões notáveis: INTERNET, ACCESS_FINE/COARSE_LOCATION, `com.beantechs.permission.aidl.beantls`

## Contrato AIDL

Descriptor: `com.beantechs.weatherservice.remote.IWeatherController`

### `IWeatherController` (você chama) — 1º arg é sempre o token `packId`

| txn | método | args |
|----:|--------|------|
| 1 | `register` | (String packId, IInterfaceAsBinder) |
| 2 | `unregister` | (String packId, IInterfaceAsBinder) |
| 3 | `registerCallbackListener` | (String packId, IWeatherCallBackListener) |
| 4 | `unregisterCallbackListener` | (String packId, IWeatherCallBackListener) |
| 5 | `syncWeather` | (String packId) |
| 6 | `syncRecentWeatherByLoc` | (String packId, String loc) |
| 7 | `syncNowWeatherByLoc` | (String packId, String loc) |
| 8 | `syncHourWeatherByLoc` | (String packId, String loc) |
| 9 | `isHaveCacheData` | (int) → boolean |
| 10 | `queryAssociateWeatherWord` | (String packId, String keyword) |
| 11 | `queryUnifiedWeatherInfo` | (String packId, String, String) |

`packId` = `"<packageName>||<pid>"` (ex.: `br.com.redesurftank.havalenginereverse||12345`).

### `IWeatherCallBackListener` (o serviço te empurra os dados)

| txn | método | payload |
|----:|--------|---------|
| 1 | `onNowWeatherWithLoc` | (String loc, String json) → **CommonNowWeather** |
| 2 | `onNowWeather` | (String json) → **CommonNowWeather** |
| 3 | `onRecentWeatherWithLoc` | (String loc, String json) → CommonRecentWeather |
| 4 | `onRecentWeather` | (String json) |
| 6 | `onHourWeatherWithLoc` | (String loc, String json) → HourWeather |
| 7 | `onHourWeather` | (String json) |
| 5 | `onAlarmSuccess` | (String json) → CommonAlarm |
| 8 | `onAssociateWeatherWord` | (String keyword, String json) |
| 9 | `onUnifiedWeather` | (CityInfoBean, String json) → UnifiedWeatherBean |
| 10 | `onUnifiedWeatherWithLoc` | (CityInfoBean, String json) |

Todos os payloads de clima são **String JSON** parseados com Gson no cliente OEM.

## Fluxo de uso (fiel ao `WeatherController` do OEM)

```
Intent(action="com.beantechs.weatherservice.IWeatherController")
       .setPackage("com.beantechs.weatherservice")
bindService(intent, conn, BIND_AUTO_CREATE)

onServiceConnected(binder):
    ctrl = IWeatherController.Stub.asInterface(binder)
    ctrl.register(packId, <IInterfaceAsBinder.Stub vazio>)   // registra o caller
    ctrl.registerCallbackListener(packId, <IWeatherCallBackListener.Stub>)
    ctrl.syncWeather(packId)                                  // ou syncNowWeatherByLoc(packId, cityCode)

callback onNowWeather(json):
    tmp = JSON.data.now.tmp        // temperatura externa °C
```

Há também um broadcast `com.beantechs.weatherservice.start` que o serviço emite ao
(re)iniciar — o OEM re-binda ao recebê-lo.

## Modelo de dados — `CommonNowWeather` (o que interessa)

Getters na raiz do objeto: `tmp`, `tmp_max`, `tmp_min`, `fl` (sensação, via `now` cru),
`condCode`, `condTxt`, `hum`, `pres`, `vis`, `cloud`, `pcpn`, `uv`, `qlty`, `aqi`, `pm25`,
`windDir`, `windDeg`, `windSc`, `windSpd`, `loc`, `air`, `cw`, `dcname`,
`basic{ adminArea, cid, cname, cnty, dcname, lat, lon }`, `update{ loc, tz }`.

JSON de exemplo (cacheado em `assets/2.txt` do APK, API estilo HeWeather/QWeather):

```json
{ "code":"000000", "data": {
    "basic": { "location":"上海", "cid":"CN101020100", "lat":"31.23", "lon":"121.47" },
    "now": { "tmp":"24", "fl":"27", "condCode":"101", "condTxt":"多云",
             "hum":"86", "pres":"1005", "vis":"16", "windDir":"北风", "windSpd":"1" },
    "update": { "loc":"2020-05-08 13:06" }, "status":"ok" },
  "description":"SUCCESS" }
```

## O que já foi integrado no projeto

- `app/src/main/java/com/beantechs/weatherservice/remote/` — 4 stubs de cliente
  copiados verbatim do launcher (só dependem de `android.os.*`):
  `IWeatherController`, `IWeatherCallBackListener`, `IInterfaceAsBinder`, `CityInfoBean`.
- `app/src/main/java/br/com/redesurftank/havalenginereverse/weather/OemWeatherClient.kt`
  — helper Kotlin pronto: `connect()` → callback com a temperatura real; `refresh(cityCode)`.

### Uso

```kotlin
val wx = OemWeatherClient(context)
wx.onNow = { tmp, code, txt, _ -> Log.d("WX", "temp=$tmp°C cond=$txt") }
wx.connect()
// … depois:
wx.disconnect()
```

## Pendências / próximos passos

- [ ] Testar o bind no carro (confirmar que o serviço entrega callback sem exigir mais setup).
- [ ] Recuperar o `.vdex` do WeatherService pra obter endpoint/appid da API e o intervalo de refresh.
- [ ] Confirmar o formato de `loc`/`cityCode` aceito por `syncNowWeatherByLoc` (código HeWeather `CNxxxxxxxxx` vs lat,lon).
