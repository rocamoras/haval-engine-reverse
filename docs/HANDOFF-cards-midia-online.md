# Handoff — fileira de mídia online da MediaCenter: remover e substituir por cards

Guia de implementação para outro projeto. Tudo aqui foi validado num **Haval H6 GT** (tela
1792×1080, `persist.bean.country.code=17`), com Frida **16.7.19** injetando no processo
`com.beantechs.mediacenter`. Análise de origem: [mediacenter-online-cards.md](mediacenter-online-cards.md).

Escopo deste documento: (1) eliminar a fileira/bloco de mídia online, (2) desenhar cards próprios
no lugar, (3) fazer o clique abrir um app. O aviso "não assista a vídeos" é assunto separado —
está na §15 do documento de análise (é só `Settings.System.bean_video_limit_mode`).

---

## 1. Alvo

| item | valor |
|---|---|
| processo | `com.beantechs.mediacenter` (Activity + serviço no mesmo processo) |
| Activity | `com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity` (é a `MAIN/LAUNCHER`) |
| método que desenha | `loadOnlineMusicCard()` — **privado, chamado só no `onCreate`** |
| fonte da lista | `MediaCenterManager.getInstance().getCPList()` → `OnlineOsImpl` → `OnlineOsModelImpl.getCPList()` → `getMCpList()` |
| container dos ícones | `LinearLayout` `id/online_music_container` = `0x7F0A01BB` |
| scroll da fileira | `HorizontalScrollView` `id/horizontalScrollView` = `0x7F0A0107` |
| título do bloco | `TextView` `id/online_music` = `0x7F0A01BA` (texto `string/main_online_music_title`) |
| clique | `MediaCenterActivity.onClick(v)` lê `v.getElevation()`, valida `501..600`, chama `MediaCenterManager.skipApp4OnlineOs(cp)` |

O que `loadOnlineMusicCard()` faz, em pseudocódigo:

```
cps = MediaCenterManager.getInstance().getCPList()      // Integer[]
online_music_container.removeAllViews()
para cada cp em cps:
    iv = AppCompatImageView(activity)
    iv.setElevation(cp)                                  // (!) o id do CP vai na elevation
    switch (cp) { 503 joox | 504 mytuner | 550 amazon | 551 deezer | 552 youtube |
                  553 tunein | 554 dazn | 555 reuters | 556 radioline | else espn }
    iv.setOnClickListener(activity)
    online_music_container.addView(iv)
```

Guardar o id do CP na `elevation` é gambiarra do OEM — e é o gancho mais barato para o clique
(§5.3).

---

## 2. Geometria real (config `1792x1080`)

Valores extraídos do `resources.arsc`. Como `1672 dip` precisam caber numa tela de `1792 px`, a
densidade é **1.0** → *dip ≈ px*.

| medida | valor |
|---|---|
| `HorizontalScrollView` marginStart | 188 |
| `HorizontalScrollView` marginTop | 164 |
| `HorizontalScrollView` width | **1672** (altura = `wrap_content`) |
| título `online_music` marginTop | 102 |
| título das fontes locais (`local_media`) marginTop | **548** |
| card do Deezer, como referência de tamanho | **536 × 324** |

**Área útil: 1672 × ~384**, com scroll horizontal.

Cards simultaneamente visíveis, pela largura de 1672:

| largura do card | visíveis |
|---|---|
| 536 (tamanho OEM) | 3 |
| ~400 | 4 |
| ~330 | 5 |

> **Altura é teto rígido.** Todo elemento do `activity_media_center.xml` é ancorado no **topo do
> pai** (`layout_constraintTop_toTopOf="0"` + margem fixa), sem encadeamento entre eles. Isso tem
> duas consequências: esconder a fileira **não faz o resto subir** (sobra o espaço, ótimo pra
> gente), e passar de ~384 de altura **sobrepõe** o título das fontes locais em vez de empurrá-lo.
> Para ganhar altura é preciso esconder/mover a seção de baixo também.

---

## 3. Regra de ouro do acesso Java (leia antes de escrever qualquer linha)

Uma instância obtida por `Java.choose` expõe os métodos **da própria classe**, e **não os
herdados**. `loadOnlineMusicCard()` (declarado na Activity) funciona; `getResources()`
(`ContextWrapper`) e `findViewById()` (`Activity`) dão **`TypeError: not a function`**.

Sempre chame o método na classe que o **declara**, via `Java.cast`:

```js
var ActivityCls  = Java.use("android.app.Activity");
var ViewCls      = Java.use("android.view.View");
var ViewGroupCls = Java.use("android.view.ViewGroup");
var TextViewCls  = Java.use("android.widget.TextView");

// id por CAMPO estático de R$id (acesso a campo não sofre do problema acima)
var RID = Java.use("com.beantechs.mediacenter.mainmodel1xos.R$id");
var RID_FIXO = { online_music: 0x7f0a01ba,
                 horizontalScrollView: 0x7f0a0107,
                 online_music_container: 0x7f0a01bb };
function resId(name) {
    try { if (RID[name] !== undefined) return RID[name].value; } catch (e) {}
    return RID_FIXO[name];
}

function viewOf(act, name) {
    return Java.cast(act, ActivityCls).findViewById(resId(name));
}
```

E use `.overload(...)` explícito em métodos sobrecarregados (`setText`, `setTextSize`, `setTag`,
`addView`, `setTextColor`, `Intent.$new`, `Array.newInstance`, `OnlineOsUtil.getProviderType`).

---

## 4. Eliminar a mídia online

Três níveis, do mais leve ao mais completo. O projeto atual expõe os três num arquivo de controle
(`/data/local/tmp/inject_media_cp`: CSV | `none` | `off` | `widget`), mas a mecânica é esta:

### 4.1 Fileira vazia (título permanece)

Devolva um `Integer[]` vazio para a cadeia. **Um `Integer[]` no Frida é um array JS de wrappers
`Integer`** — não use `java.lang.reflect.Array`, o marshaller de retorno rejeita o wrapper de
`Object` que ele devolve:

```js
var Integer = Java.use("java.lang.Integer");
function buildArr(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(Integer.valueOf(list[i]));
    return out;   // [] = nenhum ícone
}
```

Dois caminhos, e vale fazer os dois:

```js
// (a) hook nos 4 níveis da cadeia — se um divergir de versão, os outros pegam
[["com.beantechs.mediacenter.mediacentermodel.MediaCenterManager", "getCPList"],
 ["com.beantechs.mediacenter.mediacentermodel.core.OnlineOsImpl", "getCPList"],
 ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getCPList"],
 ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getMCpList"]
].forEach(function (p) {
    var w = Java.use(p[0]), name = p[1];
    w[name].implementation = function () { return buildArr(DESEJADO); };
});

// (b) escrever o campo nas instâncias vivas (passagem de ARGUMENTO, mais previsível
//     que marshaling de retorno) — o getter original passa a devolver a nossa lista
Java.choose("com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", {
    onMatch: function (inst) { inst.setMCpList(buildArr(DESEJADO)); },
    onComplete: function () {}
});
```

### 4.2 Bloco escondido (título + fileira)

```js
function setBlockVisible(act, visible) {
    var vis = visible ? 0 : 8;   // VISIBLE : GONE
    ["online_music", "horizontalScrollView"].forEach(function (n) {
        var v = viewOf(act, n);
        if (v !== null) Java.cast(v, ViewCls).setVisibility(vis);
    });
}
```

Rode na **main thread** e **não** logue sucesso fora do laço sem checar erro — foi assim que uma
versão nossa reportou "escondido" sem ter escondido nada.

### 4.3 Substituir por conteúdo próprio

Deixe o bloco **visível** (esconder o `HorizontalScrollView` esconde os seus cards junto), esvazie
a fileira via §4.1 e adicione suas views no `online_music_container` (§5). O título pode ser
reaproveitado com `setText` ou escondido sozinho.

---

## 5. Colocar cards próprios

### 5.1 Fundo, borda, cantos, imagem, texto

Tudo programático — não é possível adicionar recursos ao APK do OEM, e não é preciso:

```js
var GradientDrawable = Java.use("android.graphics.drawable.GradientDrawable");
var ImageView = Java.use("android.widget.ImageView");
var LinearLayout = Java.use("android.widget.LinearLayout");
var LLParams = Java.use("android.widget.LinearLayout$LayoutParams");
var BitmapFactory = Java.use("android.graphics.BitmapFactory");
var Str = Java.use("java.lang.String");

function novoCard(act, cfg) {          // cfg = {w,h,cor,borda,raio,rotulo,iconePath,pkgIcone,cp}
    var card = LinearLayout.$new.overload("android.content.Context").call(LinearLayout, act);
    card.setOrientation(1);            // VERTICAL

    var bg = GradientDrawable.$new();
    bg.setColor(cfg.cor);              // ex. 0xFF1A1A2E | 0
    bg.setCornerRadius(cfg.raio);      // float, px
    bg.setStroke(2, cfg.borda);        // espessura px, cor ARGB
    Java.cast(card, ViewCls).setBackground(bg);

    // imagem: arquivo solto (envie o PNG junto do script) ...
    if (cfg.iconePath) {
        var bmp = BitmapFactory.decodeFile.overload("java.lang.String")
            .call(BitmapFactory, cfg.iconePath);
        if (bmp !== null) {
            var iv = ImageView.$new.overload("android.content.Context").call(ImageView, act);
            iv.setImageBitmap(bmp);
            card.addView.overload("android.view.View").call(card, Java.cast(iv, ViewCls));
        }
    }
    // ... ou o ícone real de um app instalado
    if (cfg.pkgIcone) {
        var pm = Java.cast(act, ActivityCls).getPackageManager();
        var d = pm.getApplicationIcon.overload("java.lang.String").call(pm, cfg.pkgIcone);
        var iv2 = ImageView.$new.overload("android.content.Context").call(ImageView, act);
        Java.cast(iv2, ViewCls).setBackground(d);
        card.addView.overload("android.view.View").call(card, Java.cast(iv2, ViewCls));
    }

    if (cfg.rotulo) {
        var tv = TextViewCls.$new.overload("android.content.Context").call(TextViewCls, act);
        tv.setText.overload("java.lang.CharSequence").call(tv, Str.$new(cfg.rotulo));
        tv.setTextColor.overload("int").call(tv, -1);
        tv.setTextSize.overload("float").call(tv, 24.0);
        card.addView.overload("android.view.View").call(card, Java.cast(tv, ViewCls));
    }

    var lp = LLParams.$new.overload("int", "int").call(LLParams, cfg.w, cfg.h);
    lp.setMarginStart(cfg.margem || 0);
    Java.cast(card, ViewCls).setLayoutParams(Java.cast(lp, Java.use("android.view.ViewGroup$LayoutParams")));
    return card;
}
```

Feedback de toque: `RippleDrawable` ou `StateListDrawable`, também construídos em runtime.
Drawables do próprio OEM podem ser reusados por id (`R$drawable.selector_online_music_tunein`,
etc.) via `setBackgroundResource`.

### 5.2 Adicionar no container

```js
var box = Java.cast(viewOf(act, "online_music_container"), ViewGroupCls);
box.removeAllViews();
MEUS_CARDS.forEach(function (cfg) {
    box.addView.overload("android.view.View").call(box, Java.cast(novoCard(act, cfg), ViewCls));
});
```

Marque suas views com `setTag` e recupere com `findViewWithTag` — **nunca** guarde o wrapper da
view entre ticks (§7.4).

### 5.3 Clique abrindo um app

**Opção A — reusar o dispatch do OEM (recomendada, é barata).** A Activity já é a
`OnClickListener` e despacha por `elevation` na faixa `501..600`. Basta:

```js
Java.cast(card, ViewCls).setElevation(cp);                       // cp virtual, 501..600
Java.cast(card, ViewCls).setOnClickListener(Java.cast(act, Java.use("android.view.View$OnClickListener")));
```

e interceptar o destino:

```js
var ACOES = {                       // cp virtual -> o que abrir
    561: { pkg: "com.waze" },
    562: { url: "npwas://tunein.com/radio/music/" }
};
Java.use("com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl")
    .skipApp4OnlineOs.implementation = function (cp) {
        var a = ACOES[cp];
        if (a === undefined) return this.skipApp4OnlineOs(cp);   // deixa o OEM cuidar
        var ctx = Java.use("android.app.ActivityThread")
            .currentApplication().getApplicationContext();
        var i;
        if (a.pkg) {
            i = ctx.getPackageManager().getLaunchIntentForPackage(a.pkg);
        } else {
            var Intent = Java.use("android.content.Intent"), Uri = Java.use("android.net.Uri");
            i = Intent.$new.overload("java.lang.String", "android.net.Uri")
                .call(Intent, "android.intent.action.VIEW", Uri.parse(a.url));
            i.setPackage("com.beantechs.mediacenter.h5.ui");
        }
        if (i === null) return;
        i.addFlags(0x10000000);      // FLAG_ACTIVITY_NEW_TASK
        ctx.startActivity(i);
    };
```

Limite: 100 slots (`501..600`), e a `elevation` alta gera sombra — o OEM já faz isso, então
visualmente é aceitável; se incomodar, use a opção B.

**Opção B — listener próprio.** `Java.registerClass` implementando
`android.view.View$OnClickListener`. Sem limite de slots e sem abusar da `elevation`; custa
manter uma classe registrada (e um classloader válido) viva.

### 5.4 Abrir PWA sem provisionamento

Abrir por `launcher(id)` do OEM **exige** que o app esteja provisionado no
`com.beantechs.mediacenter.h5.core` (`CookieManager.getCookie(id)`; sem cookie → erro silencioso).
O caminho que sempre funciona é o intent acima, porque o content shell
`com.beantechs.mediacenter.h5.ui` é *exported* e aceita `npwa`/`npwas`. URLs no §8.

---

## 6. Ciclo de vida e reaplicação

- `loadOnlineMusicCard()` roda **só no `onCreate`**, e começa com `removeAllViews()` — qualquer
  recriação da Activity apaga seus cards. Reaplique depois de chamá-lo.
- Para aplicar mudanças **sem reiniciar** o app, chame `loadOnlineMusicCard()` na instância viva e
  em seguida redesenhe o seu conteúdo:

```js
function repaint() {
    var act = null;
    Java.choose(ACT, { onMatch: function (i) { act = i; return "stop"; }, onComplete: function () {} });
    if (act === null) return;                        // tela fechada: vale no próximo onCreate
    Java.scheduleOnMainThread(function () {
        act.loadOnlineMusicCard();                   // método da própria classe: acessível
        setBlockVisible(act, true);
        desenharMeusCards(act);
    });
}
```

- **Não** use `am force-stop` para "aplicar": isso mata o processo e com ele a injeção.
- Um arquivo de controle em `/data/local/tmp` + `setInterval` de ~1,5 s é suficiente para mudar
  configuração em runtime sem reinjetar.

---

## 7. Armadilhas (cada uma custou um ciclo de teste no carro)

### 7.1 Byte de controle no script
Um `\0` dentro de uma string do `.js` faz o parser do Frida abortar com
`SyntaxError: unexpected end of string` — o script **inteiro** não roda, e isso não aparece em
build nem na UI, só no log da injeção. Valide os arquivos no build:

```bash
python -c "d=open('script.js','rb').read(); print([ (i,b) for i,b in enumerate(d) if b<9 or (11<=b<32 and b!=13) ])"
```

### 7.2 `Integer[]` montado errado
`java.lang.reflect.Array.newInstance` devolve wrapper de `Object`, e o marshaller de **retorno**
não aceita. Use array JS de wrappers `Integer` (§4.1). Preferir passagem por **argumento**
(`setMCpList`) quando possível.

### 7.3 Método herdado no wrapper do `Java.choose`
`TypeError: not a function` em `getResources`/`findViewById`. Solução: `Java.cast` para a classe
declarante e ids por `R$id` (§3).

### 7.4 Guardar `this` de um hook
`cachedView = this` dentro de uma implementação e reusar depois → 
`JNI DETECTED ERROR IN APPLICATION: use of invalid jobject` e o ART **aborta o processo**
(SIGABRT). `try/catch` em JS não segura isso. Regra: procure a instância (`Java.choose`) e use no
mesmo tick. Resultados de `Java.choose` são referências globais do bridge e podem ser usados
dentro do tick; `this` de hook é **local**.

### 7.5 Refactor que apaga helper
Um patch textual removeu a definição de `buildArr` deixando duas chamadas órfãs; só o caminho da
lista quebrou, e os outros estados continuaram funcionando — passou despercebido. Coloque um
**autoteste na injeção**:

```js
(function selfTest() {
    var falhas = [];
    [["buildArr", function () { return buildArr([551]).length === 1; }],
     ["resId",    function () { return resId("online_music") > 0; }]
    ].forEach(function (p) {
        try { if (p[1]() !== true) falhas.push(p[0]); } catch (e) { falhas.push(p[0] + ": " + e); }
    });
    log(falhas.length ? "autoteste FALHOU -> " + falhas.join(" | ") : "autoteste: OK");
})();
```

### 7.6 Log que mente
Mensagem de sucesso fora do `try`/depois do laço reporta "aplicado" sem ter aplicado. Logue o
resultado real, e use `logOnce` por mensagem — um erro por tick vira centenas de linhas idênticas
e esconde o resto.

### 7.7 Versão do Frida
**16.x** (validado: 16.7.19). O Frida 17 removeu o bridge global `Java`, que scripts injetados
crus usam.

---

## 8. Referência

### CPs desenháveis pelo `switch` do OEM

| CP | app | drawable |
|---|---|---|
| 503 / 504 | JOOX / myTuner | `selector_online_music_joox` / `_mytuner` |
| 550 | Amazon Music | `selector_online_music_amazon` |
| 551 | Deezer | `selector_online_music_deezer` |
| 552 | YouTube | `selector_online_music_youtube` |
| 553 | TuneIn | `selector_online_music_tunein` |
| 554 | DAZN | `selector_online_music_dazn` |
| 555 | Reuters TV | `selector_online_music_reuters` |
| 556 | Radioline | `selector_online_music_radio_line` |
| 557 e qualquer outro | ESPN (é o `else` do switch) | `selector_online_music_espn` |

Só a faixa `501..600` é aceita pelo `onClick` — daí os "cp virtuais" da §5.3.

### URLs `npwas://` (catálogo `h5.ui/res/raw/app_list.txt`, 29 apps)

| app | URL |
|---|---|
| TuneIn | `npwas://tunein.com/radio/music/` |
| Radioline | `npwas://access-bt-gwm-9zcb4r.radioline.co/` |
| Deezer | `npwas://ver.netrange.com/twine4car.deezer/production/v1/index.html` |
| Reuters TV | `npwas://www.reuters.com/video/?ua=mobile` |
| Amazon Music | `npwas://music.amazon.com/?ua=mobile` |
| YouTube | `npwas://www.youtube.com/?app=desktop&persist_app=1&ua=default` |
| DAZN / Pluto TV / CNN | `npwas://dazn.com` / `npwas://pluto.tv/` / `npwas://edition.cnn.com/videos` |

### Provisionamento real nesta central (`PROVIDER_TYPE_MAP`, vindo do `h5.core`)

Provisionados: `Deezer`, `Youtube`, `YouTube (mobile)`, `ESPN`, `Reuter TV`, `Apple Music`.
Vazios (não provisionados): `TuneIn`, `Radioline`, `Amazon`, `Dazn`, `Reuters TV`, `JOOX`,
`MY_TUNER`, `KIDOMI`, `PLUTO_TV`, `EURONEWS`, `STINGRAY_MUSIC`, `WALL_STREET`,
`GOGGLES_CARTOONS`, `DEUTSCHE_WELLE_TV`.

Atenção a nomes divergentes: o OEM procura `Reuters TV`/`Amazon`/`Dazn`, o servidor provisionou
`Reuter TV`/`Amazon Music`/`DAZN`. Resolver por alias faz o caminho OEM voltar a funcionar.

### Dados do veículo (para widgets)

`PlatformAdapterClient.getInstance().getData(chave)` — a classe existe no APK da MediaCenter.
Confirmadas: `car.basic.outside_temp` e `car.basic.inside_temp` (ambas `float` em string).

---

## 9. Checklist de validação

1. Log da injeção mostra os hooks aplicados e `autoteste: OK`.
2. `getCPList()` real logado no start (esperado `[551]` no Brasil) — prova o processo certo.
3. Fileira vazia: nenhum ícone, título presente.
4. Bloco escondido: título e fileira ausentes, **sem** `TypeError` no log.
5. Cards próprios: aparecem, com borda/cor/ícone; scroll horizontal funciona.
6. Clique em cada card abre o alvo; log registra `cp` e ação.
7. Fechar e reabrir a tela de mídia: cards voltam (reaplicação no `onCreate`).
8. Rodar 10+ min com a tela aberta e fechada: nenhum `invalid jobject`, nenhum crash do processo.

---

## 10. Arquivos deste repositório para copiar

| arquivo | o que tem |
|---|---|
| [app/src/main/res/raw/com_beantechs_mediacenter_cp.js](../app/src/main/res/raw/com_beantechs_mediacenter_cp.js) | script completo e funcionando: hooks, estados, widget, autoteste |
| [app/src/main/java/.../utils/FridaUtils.java](../app/src/main/java/br/com/redesurftank/havalenginereverse/utils/FridaUtils.java) | injeção via Shizuku (extrair binários, subir server, achar pid, injetar), arquivo de controle, diagnóstico |
| [app/src/main/java/.../MediaCardScreen.kt](../app/src/main/java/br/com/redesurftank/havalenginereverse/MediaCardScreen.kt) | UI de controle (Compose) |
| [app/build.gradle.kts](../app/build.gradle.kts) | task `checkFridaScripts` (§7.1) |
| [docs/mediacenter-online-cards.md](mediacenter-online-cards.md) | análise completa, com o caminho do bytecode |
