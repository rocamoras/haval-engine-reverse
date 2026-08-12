package br.com.redesurftank.havalenginereverse

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FirebaseLogUploader {

    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()

    fun upload(
        entries: List<EngineReverseStateHolder.EventEntry>,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("Autenticando...")
        ensureSignedIn(
            onReady = { doUpload(entries, onProgress, onSuccess, onError) },
            onError = { onError("Erro de autenticação: $it") }
        )
    }

    private fun ensureSignedIn(onReady: () -> Unit, onError: (String) -> Unit) {
        if (auth.currentUser != null) {
            onReady()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { onError(it.message ?: "falha") }
    }

    /**
     * Faz upload de bytes já lidos de um .pcap.
     * DEVE ser chamado na main thread (Firebase usa Looper internamente).
     */
    fun uploadPcapBytes(
        bytes: ByteArray,
        fileName: String,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("Autenticando...")
        ensureSignedIn(
            onReady = {
                val ref = storage.reference.child("logs/$fileName")
                onProgress("Enviando $fileName (${bytes.size / 1024} KB)...")
                ref.putBytes(bytes)
                    .addOnSuccessListener {
                        ref.downloadUrl
                            .addOnSuccessListener { uri -> onSuccess(uri.toString()) }
                            .addOnFailureListener { onSuccess(fileName) }
                    }
                    .addOnFailureListener { onError(it.message ?: "falha no upload") }
            },
            onError = { onError("Erro de autenticação: $it") }
        )
    }

    /**
     * Faz upload de um arquivo (ex.: APK) por streaming — evita carregar tudo em memória.
     * DEVE ser chamado na main thread (Firebase usa Looper internamente).
     */
    fun uploadFile(
        file: File,
        destName: String,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("Autenticando...")
        ensureSignedIn(
            onReady = {
                val ref = storage.reference.child("logs/$destName")
                onProgress("Enviando $destName (${file.length() / 1024} KB)...")
                val stream = try {
                    file.inputStream()
                } catch (e: Exception) {
                    onError("Erro ao abrir ${file.name}: ${e.message}"); return@ensureSignedIn
                }
                ref.putStream(stream)
                    .addOnProgressListener { snap ->
                        val pct = if (snap.totalByteCount > 0)
                            (100 * snap.bytesTransferred / snap.totalByteCount) else 0
                        onProgress("Enviando $destName… $pct%")
                    }
                    .addOnSuccessListener {
                        try { stream.close() } catch (_: Exception) {}
                        ref.downloadUrl
                            .addOnSuccessListener { uri -> onSuccess(uri.toString()) }
                            .addOnFailureListener { onSuccess(destName) }
                    }
                    .addOnFailureListener {
                        try { stream.close() } catch (_: Exception) {}
                        onError(it.message ?: "falha no upload")
                    }
            },
            onError = { onError("Erro de autenticação: $it") }
        )
    }

    fun uploadJson(
        json: String,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("Autenticando...")
        ensureSignedIn(
            onReady = {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val fileName  = "proxy_$timestamp.json"
                val ref       = storage.reference.child("logs/$fileName")
                val bytes     = json.toByteArray(Charsets.UTF_8)
                onProgress("Enviando $fileName (${bytes.size / 1024} KB)...")
                ref.putBytes(bytes)
                    .addOnSuccessListener {
                        ref.downloadUrl
                            .addOnSuccessListener { uri -> onSuccess(uri.toString()) }
                            .addOnFailureListener { onSuccess(fileName) }
                    }
                    .addOnFailureListener { onError(it.message ?: "falha no upload") }
            },
            onError = { onError("Erro de autenticação: $it") }
        )
    }

    private fun doUpload(
        entries: List<EngineReverseStateHolder.EventEntry>,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "log_$timestamp.txt"
        val ref = storage.reference.child("logs/$fileName")

        val content = buildString {
            appendLine("# Haval Engine Reverse — Log exportado em $timestamp")
            appendLine("# Total: ${entries.size} entradas")
            appendLine()
            entries.forEach { e ->
                appendLine("${e.time}  [${e.source.padEnd(12)}]  ${e.key}  =  ${e.value}")
            }
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        onProgress("Enviando $fileName...")

        ref.putBytes(bytes)
            .addOnSuccessListener {
                ref.downloadUrl
                    .addOnSuccessListener { uri -> onSuccess(uri.toString()) }
                    .addOnFailureListener { onSuccess(fileName) }
            }
            .addOnFailureListener { onError(it.message ?: "falha no upload") }
    }
}
