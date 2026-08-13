# Temperatura externa REAL na barra de status — Haval H6 GT (B03)

Guia reproduzível. Mostra o sensor real `car.basic.outside_temp` na barra nativa do
sistema, substituindo a temperatura do app de clima OEM.

Carro de referência: `msmnile` (Snapdragon), `BuildConfig.type == 2`, Android 9.

---

## TL;DR

1. **Desativar** o app de clima OEM: **`com.beantechs.weatherservice`**.
2. Injetar um hook Frida **16.x** no **`com.android.systemui`** que lê o sensor real
   (`car.basic.outside_temp`) via `PlatformAdapterClient` e escreve na TextView
   `BeanCarStatusBarView.mWeatherTemp`.

---

## Por que precisa do hook (a descoberta)

- A temperatura da barra vem do **app de clima**, via
  `BeanCarStatusBarView` ← `BeanWeatherManager.onWeatherInfoUpdate(BeanWeatherInfo.curTemp)`.
  Desativando o app de clima, a barra fica **"--"** (`R.string.bean_info_label_default`).
- Existe um caminho de sensor real — `BeanCarStatusBarView.onOutsideTempUpdate(float)` —
  **mas só roda em `BuildConfig.type == 1 || 3`**. Neste carro `type == 2`:
  - `onOutsideTempUpdate` é **no-op**;
  - o `BeanStatusBarManager` **nem lê** `car.basic.outside_temp` (o `case` é guardado
    pela mesma trava de type).
  - → Hookar `onOutsideTempUpdate` **não funciona** aqui.
- Solução: **ler o sensor direto** e escrever na TextView, ignorando `type` e o app de clima.

---

## APK nativo a desativar

```
com.beantechs.weatherservice
```

Desativar (via root/telnet/adb shell com privilégio):

```bash
pm disable-user --user 0 com.beantechs.weatherservice
# reabilitar depois, se quiser:
# pm enable com.beantechs.weatherservice
```

> Observação: `com.beantechs.weatherservice` é app de sistema (`sharedUserId=android.uid.system`),
> dex-stripped (código real no `.vdex`). Desabilitar por usuário basta — não precisa remover.

---

## Alvos do hook (reversão do SystemUI)

| Item | Valor |
|------|-------|
| Processo alvo | `com.android.systemui` |
| Classe da view | `com.bean.statusbar.BeanCarStatusBarView` |
| TextView da temp | campo privado `mWeatherTemp` (`R.id.status_bar_weather_temp_text`) |
| Fonte do sensor | `com.beantechs.adapterservice.client.PlatformAdapterClient` |
| Chamada | `PlatformAdapterClient.getInstance().getData("car.basic.outside_temp")` → String |
| Chave do sensor | `car.basic.outside_temp` (`LocalConstants.Display.OUTSIDE_TEMP`) |
| Faixa válida | `-40 < t < 86.5` |

---

## Pré-requisitos

- **Frida 16.x** (usar **16.7.19**). ⚠️ **Frida 17 NÃO serve** — removeu o bridge global
  `Java`, e scripts crus quebram com `ReferenceError: 'Java' is not defined`.
  - `frida-server-16.7.19-android-arm64` e `frida-inject-16.7.19-android-arm64`
    (releases oficiais `github.com/frida/frida`).
- **Root** (para `setenforce 0`, subir o fridaserver e injetar). Neste projeto: via
  **Shizuku** (rodando como root) ou telnet root local (`127.0.0.1:23`).
- SELinux permissivo durante a injeção: `setenforce 0`.

---

## Passo a passo (injeção)

```bash
# 0) binários em /data/local/tmp (chmod 755), SELinux permissivo
setenforce 0
chmod 755 /data/local/tmp/fridaserver /data/local/tmp/fridainject

# 1) fridaserver (se não estiver rodando)
setsid /data/local/tmp/fridaserver >/dev/null 2>&1 < /dev/null &

# 2) pid do SystemUI
PID=$(pidof com.android.systemui)

# 3) injeta o script (mata injeções anteriores antes — idempotente)
pkill -f com_android_systemui
setsid /data/local/tmp/fridainject -D local -p $PID \
  -s /data/local/tmp/com_android_systemui.js \
  > /data/local/tmp/com_android_systemui.log 2>&1 < /dev/null &
```

> O hook **não persiste** se o SystemUI reiniciar — reinjetar nesse caso.

---

## Script Frida (`com_android_systemui.js`)

```javascript
"use strict";
function log(m){ console.log("[sysui-outtemp] " + m); }

Java.perform(function () {
    var PAC = Java.use("com.beantechs.adapterservice.client.PlatformAdapterClient");
    var Str = Java.use("java.lang.String");
    var OUTSIDE = "car.basic.outside_temp";
    var views = [];

    function findViews() {
        views = [];
        Java.choose("com.bean.statusbar.BeanCarStatusBarView", {
            onMatch: function (v) { views.push(v); },
            onComplete: function () {}
        });
    }
    function readTemp() {
        try { var t = parseFloat(PAC.getInstance().getData(OUTSIDE)); return isNaN(t) ? null : t; }
        catch (e) { return null; }
    }

    setInterval(function () {
        Java.perform(function () {
            var t = readTemp();
            if (t === null || t <= -40.0 || t >= 86.5) return;
            var s = Math.round(t) + "°";
            if (views.length === 0) findViews();
            var painted = 0;
            views.forEach(function (v) {
                try {
                    var tv = v.mWeatherTemp.value;
                    if (tv !== null) {
                        Java.scheduleOnMainThread(function () {
                            tv.setText.overload("java.lang.CharSequence").call(tv, Str.$new(s));
                        });
                        painted++;
                    }
                } catch (e) {}
            });
            if (painted === 0) views = []; // recaptura na próxima
        });
    }, 2000);

    log("hook temp externa REAL ativo");
});
```

Notas de implementação (aprendidas na marra):
- **`Java.choose`** para achar a `BeanCarStatusBarView` viva — não usar o campo estático
  do manager (`HiBoardManager.INSTANCE` / etc.): o bridge Kotlin do Frida expõe
  `INSTANCE` como `undefined` (`cannot read property 'value' of undefined`).
- Envolver o corpo do `setInterval` em **`Java.perform`** (`Java.choose`/`scheduleOnMainThread`
  exigem contexto VM-attached).
- `setText` via `.overload("java.lang.CharSequence")` para não colidir com `setText(int)`.

---

## Verificação / log

```bash
# o log é root:600; ler via root (su/telnet/shizuku)
cat /data/local/tmp/com_android_systemui.log
```

Mensagens esperadas:
- `pintado 28° (sensor 28)` → funcionando.
- `sensor invalido/ausente: NaN` → `getData` não retornou valor.
- `BeanCarStatusBarView nao encontrada` → barra ainda não criada.

---

## Plano B (se `getData` vier vazio)

Se com o app de clima desativado o `PlatformAdapterClient.getData("car.basic.outside_temp")`
vier vazio, um app com privilégio lê o sensor por conta própria (car property) e grava
o valor num arquivo (`/data/local/tmp/inject_temp`); o script lê desse arquivo em vez do
`getData`. (Não foi necessário no carro de referência.)
