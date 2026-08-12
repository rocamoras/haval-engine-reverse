/*
 * Hook do SystemUI (com.android.systemui) — Haval H6 GT.
 *
 * Descoberta por engenharia reversa: com.bean.statusbar.BeanCarStatusBarView.onOutsideTempUpdate(float)
 * JÁ recebe o sensor real (car.basic.outside_temp), mas retorna cedo por causa de
 * BuildConfig.type (só exibe quando type == 1 || type == 3; neste carro type == 2).
 * Aqui reimplementamos o método ignorando essa trava: escrevemos o sensor real na
 * mesma TextView (mWeatherTemp) que a barra usa para a temperatura.
 */
"use strict";

function log(m) { console.log("[sysui-outtemp] " + m); }

Java.perform(function () {
    try {
        var View = Java.use("com.bean.statusbar.BeanCarStatusBarView");

        View.onOutsideTempUpdate.overload("float").implementation = function (temp) {
            try {
                var tv = this.mWeatherTemp.value;
                if (tv === null) { return; }
                if (temp > -40.0 && temp < 86.5) {
                    var s = Math.round(temp) + "°"; // ex.: "28°"
                    // setText(CharSequence) na thread em que o método original roda (UI).
                    tv.setText.overload("java.lang.CharSequence").call(tv, Java.use("java.lang.String").$new(s));
                }
            } catch (e) {
                log("erro no update: " + e);
            }
        };

        log("hook aplicado em BeanCarStatusBarView.onOutsideTempUpdate");
    } catch (e) {
        log("falha ao aplicar hook: " + e);
    }
});
