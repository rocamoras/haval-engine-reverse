package br.com.redesurftank.havalenginereverse

import android.content.Context
import android.util.Log
import br.com.redesurftank.havalenginereverse.utils.TelnetClientWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASES_API =
    "https://api.github.com/repos/rocamoras/haval-engine-reverse/releases/latest"

/**
 * "Sobe pela central": baixa o APK da release/latest e instala via o telnet
 * root local (127.0.0.1:23) — sem cabo, sem adb, sem o tap do instalador.
 *
 * Fluxo (o shell do telnet é root — prompt ":/ #"):
 *   1. app baixa o APK no cache privado (sem permissão de storage)
 *   2. root: cp cache -> /data/local/tmp (contexto que o installd consegue ler)
 *   3. root: pm install -r -d /data/local/tmp/her.apk   (silencioso)
 *   4. limpa os temporários
 *
 * `onLog` é sempre chamado na Main thread. Retorna true se "Success".
 */
suspend fun installLatestViaTelnet(
    context: Context,
    onLog: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    val tag = "SelfUpdate"
    suspend fun log(m: String) { Log.d(tag, m); withContext(Dispatchers.Main) { onLog(m) } }

    try {
        // 1) resolve a URL do APK na release/latest
        log("Buscando release…")
        val (url, tagName) = withContext(Dispatchers.IO) {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            val json = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
            val root = JSONObject(json)
            val assets = root.getJSONArray("assets")
            var found = ""
            for (i in 0 until assets.length()) {
                val n = assets.getJSONObject(i).getString("name").lowercase()
                if (n.endsWith(".apk") && (n.contains("release") || n.contains("fat") || n.contains("frida"))) {
                    found = assets.getJSONObject(i).getString("browser_download_url"); break
                }
            }
            if (found.isEmpty()) for (i in 0 until assets.length()) {
                if (assets.getJSONObject(i).getString("name").lowercase().endsWith(".apk")) {
                    found = assets.getJSONObject(i).getString("browser_download_url"); break
                }
            }
            found to root.optString("tag_name", "?")
        }
        if (url.isEmpty()) { log("Nenhum .apk na release"); return@withContext false }

        // 2) baixa pro cache privado (sem permissão)
        log("Baixando $tagName…")
        val apk = File(context.cacheDir, "her-update.apk")
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 120000; conn.connect()
            val total = conn.contentLength.toLong()
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(apk).use { output ->
                    val buf = ByteArray(8192); var read = 0L; var n: Int; var lastPct = -1
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n); read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct && pct % 10 == 0) { lastPct = pct; log("Baixando… $pct%") }
                        }
                    }
                }
            }
            conn.disconnect()
        }
        log("Baixado: ${apk.length() / 1024 / 1024} MB")

        // 3) instala via telnet root
        val cache = apk.absolutePath
        val tmp = "/data/local/tmp/her-update.apk"
        var telnet: TelnetClientWrapper? = null
        val result = try {
            telnet = TelnetClientWrapper()
            telnet.connect("127.0.0.1", 23)
            log("Telnet root OK — copiando…")
            telnet.executeCommand("cp \"$cache\" $tmp && chmod 644 $tmp && echo CP_OK", 30000)
            log("pm install… (silencioso)")
            val out = telnet.executeCommand("pm install -r -d $tmp 2>&1", 120000)
            telnet.executeCommand("rm -f $tmp", 8000)
            out
        } finally {
            try { telnet?.disconnect() } catch (_: Exception) {}
        }
        try { apk.delete() } catch (_: Exception) {}

        val ok = result.contains("Success", ignoreCase = true)
        if (ok) log("✓ Instalado ($tagName). O app vai reiniciar.")
        else log("Falhou: ${result.ifBlank { "(sem saída)" }}")
        return@withContext ok
    } catch (e: Exception) {
        log("Erro: ${e.message}")
        return@withContext false
    }
}
