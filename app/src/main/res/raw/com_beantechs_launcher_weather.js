/*
 * Injeção na launcher OEM (com.beantechs.launcher) — envia a temperatura pra
 * central "como se fosse o app de clima".
 *
 * Descoberta por engenharia reversa:
 *   HiBoardView.parseWeather(CommonNowWeather) é quem pinta o card de clima da
 *   home (temperatura_tv, weather_tv, ícone, min/max). O app de clima OEM só
 *   chega até aqui via callback AIDL — não há API de push.
 *
 * Estratégia:
 *   1) Lê /data/local/tmp/inject_weather  (formato: tmp|condCode|condTxt|min|max)
 *   2) Fabrica um CommonNowWeather (ctor de 7 args; getters têm guarda de null)
 *      e chama HiBoardView.parseWeather na main thread (push ativo).
 *   3) Hooka parseWeather p/ sobrescrever qualquer callback real com o nosso
 *      valor (fica "grudento" — o serviço real não reverte).
 */
"use strict";

function log(m) { console.log("[launcher-wx] " + m); }

Java.perform(function () {
    var CTRL = "/data/local/tmp/inject_weather";
    var JFile      = Java.use("java.io.File");
    var FileReader = Java.use("java.io.FileReader");
    var BufReader  = Java.use("java.io.BufferedReader");
    var W  = Java.use("com.beantechs.weatherservice.entity.CommonNowWeather");
    var HM = Java.use("com.beantechs.launcher.hiboard.HiBoardManager");
    var HiBoardView = Java.use("com.beantechs.launcher.hiboard.HiBoardView");

    // Estado desejado: {tmp, code, txt, min, max}. null = sem injeção.
    var desired = null;

    function readCtrl() {
        try {
            var f = JFile.$new(CTRL);
            if (!f.exists()) return null;
            var br = BufReader.$new(FileReader.$new(f));
            var line = br.readLine();
            br.close();
            if (line === null) return null;
            var p = ("" + line).split("|");
            var tmp = (p[0] || "").trim();
            if (tmp === "") return null;
            return {
                tmp:  tmp,
                code: (p[1] || "100").trim(),
                txt:  (p[2] || "").trim(),
                min:  (p[3] || tmp).trim(),
                max:  (p[4] || tmp).trim()
            };
        } catch (e) { return null; }
    }

    var ctor7 = W.$new.overload(
        "com.beantechs.weatherservice.entity.CommonNowWeather$BasicBean",
        "java.lang.String", "java.lang.String", "java.lang.String",
        "java.lang.String", "java.lang.String",
        "com.beantechs.weatherservice.entity.CommonNowWeather$UpdateBean"
    );

    function build(d) {
        // basic=null e update=null → getDcname()/getLoc() retornam "" (sem NPE).
        return ctor7.call(W, null, d.code, d.txt, d.tmp, d.max, d.min, null);
    }

    // (3) sticky: sobrescreve o callback real com o nosso valor.
    try {
        HiBoardView.parseWeather.implementation = function (now) {
            if (desired !== null && now !== null) {
                try {
                    now.tmp.value      = desired.tmp;
                    now.condCode.value = desired.code;
                    now.condTxt.value  = desired.txt;
                    now.tmp_min.value  = desired.min;
                    now.tmp_max.value  = desired.max;
                } catch (e) { log("override err: " + e); }
            }
            return this.parseWeather(now); // chama o original
        };
        log("hook parseWeather aplicado");
    } catch (e) {
        log("falha ao hookar parseWeather: " + e);
    }

    // (2) push ativo: mesmo sem callback real, força o nosso valor no card.
    function pushNow() {
        if (desired === null) return;
        var view = null;
        try { view = HM.INSTANCE.value.getInstance().getMHiBoardView(); } catch (e) { return; }
        if (view === null) return; // gaveta ainda não criada
        var w;
        try { w = build(desired); } catch (e) { log("build err: " + e); return; }
        Java.scheduleOnMainThread(function () {
            try { view.parseWeather(w); } catch (e) { log("push err: " + e); }
        });
    }

    setInterval(function () {
        desired = readCtrl();
        pushNow();
    }, 1500);

    log("injetor de clima ativo (alvo com.beantechs.launcher)");
});
