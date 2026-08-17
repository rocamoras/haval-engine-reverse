/*
 * Injeção na MediaCenter OEM (com.beantechs.mediacenter) — escolhe QUAIS ícones
 * de mídia online aparecem no card da tela principal do app de mídia.
 *
 * Descoberta por engenharia reversa (docs/mediacenter-online-cards.md):
 *   MediaCenterActivity.loadOnlineMusicCard() desenha um ImageView por id de CP
 *   devolvido por MediaCenterManager.getCPList() -> ... -> OnlineOsModelImpl.getMCpList().
 *   A lista é fixa por país (persist.bean.country.code): no Brasil (17) é só
 *   [551 Deezer]. O id do CP vai na `elevation` da view; o clique lê de volta e
 *   chama skipApp4OnlineOs(cp) -> OnlineOsUtil.getProviderType(cp) (nome)
 *   -> ProviderTypeManager.getProviderType(nome) (id do app H5) -> launcher(id).
 *
 * Estratégia:
 *   1) Lê /data/local/tmp/inject_media_cp — CSV de ids (ex. "553" ou "551,553,556").
 *      Conteúdo "none" (ou linha vazia) = lista vazia -> nenhum ícone.
 *      Arquivo ausente = não interfere (comportamento original).
 *   2) Hooka OnlineOsModelImpl.getMCpList() devolvendo a lista desejada.
 *   3) Quando o arquivo muda, acha a MediaCenterActivity viva (Java.choose) e
 *      chama loadOnlineMusicCard() na main thread — repinta sem reiniciar o app
 *      (loadOnlineMusicCard só roda no onCreate).
 *   4) Rede de segurança: ProviderTypeManager.getProviderType devolve "" quando o
 *      PWA não está na lista instalada que o serviço H5 reporta. Nesse caso
 *      injetamos o id do catálogo embutido (h5.ui/res/raw/app_list.txt).
 */
"use strict";

function log(m) { console.log("[mc-cp] " + m); }

Java.perform(function () {
    var CTRL = "/data/local/tmp/inject_media_cp";
    var ACT  = "com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity";

    var JFile      = Java.use("java.io.File");
    var FileReader = Java.use("java.io.FileReader");
    var BufReader  = Java.use("java.io.BufferedReader");
    var Integer    = Java.use("java.lang.Integer");
    var IMPL_CLS   = "com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl";

    // ids do catálogo embutido do h5.ui (res/raw/app_list.txt), por nome do
    // ProviderTypeManager. Usado só se o mapa real vier vazio.
    var IDS = {
        "Deezer":     "6131F97C0C42A60001000002",
        "TuneIn":     "611D7A9A0C42A60001000017",
        "Radioline":  "611D6F1F0C42A60001000016",
        "Reuters TV": "61238F990C42A6000100001A",
        "Youtube":    "6181A5990C42A6000100000C",
        "Amazon":     "615C3EEE0C42A60001000003",
        "Dazn":       "6176B10C0C42A60001000008"
    };

    // URL npwas:// de cada CP (h5.ui/res/raw/app_list.txt). O content shell do
    // h5.ui é exported e aceita VIEW com scheme npwa/npwas, então isso abre a PWA
    // sem depender do provisionamento no com.beantechs.mediacenter.h5.core.
    var URLS = {
        550: "npwas://music.amazon.com/?ua=mobile",
        551: "npwas://ver.netrange.com/twine4car.deezer/production/v1/index.html",
        552: "npwas://www.youtube.com/?app=desktop&persist_app=1&ua=default",
        553: "npwas://tunein.com/radio/music/",
        554: "npwas://dazn.com",
        555: "npwas://www.reuters.com/video/?ua=mobile",
        556: "npwas://access-bt-gwm-9zcb4r.radioline.co/"
    };
    var H5_UI_PKG = "com.beantechs.mediacenter.h5.ui";

    // null = não interferir; [] = lista vazia; [551,...] = lista fixa.
    var desired = null;
    var lastRaw = null;        // marcador "ainda não li"

    function readCtrl() {
        try {
            var f = JFile.$new(CTRL);
            if (!f.exists()) return null;
            var br = BufReader.$new(FileReader.$new(f));
            var line = br.readLine();
            br.close();
            var raw = (line === null ? "" : ("" + line)).trim();
            if (raw === "" || raw.toLowerCase() === "none") return [];
            var out = [];
            raw.split(",").forEach(function (p) {
                var n = parseInt(("" + p).trim(), 10);
                // o onClick da Activity só aceita 501..600
                if (!isNaN(n) && n >= 501 && n <= 600) out.push(n);
            });
            return out;
        } catch (e) {
            log("readCtrl err: " + e);
            return null;
        }
    }

    function rawOf(list) { return list === null ? "(passthrough)" : list.join(","); }

    // Um Integer[] pro Frida é um array JS de wrappers Integer — é assim que o
    // bridge marshala tanto em argumento quanto em retorno. (Tentar montar via
    // java.lang.reflect.Array devolve um wrapper de Object que o marshaller de
    // retorno não aceita — foi o erro da v2.21.x.)
    function buildArr(list) {
        var out = [];
        for (var i = 0; i < list.length; i++) out.push(Integer.valueOf(list[i]));
        return out;
    }

    // (2) a lista que a tela e o serviço enxergam. Hooka os QUATRO níveis da
    // cadeia (MediaCenterManager -> OnlineOsImpl -> OnlineOsModelImpl.getCPList
    // -> getMCpList): se algum nível estiver diferente nesta versão do APK, os
    // outros ainda pegam, e o log diz exatamente qual aplicou.
    var CHAIN = [
        ["com.beantechs.mediacenter.mediacentermodel.MediaCenterManager", "getCPList"],
        ["com.beantechs.mediacenter.mediacentermodel.core.OnlineOsImpl", "getCPList"],
        ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getCPList"],
        ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getMCpList"]
    ];

    CHAIN.forEach(function (pair) {
        var cls = pair[0], name = pair[1];
        try {
            var w = Java.use(cls);
            var orig = w[name];
            if (orig === undefined) { log("SEM método " + cls + "." + name); return; }
            orig.implementation = function () {
                if (desired === null) return this[name]();
                try {
                    return buildArr(desired);
                } catch (e) {
                    log("buildArr err: " + e);
                    return this[name]();
                }
            };
            log("hook OK " + cls.split(".").pop() + "." + name);
        } catch (e) {
            log("hook FALHOU " + cls + "." + name + ": " + e);
        }
    });

    // Autoteste: prova em qual processo estamos e o que a cadeia devolve HOJE.
    try {
        var MCM = Java.use("com.beantechs.mediacenter.mediacentermodel.MediaCenterManager");
        var inst = MCM.getInstance();
        if (inst === null) {
            log("autoteste: MediaCenterManager.getInstance() = null (serviço não subiu ainda)");
        } else {
            var cur = inst.getCPList();
            var acc = [];
            if (cur !== null) {
                for (var i = 0; i < cur.length; i++) acc.push("" + cur[i]);
            }
            log("autoteste: getCPList() atual = [" + acc.join(",") + "]");
        }
    } catch (e) {
        log("autoteste falhou: " + e);
    }
    try {
        log("processo = " + Java.use("android.app.ActivityThread")
            .currentProcessName());
    } catch (e) { /* API interna pode não existir */ }

    // (4) fallback do id do app H5.
    var loggedFallback = {};
    try {
        var PTM = Java.use("com.beantechs.mediacenter.h5.sdk.ProviderTypeManager");
        PTM.getProviderType.implementation = function (name) {
            var r = this.getProviderType(name);
            if ((r === null || ("" + r) === "") && name !== null && IDS[("" + name)]) {
                var id = IDS[("" + name)];
                if (!loggedFallback[("" + name)]) {
                    loggedFallback[("" + name)] = true;
                    log("providerType(" + name + ") vazio no mapa real -> id do catálogo " + id);
                }
                return id;
            }
            return r;
        };
        log("hook ProviderTypeManager aplicado");
    } catch (e) {
        log("falha ao hookar ProviderTypeManager: " + e);
    }

    /**
     * (2b) Caminho principal, que NÃO depende do marshaling de retorno:
     * escreve a lista direto no campo `mCpList` das instâncias vivas de
     * OnlineOsModelImpl, via setMCpList(Integer[]). Aí o getter original já
     * devolve a nossa lista, com ou sem o hook do item (2).
     */
    function applyToInstances() {
        if (desired === null) return 0;
        var n = 0;
        try {
            var arr = buildArr(desired);
            Java.choose(IMPL_CLS, {
                onMatch: function (inst) {
                    try { inst.setMCpList(arr); n++; }
                    catch (e) { log("setMCpList err: " + e); }
                },
                onComplete: function () {}
            });
        } catch (e) {
            log("applyToInstances err: " + e);
        }
        log("setMCpList aplicado em " + n + " instância(s)");
        return n;
    }

    /**
     * (3) repinta a fileira de ícones sem reiniciar o app.
     *
     * Sem cache da Activity de propósito: um wrapper guardado entre ticks pode
     * virar jobject inválido e o ART ABORTA o processo ("JNI DETECTED ERROR IN
     * APPLICATION: use of invalid jobject") — foi assim que a launcher caiu com
     * o hook de clima. Aqui a instância é procurada e usada no mesmo tick.
     */
    function repaint() {
        var act = null;
        try {
            Java.choose(ACT, {
                onMatch: function (inst) { act = inst; return "stop"; },
                onComplete: function () {}
            });
        } catch (e) {
            log("choose err: " + e);
            return;
        }
        if (act === null) {
            log("MediaCenterActivity não está viva — abra a tela de mídia (vale no próximo onCreate)");
            return;
        }
        Java.scheduleOnMainThread(function () {
            try {
                act.loadOnlineMusicCard();
                log("card repintado: [" + rawOf(desired) + "]");
            } catch (e) {
                log("repaint err (activity morta?): " + e);
            }
        });
    }


    /**
     * (5) O clique do card chama skipApp4OnlineOs(cp), que termina em
     * IAppControlCall.launcher(id) — e esse id só vale se o app estiver
     * provisionado no com.beantechs.mediacenter.h5.core (o mapa do
     * ProviderTypeManager vem DELE, via getProviderAppList). Quando o mapa real
     * não tem o nome, o launcher(id) não abre nada. Aí abrimos a PWA na mão,
     * por Intent VIEW npwas:// no content shell do h5.ui (exported/BROWSABLE).
     */
    function realProviderId(name) {
        try {
            var map = Java.use("com.beantechs.mediacenter.h5.sdk.ProviderTypeManager")
                .PROVIDER_TYPE_MAP.value;
            var v = map.get(name);
            return (v === null) ? "" : ("" + v);
        } catch (e) {
            log("realProviderId err: " + e);
            return "";
        }
    }

    function openPwa(url) {
        try {
            var ctx = Java.use("android.app.ActivityThread")
                .currentApplication().getApplicationContext();
            var Intent = Java.use("android.content.Intent");
            var Uri = Java.use("android.net.Uri");
            var i = Intent.$new.overload("java.lang.String", "android.net.Uri")
                .call(Intent, "android.intent.action.VIEW", Uri.parse(url));
            i.setPackage(H5_UI_PKG);
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i);
            log("aberto por intent: " + url);
            return true;
        } catch (e) {
            log("openPwa err: " + e);
            return false;
        }
    }

    try {
        var OnlineOsUtil = Java.use("com.beantechs.mediacenter.core_onlineosmodel.utils.OnlineOsUtil");
        Java.use(IMPL_CLS).skipApp4OnlineOs.implementation = function (cp) {
            var name = "";
            try {
                // getProviderType tem 3 sobrecargas — precisa desambiguar.
                name = "" + OnlineOsUtil.INSTANCE.value
                    .getProviderType.overload("int").call(OnlineOsUtil.INSTANCE.value, cp);
            } catch (e) { log("getProviderType(" + cp + ") err: " + e); }
            var provisioned = (name === "") ? "" : realProviderId(name);
            log("clique cp=" + cp + " nome=" + name +
                " provisionado=" + (provisioned === "" ? "NÃO" : provisioned));
            if (provisioned !== "") return this.skipApp4OnlineOs(cp);   // caminho OEM
            var url = URLS[cp];
            if (url !== undefined && openPwa(url)) return;
            return this.skipApp4OnlineOs(cp);   // sem URL conhecida: deixa o OEM tentar
        };
        log("hook OK skipApp4OnlineOs (clique)");
    } catch (e) {
        log("hook FALHOU skipApp4OnlineOs: " + e);
    }

    // Diagnóstico: o que o provisionamento conhece, e se o h5.core existe.
    try {
        var map = Java.use("com.beantechs.mediacenter.h5.sdk.ProviderTypeManager")
            .PROVIDER_TYPE_MAP.value;
        log("PROVIDER_TYPE_MAP = " + map.toString());
    } catch (e) { log("dump do mapa falhou: " + e); }
    try {
        var pm = Java.use("android.app.ActivityThread")
            .currentApplication().getApplicationContext().getPackageManager();
        ["com.beantechs.mediacenter.h5.core", "com.beantechs.mediacenter.h5.ui"].forEach(function (p) {
            var ok = true;
            try { pm.getPackageInfo(p, 0); } catch (e) { ok = false; }
            log("pacote " + p + " instalado = " + ok);
        });
    } catch (e) { log("check de pacotes falhou: " + e); }

    setInterval(function () {
        Java.perform(function () {
            var list = readCtrl();
            var raw = rawOf(list);
            if (raw === lastRaw) return;
            lastRaw = raw;
            desired = list;
            log("lista desejada = [" + raw + "]");
            applyToInstances();
            repaint();
        });
    }, 1500);

    log("injetor de card de mídia online ativo (alvo com.beantechs.mediacenter)");
});
