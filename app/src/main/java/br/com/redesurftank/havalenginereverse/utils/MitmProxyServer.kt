package br.com.redesurftank.havalenginereverse.utils

import android.util.Log
import br.com.redesurftank.havalenginereverse.EngineReverseStateHolder
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket

/**
 * Servidor HTTPS man-in-the-middle.
 *
 * Fluxo:
 *  widget → /etc/hosts (gwmcloud → 127.0.0.1) → iptables REDIRECT :443→:8443
 *       → MitmProxyServer :8443 → TLS termination → loga request
 *       → OkHttp (DNS direto a 8.8.8.8) → servidor real → resposta de volta
 */
class MitmProxyServer(
    val port: Int = 8443,
    private val onIntercepted: (EngineReverseStateHolder.ProxyEntry) -> Unit
) {
    private var serverSocket: SSLServerSocket? = null
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val idCounter  = AtomicLong(0)
    private val timeFmt    = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // OkHttp com DNS customizado — não usa /etc/hosts do sistema
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = resolveDirect(hostname)
            })
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun isRunning() = serverSocket != null && !serverSocket!!.isClosed

    fun start(sslContext: SSLContext) {
        if (isRunning()) return
        val ssf = sslContext.serverSocketFactory as SSLServerSocketFactory
        val ss  = ssf.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = false
        serverSocket = ss

        scope.launch {
            while (isActive) {
                try {
                    val client = ss.accept() as SSLSocket
                    launch { handle(client) }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "accept: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        serverSocket?.close()
        serverSocket = null
    }

    // ── Lida com uma conexão TLS ──────────────────────────────────────────

    private suspend fun handle(sock: SSLSocket) = withContext(Dispatchers.IO) {
        try {
            sock.use {
                val reader = BufferedReader(InputStreamReader(it.inputStream))
                val out    = it.outputStream

                // Linha de requisição (ex: "GET /api/v1/weather HTTP/1.1")
                val reqLine = reader.readLine() ?: return@withContext
                val parts   = reqLine.split(" ")
                if (parts.size < 2) return@withContext
                val method = parts[0]
                val path   = parts[1]

                // Headers
                val headers = linkedMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                    line = reader.readLine()
                }

                // Body
                val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
                val reqBodyBytes = if (contentLen > 0) {
                    val cbuf = CharArray(contentLen); reader.read(cbuf)
                    String(cbuf).toByteArray(Charsets.UTF_8)
                } else ByteArray(0)

                val host = headers["host"] ?: ProxyCA.TARGET_DOMAIN
                val url  = "https://$host$path"

                // Monta requisição OkHttp
                val reqBuilder = Request.Builder().url(url)
                headers.forEach { (k, v) ->
                    if (k != "host" && k != "content-length") reqBuilder.header(k, v)
                }
                val reqBody = when {
                    reqBodyBytes.isNotEmpty() ->
                        reqBodyBytes.toRequestBody(headers["content-type"]?.toMediaTypeOrNull())
                    method in listOf("POST", "PUT", "PATCH") ->
                        ByteArray(0).toRequestBody(null)
                    else -> null
                }
                reqBuilder.method(method, reqBody)

                // Encaminha ao servidor real
                val resp      = httpClient.newCall(reqBuilder.build()).execute()
                val respBytes = resp.body?.bytes() ?: ByteArray(0)

                // Devolve resposta ao cliente (widget)
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${resp.code} ${resp.message}\r\n")
                resp.headers.forEach { (k, v) ->
                    if (!k.equals("transfer-encoding", ignoreCase = true) &&
                        !k.equals("content-encoding",  ignoreCase = true))
                        sb.append("$k: $v\r\n")
                }
                sb.append("content-length: ${respBytes.size}\r\n\r\n")
                out.write(sb.toString().toByteArray())
                out.write(respBytes)
                out.flush()

                // Registra entrada interceptada
                val entry = EngineReverseStateHolder.ProxyEntry(
                    id              = idCounter.getAndIncrement(),
                    time            = timeFmt.format(Date()),
                    method          = method,
                    host            = host,
                    path            = path,
                    requestHeaders  = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                    requestBody     = String(reqBodyBytes),
                    responseCode    = resp.code,
                    responseHeaders = resp.headers.toString(),
                    responseBody    = String(respBytes)
                )
                withContext(Dispatchers.Main) { onIntercepted(entry) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle: ${e.message}")
        }
    }

    // ── DNS direto a 8.8.8.8 — ignora /etc/hosts ─────────────────────────

    private fun resolveDirect(hostname: String): List<InetAddress> {
        return try {
            val txid   = Random().nextInt(65535)
            val query  = buildDnsQuery(hostname, txid)
            val socket = DatagramSocket().also { it.soTimeout = 3000 }
            val server = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
            socket.send(DatagramPacket(query, query.size, server, 53))
            val buf  = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            socket.close()
            parseDnsARecords(buf, resp.length).ifEmpty { fallbackIps() }
        } catch (_: Exception) {
            fallbackIps()
        }
    }

    private fun fallbackIps(): List<InetAddress> = listOf(
        InetAddress.getByAddress(byteArrayOf(47, 131.toByte(), 72, 159.toByte())),
        InetAddress.getByAddress(byteArrayOf(54, 169.toByte(), 29, 203.toByte()))
    )

    private fun buildDnsQuery(hostname: String, txid: Int): ByteArray {
        val qname = mutableListOf<Byte>()
        hostname.split(".").forEach { label ->
            qname.add(label.length.toByte())
            label.forEach { qname.add(it.code.toByte()) }
        }
        qname.add(0)
        return ByteArray(12 + qname.size + 4).also { b ->
            b[0] = (txid shr 8).toByte(); b[1] = txid.toByte()
            b[2] = 1; b[5] = 1  // flags RD=1, QDCOUNT=1
            qname.forEachIndexed { i, v -> b[12 + i] = v }
            val q = 12 + qname.size
            b[q] = 0; b[q+1] = 1    // QTYPE  A
            b[q+2] = 0; b[q+3] = 1  // QCLASS IN
        }
    }

    private fun parseDnsARecords(buf: ByteArray, len: Int): List<InetAddress> {
        val results = mutableListOf<InetAddress>()
        try {
            val ancount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if (ancount == 0) return results
            var pos = 12
            // Pula QNAME da seção de perguntas
            while (pos < len) {
                if (buf[pos] == 0.toByte()) { pos++; break }
                if ((buf[pos].toInt() and 0xC0) == 0xC0) { pos += 2; break }
                pos += (buf[pos].toInt() and 0xFF) + 1
            }
            pos += 4 // QTYPE + QCLASS
            // Parse das respostas
            repeat(ancount) {
                if (pos >= len) return@repeat
                if ((buf[pos].toInt() and 0xC0) == 0xC0) pos += 2
                else {
                    while (pos < len && buf[pos] != 0.toByte())
                        pos += (buf[pos].toInt() and 0xFF) + 1
                    if (pos < len) pos++
                }
                if (pos + 10 > len) return@repeat
                val type  = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos+1].toInt() and 0xFF)
                pos += 8 // type(2) + class(2) + ttl(4)
                val rdlen = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos+1].toInt() and 0xFF)
                pos += 2
                if (type == 1 && rdlen == 4 && pos + 4 <= len)
                    results.add(InetAddress.getByAddress(buf.copyOfRange(pos, pos + 4)))
                pos += rdlen
            }
        } catch (_: Exception) {}
        return results
    }

    companion object { private const val TAG = "MitmProxy" }
}
