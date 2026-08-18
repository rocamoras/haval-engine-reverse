# Card de "mídia online" da MediaCenter — como é escolhido e como trocar

Análise dos APKs em `apks_oem/` (`com.beantechs.mediacenter.apk`, `com.beantechs.mediacenter.h5.ui.apk`).
Sem jadx/apktool na máquina: feito via androguard (dex → smali) com scripts descartáveis.

## 1. Quem desenha a fileira de ícones

`com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity.loadOnlineMusicCard()`
(chamada só em `onCreate`) faz:

```
cps = MediaCenterManager.getInstance().getCPList()     // Integer[]
onlineMusicContainer.removeAllViews()
para cada cp em cps:
    iv = AppCompatImageView(this)
    iv.setElevation(cp)                                 // (!) o id do CP vai na elevation
    switch (cp) { 503 -> joox, 504 -> mytuner, 550 -> amazon,
                  551 -> deezer, 552 -> youtube, 553 -> tunein,
                  554 -> dazn, 555 -> reuters, 556 -> radioline,
                  else -> espn }                        // width/height/margin/drawable
    iv.setOnClickListener(this)
    onlineMusicContainer.addView(iv)
```

`MediaCenterActivity.onClick(v)` lê de volta `v.getElevation()`, valida `501..600` e chama
`MediaCenterManager.skipApp4OnlineOs(cp)`.

Cadeia do clique:
`MediaCenterManager` → `OnlineOsImpl` → `OnlineOsModelImpl.skipApp4OnlineOs(cp)`
→ `OnlineOsBeanHelper.skipApp4OnlineOs(cp)`
→ `OnlineOsUtil.getProviderType(cp)` (503→`joox`, 504→`mytuner`, 550→`Amazon`, 551→`Deezer`,
552→`Youtube`, 553→`TuneIn`, 554→`Dazn`, 555→`Reuters TV`, 556→`Radioline`, 557→`ESPN`)
→ `ProviderTypeManager.getProviderType(nome)` → id do app H5
→ `IAppControlCall.launcher(id, "", null)` (binder do `com.beantechs.mediacenter.h5.ui`).

## 2. Por que só aparece o Deezer

`OnlineOsModelImpl.initHelper()` (chamado de `OnlineOsModelImpl.onCreate()`) monta a lista fixa
a partir de uma system property:

```
countryCode = SystemProperties.getInt("persist.bean.country.code", -1)

13 (Tailândia)  -> [503 JOOX, 504 myTuner]
15 (União Eur.) -> se persist.vendor.gwm.cfg.special.country.export == 34 (Israel) -> [551 Deezer]
                   senão                                                            -> [-1]  (nada)
17 (Brasil)     -> [551 Deezer]
qualquer outro  -> [-1]  (nada)
```

Constantes (`SystemPropertiesUtils$CountryCode`): THAILAND=13, EUROPEAN_UNION=15, BRAZIL=17,
ISRAEL=34 (essa vem de `persist.vendor.gwm.cfg.special.country.export`), AUSTRALIA=7.

Ou seja: **na sua central `persist.bean.country.code` está 17 (Brasil), e o build só sabe entregar
`[Deezer]` para o Brasil.** Os ramos de TuneIn (553), Radioline (556), Reuters (555), Amazon (550),
YouTube (552) e DAZN (554) existem no `loadOnlineMusicCard()` mas **nenhum valor de country code
neste APK os coloca na lista** — é código morto nessa versão. Confirmado: `selector_online_music_tunein`
e `selector_online_music_radio_line` só têm 1 xref, o `loadOnlineMusicCard()`.

## 3. Segundo portão: o app precisa existir no runtime H5

`ProviderTypeManager` traduz nome → id. O mapa começa com todos os nomes apontando para `""`
(`init()`) e só é preenchido de verdade em `OnlineOsBeanHelper$appListener$1.binderConnected()`,
com a **lista de apps instalados** que o serviço H5 devolve (`AppInfo.name` → `AppInfo.id`).
Se o nome não estiver instalado, `getProviderType` devolve vazio e `skipApp4OnlineOs` retorna sem fazer nada.

O catálogo vem embutido em `com.beantechs.mediacenter.h5.ui.apk` → `res/raw/app_list.txt` (29 apps,
NetRange/twine4car). Os que interessam:

| Nome (chave do ProviderTypeManager) | id | URL |
|---|---|---|
| Deezer | `6131F97C0C42A60001000002` | `npwas://ver.netrange.com/twine4car.deezer/production/v1/index.html` |
| TuneIn | `611D7A9A0C42A60001000017` | `npwas://tunein.com/radio/music/` |
| Radioline | `611D6F1F0C42A60001000016` | `npwas://access-bt-gwm-9zcb4r.radioline.co/` |
| Reuters TV | `61238F990C42A6000100001A` | `npwas://www.reuters.com/video/?ua=mobile` |
| Amazon Music | `615C3EEE0C42A60001000003` | `npwas://music.amazon.com/?ua=mobile` |
| Youtube | `6181A5990C42A6000100000C` | `npwas://www.youtube.com/?app=desktop&persist_app=1&ua=default` |
| Pluto TV, CNN, Sky Sports, One Football, DAZN, Kidomi, Stingray Music, WSJ, Toon Goggles, Chili, euronews, DW TV, + 8 jogos | ver §6 | — |

Detalhe: o `app_list.txt` traz o título **`DAZN`**, mas o `ProviderTypeManager` registra a chave
**`Dazn`** — o `containsKey` falha e o DAZN nunca mapeia. Bug do OEM.

`com.beantechs.mediacenter.h5.ui/…MainActivity` é exportada e aceita `VIEW` com schemes
`npwa` / `npwas` — dá para abrir qualquer PWA direto, sem passar pela MediaCenter.

## 4. Como trocar, do menos para o mais invasivo

**(a) Abrir direto o PWA (teste rápido, sem patch):**

```bash
adb shell am start -a android.intent.action.VIEW -d "npwas://tunein.com/radio/music/"
```

**(b) Trocar o country code** (root; muda outras coisas além do card, e nenhum valor dá TuneIn):

```bash
adb shell getprop persist.bean.country.code
adb shell setprop persist.bean.country.code 13   # -> JOOX + myTuner
adb shell am force-stop com.beantechs.mediacenter
```

**(c) Frida no processo `com.beantechs.mediacenter`** — é o caminho real para escolher o que aparece.
Hook em `getMCpList()` (o Activity e o model estão no mesmo processo, então pega os dois usos):

```js
Java.perform(function () {
  var Impl = Java.use('com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl');
  var Integer = Java.use('java.lang.Integer');
  var WANT = [551, 553, 556, 555];  // Deezer, TuneIn, Radioline, Reuters

  Impl.getMCpList.implementation = function () {
    var arr = Java.array('java.lang.Integer', WANT.map(function (v) { return Integer.valueOf(v); }));
    return arr;
  };

  // Se o clique não abrir nada, o app não está na lista instalada do H5:
  // force o id direto (ids da tabela acima).
  var PTM = Java.use('com.beantechs.mediacenter.h5.sdk.ProviderTypeManager');
  var IDS = { 'TuneIn': '611D7A9A0C42A60001000017',
              'Radioline': '611D6F1F0C42A60001000016',
              'Reuters TV': '61238F990C42A6000100001A' };
  PTM.getProviderType.implementation = function (name) {
    var r = this.getProviderType(name);
    if ((!r || r.length === 0) && IDS[name]) return IDS[name];
    return r;
  };
});
```

Depois de injetar, reabrir a MediaCenter (`loadOnlineMusicCard()` só roda em `onCreate`):

```bash
adb shell am force-stop com.beantechs.mediacenter
```

Para deixar permanente sem Frida seria preciso repatchar o `classes2.dex` do
`com.beantechs.mediacenter.apk` (o array de `initHelper`) e reassinar — só vale se o `/system`
estiver gravável, já que o APK é system app com `sharedUserId=android.uid.system`.

## 5. Todos os `MEDIA_SRC_*` (ids de fonte de mídia)

Só a faixa `501..600` (`MEDIA_SRC_OVERSEA_MIN..MAX`) é aceita pelo `onClick` do card.

| id | constante |
|---|---|
| 1 / 100 | `LOCAL_MIN` / `LOCAL_MAX` |
| 2 | `LOCAL_USB_AUDIO` |
| 3 | `LOCAL_USB_VIDEO` |
| 4 | `LOCAL_BT` |
| 10 | `LOCAL_RADIO` |
| 11 / 12 / 13 / 14 | `LOCAL_RADIO_AM` / `FM` / `DRM` / `DAB` |
| 101 / 200 | `ONLINE_AUDIO_MIN` / `MAX` |
| 102 | `ONLINE_AUDIO_BOOK` |
| 103 | `ONLINE_AUDIO_RADIO` |
| 104 | `ONLINE_AUDIO_WEY` |
| 105 | `ONLINE_AUDIO_NEWS` |
| 106 | `ONLINE_AUDIO_BRAND_RADIO` |
| 107 | `ONLINE_AUDIO_HIGH_QUALITY` |
| 108 | `ONLINE_AUDIO_URL` |
| 109 | `ONLINE_AUDIO_51_KU_WO_MUSIC` |
| 201 / 300 | `ONLINE_AUDIO_THIRD_MIN` / `MAX` |
| 202 | `ONLINE_AUDIO_AQT` |
| 254 | `FOR_FOCUS_ALL` |
| 255 | `NONE` |
| 301 / 400 | `ONLINE_VIDEO_MIN` / `MAX` |
| 302 | `ONLINE_VIDEO_HIGH_QUALITY` |
| 303 | `ONLINE_VIDEO_URL` |
| 304 | `THIRD_VIDEO_IQY` |
| 401 / 500 | `PHONE_CONNECT_MIN` / `MAX` |
| 402 | `PHONE_CONNECT_ANDROID_AUTO` |
| 403 | `PHONE_CONNECT_CAR_PLAY` |
| **501 / 600** | **`OVERSEA_MIN` / `OVERSEA_MAX`** |
| 502 | `OVERSEA_ONLINE_MEDIA` |
| 503 | `OVERSEA_ONLINE_MUSIC_JOOX` |
| 504 | `OVERSEA_ONLINE_RADIO_MY_TUNER` |
| 550 | `EU_ONLINE_MUSIC_AMAZON` |
| 551 | `EU_ONLINE_MUSIC_DEEZER` |
| 552 | `EU_ONLINE_VIDEO_YOUTUBE` |
| 553 | `EU_ONLINE_RADIO_TUNEIN` |
| 554 | `EU_ONLINE_MATCH_DA_ZN` |
| 555 | `EU_ONLINE_VIDEO_REUTERS` |
| 556 | `EU_ONLINE_RADIO_LINE` |
| 557 | `OVERSEA_ESPN` |
| 601 / 700 | `EXCLUDE_MIN` / `MAX` |
| 602 | `EXCLUDE_VIDEO_TIK_TOK` |
| 701 / 800 | `OTHER_MIN` / `MAX` |
| 702 | `OTHER_ONLINE_MATCH` |
| 703 | `OTHER_ONLINE_MATCH_DETAIL` |
| 799 | `OTHER_ONLINE_AUDIO_LAST` |
| 900 | `PHONE_LINK_CAR` |

Note que só existe desenho de ícone para 503, 504, 550-557. `502` (`OVERSEA_ONLINE_MEDIA`) cai no
`else` do switch e apareceria como ESPN.

## 6. Catálogo completo (`h5.ui/res/raw/app_list.txt`, 29 apps)

### WEBAPP (PWA, abre via `npwas://` no content shell NFBE)

| # | title | id | app_url | idiomas |
|---|---|---|---|---|
| 1 | Amazon Music | `615C3EEE0C42A60001000003` | `npwas://music.amazon.com/?ua=mobile` | en-GB, de, fr, es, it |
| 2 | CNN | `615C53170C42A60001000006` | `npwas://edition.cnn.com/videos` | en-US, en-GB |
| 3 | Chili | `61799ECA0C42A6000100000A` | `npwas://chili.com` | en |
| 4 | DAZN | `6176B10C0C42A60001000008` | `npwas://dazn.com` | en |
| 5 | Deezer | `6131F97C0C42A60001000002` | `npwas://ver.netrange.com/twine4car.deezer/production/v1/index.html` | en, de, fr, es, it |
| 6 | Kidomi | `60F9C3A90C42A60001000008` | `npwas://www.kidomi.com/play/` | — |
| 7 | One Football | `615C8B250C42A60001000009` | `npwas://onefootball.com/` | en, de, fr, es, it |
| 8 | Pluto TV | `60F9B7100C42A60001000003` | `npwas://pluto.tv/` | en-GB, de, fr, es, it |
| 9 | Radioline | `611D6F1F0C42A60001000016` | `npwas://access-bt-gwm-9zcb4r.radioline.co/` | en, de, fr, es, it |
| 10 | Reuters TV | `61238F990C42A6000100001A` | `npwas://www.reuters.com/video/?ua=mobile` | en |
| 11 | Reuters TV - CN | `6179A4EA0C42A6000100000B` | `npwas://cn.reuters.com/video/` | — |
| 12 | Sky Sports News | `615C62320C42A60001000008` | `npwas://sport.sky.de/videos` | — |
| 13 | Stingray Music | `60F9BD550C42A60001000005` | `npwas://ver.netrange.com/twine4car.stingray-music-app/production/v1/index.html` | en, de |
| 14 | T4C News Service | `615C4BEE0C42A60001000005` | `npwas://ver.netrange.com/twine4car.news-aggregation-app/production/v1/index.html#/` | en-GB |
| 15 | The Wall Street Journal | `60F9C00D0C42A60001000006` | `npwas://ver.netrange.com/twine4car.wsj/production/v1/index.html` | en-US, en-GB |
| 16 | Toon Goggles | `60F9C1720C42A60001000007` | `npwas://ver.netrange.com/twine4car.toongoggles/production/v1/index.html` | en, de, fr, es, it |
| 17 | TuneIn | `611D7A9A0C42A60001000017` | `npwas://tunein.com/radio/music/` | en, de, fr, es, it |
| 18 | Youtube | `6181A5990C42A6000100000C` | `npwas://www.youtube.com/?app=desktop&persist_app=1&ua=default` | en-GB, de, fr, es, it |

### NATIVEAPP (APK baixado do CDN e instalado)

| # | title | package | versão | id |
|---|---|---|---|---|
| 19 | 8 Ball Pool | `com.codethislab.eightballpool` | 1.08 (8) | `615EB5BA0C42A60001000013` |
| 20 | Burnin Rubber Air | `com.xformgames.burninrubber5air` | 1.0 (1) | `615EB60D0C42A60001000014` |
| 21 | Deutsche Welle TV | `com.idmedia.android.newsportal` | 2.6.9 (2690) | `611EB8080C42A60001000019` |
| 22 | Dungeon Crawl | `net.eightbitape.dungeoncrawl` | 1.0.5 (6) | `615EB5810C42A60001000012` |
| 23 | Dust Squad | `com.airconsole.games.dustsquad` | 1.1 (4) | `615EB1EB0C42A60001000010` |
| 24 | Go Kart Go Air | `com.xformgames.gokartgoair` | 1.1 (3) | `615EAF480C42A6000100000F` |
| 25 | Ludo | `com.beetsteam.ludogame` | 1.3 (13) | `6163EF030C42A60001000002` |
| 26 | Racing Wars | `com.bighutgames.racingwars` | 1.1.10 (1110) | `615CBA920C42A6000100000D` |
| 27 | The Neighborhood | `ch.dnastudios.theneighbourhood` | 1.0.7 (1070) | `615CBA2C0C42A6000100000C` |
| 28 | Tower of Babel | `ch.dnastudios.towerofbabel` | 2.0.0 (5) | `615EB2A20C42A60001000011` |
| 29 | euronews. | `com.euronews.express` | 5.4.2 (234) | `60F9E2C50C42A60001000012` |

Os APKs nativos vêm de `https://d1r7m2azi9bq3q.cloudfront.net/<slug>/<arquivo>.apk`; as URLs completas
estão no `app_list.txt`. Este arquivo é o catálogo **embutido** (fallback); em runtime o serviço H5
consulta o servidor NetRange e o mapa de `ProviderTypeManager` é montado com o que ele devolver.

Chaves que o `ProviderTypeManager.init()` registra (e que precisam casar com o `title` do catálogo):
`JOOX`, `MY_TUNER`, `Deezer`, `Youtube`, `TuneIn`, `Reuters TV`, `Radioline`, `Dazn`, `Amazon`,
`ESPN`, `STINGRAY_MUSIC`, `WALL_STREET`, `GOGGLES_CARTOONS`, `KIDOMI`, `EURONEWS`,
`DEUTSCHE_WELLE_TV`, `PLUTO_TV`. Só `Deezer`, `Youtube`, `TuneIn`, `Reuters TV` e `Radioline` casam
exatamente com títulos do catálogo — `Amazon` (título "Amazon Music"), `Dazn` ("DAZN"),
`STINGRAY_MUSIC` ("Stingray Music") etc. não casam, então nunca resolvem id por esse caminho.

## 7. Filtro de media session do content shell

`h5.ui/assets/mediasession_filter.json` — só estes 4 têm integração de sessão de mídia
(minibar/controles do carro):

```json
{"apps":[
  {"url":"tunein.com","actions":[],"notify":true},
  {"url":"twine4car.deezer","title":"Deezer","notify":false,"support_favorite":true,"support_loading":false},
  {"url":"radioline.co","support_favorite":true,"support_favorite_enabled":true,"support_loading":true,"calculate_position":true},
  {"url":"reuters.com","title":"Reuters","calculate_position":true}
]}
```

## 8. Aba "Mídia" no app (v2.21.0)

Implementado em:

- `app/src/main/res/raw/com_beantechs_mediacenter_cp.js` — hook Frida no processo
  `com.beantechs.mediacenter`: substitui `OnlineOsModelImpl.getMCpList()` pela lista lida de
  `/data/local/tmp/inject_media_cp` (CSV de ids 501..600; `none`/vazio = lista vazia; arquivo
  ausente = comportamento original) e, a cada mudança do arquivo, acha a `MediaCenterActivity`
  viva com `Java.choose` e chama `loadOnlineMusicCard()` na main thread — repinta sem reiniciar
  o app. Também cobre `ProviderTypeManager.getProviderType`: se o mapa do runtime H5 vier vazio
  para o nome, devolve o id do catálogo embutido (§6).
- `FridaUtils.injectMediaCenterCp() / writeMediaCp() / openMediaCenter() / stopMediaCenterCp() /
  readMediaCp() / mediaCpLog()`.
- `MediaCardScreen.kt` (`MediaCardTab`), aba 8 do `MainActivity`.

A aba tem as duas opções pedidas — **A: remover Deezer e colocar TuneIn** (`553`) e
**B: remover todos** (card vazio) — mais "voltar ao padrão (só Deezer)" e uma seleção livre com
os 10 CPs desenháveis, aplicada na ordem em que você marca. Os chips com ⚠ (`Amazon Music`,
`DAZN`, `ESPN`, `JOOX`, `myTuner`) têm nome que não casa com o catálogo do runtime H5: o ícone
aparece, mas o clique pode não abrir nada.

Requisitos: APK "fat" (com os binários do Frida 16.x em `res/raw`) + Shizuku ativo, igual às
abas Tela/Clima. O `am force-stop` só é usado em "remover hook" — aplicar a lista não reinicia
nada, senão a injeção morreria junto com o processo.

## 9. O clique: o id do catálogo não basta (v2.21.4)

Com o card já mostrando o TuneIn (v2.21.3), o clique não abria nada. O log da injeção:

```
[mc-cp] card repintado: [553]
[mc-cp] providerType(TuneIn) vazio -> usando id do catálogo 611D7A9A0C42A60001000017
```

"vazio" = o `PROVIDER_TYPE_MAP` real não tem `TuneIn`. E o `launcher(id)` não abre a PWA
sozinho — ele fala por AIDL com um **terceiro app**:

```
H5SdkAppServiceImpl.serviceAction()  = "com.beantechs.mediacenter.h5.core.action.LaunchManager"
H5SdkAppServiceImpl.servicePackage() = "com.beantechs.mediacenter.h5.core"
H5SdkBaseAppService.bindService()    -> fallback p/ componente
                                        com.beantechs.mediacenter.h5.core/…OnlineManagerService
```

É o `h5.core` que devolve `getProviderAppList()` (`AppInfo.name` → `AppInfo.id`), e é dele que
`ProviderTypeManager` se alimenta em `binderConnected()`. Um id que ele não provisionou é
ignorado. Note que o `h5.core` **não está** em `apks_oem/` — só o `h5.ui`.

Contorno implementado: o hook intercepta `OnlineOsModelImpl.skipApp4OnlineOs(cp)` e,
quando o nome não está no mapa real, abre a PWA por conta própria com
`Intent(ACTION_VIEW, npwas://…)` + `setPackage("com.beantechs.mediacenter.h5.ui")` — o content
shell NFBE é exported e BROWSABLE, então não depende de provisionamento. Se o nome ESTÁ no mapa,
o caminho OEM é preservado.

O script agora também loga, no start, `PROVIDER_TYPE_MAP` inteiro e se `h5.core`/`h5.ui` estão
instalados — é o que decide se o caminho certo é provisionar no `h5.core` ou seguir por intent.

## 10. Quem está provisionado de verdade (log da central, 2026-08-17)

O `PROVIDER_TYPE_MAP` lido em runtime na central (dump do hook) — chave vazia = nome que o
`ProviderTypeManager.init()` registra mas que o provisionamento não conhece:

```
Deezer            = 6131F97C0C42A60001000002
ESPN              = 697A2D92CBA38E0001000003
Reuter TV         = 61B9D0780C42A60001000017
Youtube           = 6181A5990C42A6000100000C
YouTube (mobile)  = 619FF6480C42A6000100000F
Apple Music       = 65DC4401BBE3E50001000001
TuneIn=, Radioline=, Amazon=, Dazn=, Reuters TV=, JOOX=, MY_TUNER=,
KIDOMI=, PLUTO_TV=, EURONEWS=, STINGRAY_MUSIC=, WALL_STREET=,
GOGGLES_CARTOONS=, DEUTSCHE_WELLE_TV=
```

Três coisas importantes saem daí:

1. **O `enable` do `assets/cp_list.xml` do h5.core é irrelevante** — `Util.getProvidersFromAssets()`
   não tem nenhum chamador (código morto). A prova nos dados: `Radioline` tem `enable=true` no
   asset e está **vazio** em runtime, e os ids de `ESPN`/`Reuters TV` em runtime não são os do
   asset. A lista real vem de `CookieManager.getProvidedAppInfoList()`, alimentada pelo servidor.
2. **`launcher(id)` precisa de cookie.** Em `H5CoreAppServiceStubImpl.launcher(providerType,…)`:
   `cookie = CookieManager.getCookie(providerType)`; vazio → `onResult(erro)` e nada abre.
   Com cookie → `ILocalReceiveListener.launcher(id, cb)` no h5.ui. Por isso id de catálogo não
   funciona: não existe cookie pra um app não provisionado.
3. **Diferença de nome derruba o caminho OEM sozinha.** O OEM procura `Reuters TV`, `Amazon` e
   `Dazn`; o provisionamento tem `Reuter TV` (sem s), `Amazon Music` e `DAZN`. O hook agora
   resolve por alias, então o **Reuters (555) passa a abrir pelo fluxo original**.

Resultado por CP nesta central:

| CP | app | como abre |
|---|---|---|
| 551 | Deezer | OEM (provisionado) |
| 552 | YouTube | OEM (provisionado) |
| 555 | Reuters TV | OEM via alias `Reuter TV` |
| 557 | ESPN | OEM (provisionado) |
| 553 | TuneIn | Intent `npwas://tunein.com/radio/music/` |
| 556 | Radioline | Intent |
| 550 | Amazon Music | Intent |
| 554 | DAZN | Intent |
| 503/504 | JOOX / myTuner | sem app aqui |

**Apple Music** (`65DC4401BBE3E50001000001`) está provisionado e **não tem CP** no
`loadOnlineMusicCard()` — não há ícone pra ele no APK da MediaCenter. Daria pra pendurar num
slot livre (ex. 550) resolvendo o nome pra `Apple Music`, mas o ícone desenhado seria o do
Amazon. Fica registrado como possibilidade.

Sobre integração de mídia no caminho por intent: o `assets/mediasession_filter.json` do h5.ui
casa por **URL** (`tunein.com`, `radioline.co`, `reuters.com`, `twine4car.deezer`), não por
provisionamento — então minibar e controles do volante têm chance de funcionar mesmo abrindo
por intent. Vale confirmar tocando algo.

## 11. Desabilitar o bloco, e o que dá pra pôr no lugar (v2.22.0)

O "menu de mídia online" é só na tela principal do app de mídia — a **launcher não tem**:
nenhum layout dela menciona mídia e o id `media_online_music_record` não existe no
`resources.arsc` (a string no dex é resíduo).

`res/layout-1792x1080/activity_media_center.xml`:

```
TextView             id/online_music           text=string/main_online_music_title   <- título
HorizontalScrollView id/horizontalScrollView                                         <- fileira
  └ LinearLayout     id/online_music_container                                       <- os ícones
TextView             id/local_media            text=string/main_local_media_title
5x ConstraintLayout (binding_1..5)                                                   <- fontes locais
```

Todo elemento é ancorado no **topo do pai** (`layout_constraintTop_toTopOf="0"` + margem fixa),
sem encadeamento entre eles. Consequência prática: esconder a fileira **não faz o resto subir** —
o espaço fica vago (e disponível).

Implementado: o arquivo de controle aceita `off` (além de CSV e `none`), e o hook chama
`setVisibility(GONE)` em `online_music` + `horizontalScrollView`, resolvendo os ids por
`getResources().getIdentifier(...)`. Botão "C · esconder o menu inteiro" na aba.

Três estados agora:

| `/data/local/tmp/inject_media_cp` | efeito |
|---|---|
| ausente | comportamento de fábrica |
| `none` (ou vazio) | título fica, fileira sem nenhum ícone |
| `off` | título e fileira somem |
| `553,556,…` | fileira com esses CPs |

### O que daria pra mostrar ali

O `online_music_container` é um `LinearLayout` que já é reconstruído por nós a cada
`loadOnlineMusicCard()` — dá pra adicionar qualquer `View` filha, com qualquer ação no clique
(o OEM mesmo faz isso, guardando o id do CP na `elevation`). Caminhos viáveis:

1. **Atalhos livres**: ícone + clique abrindo qualquer PWA (`npwas://…`) ou qualquer pacote
   (`am start`/Intent). Sem depender de provisionamento, como o TuneIn já faz hoje.
2. **Apps provisionados sem CP**: `Apple Music` (`65DC4401BBE3E50001000001`) está provisionado e
   não tem slot no `switch` do OEM — daria pra pendurar num CP livre resolvendo o nome, mas o
   ícone desenhado seria o do slot usado (não existe drawable de Apple Music no APK).
3. **Widget de informação** no lugar da fileira: qualquer `View` construída em runtime
   (texto/valor), no espaço que sobra com o bloco escondido — ex. temperatura externa, que já
   temos lendo `car.basic.outside_temp`.

Ícone customizado exige bitmap: ou um PNG solto em `/data/local/tmp` via `BitmapFactory`, ou algo
desenhado em runtime (`ShapeDrawable` + texto).
