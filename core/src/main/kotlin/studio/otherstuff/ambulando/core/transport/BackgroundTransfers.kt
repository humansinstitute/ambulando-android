package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.nostr.Hex
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How a backend transfer ended: HTTP status, failure text, and for downloads the body file (inside the transfers directory). */
data class TransferCompletion(
    val key: String,
    val status: Int?,
    val reason: String?,
    val file: File? = null,
    /** sha256 of a downloaded body, computed while it streamed (null when the backend did not hash it). */
    val sha256: String? = null,
)

/**
 * Transfer executor seam. Production uses [OkHttpTransferBackend] (the app may host it in a scheduler that outlives a
 * screen, such as WorkManager); tests drive completions by hand.
 */
interface TransferBackend {
    /** Called by the backend when a transfer ends (also for transfers started before the coordinator was recreated). */
    fun setCompletionHandler(handler: (TransferCompletion) -> Unit)
    fun startUpload(request: HttpRequest, file: File, key: String)
    fun startDownload(request: HttpRequest, key: String)
    /** Keys of transfers the backend still runs. */
    suspend fun activeKeys(): Set<String>
}

/**
 * Durable coordinator for transfers to and from presigned object-store URLs (the Android counterpart of the iOS
 * background transfer coordinator).
 *
 * - Only **unsigned** requests to a host other than the Tower origin are accepted ([TransferPolicy]): the NIP-98
 *   identity, cookies and any Authorization header never reach an object store. Tower's own signed storage routes stay
 *   in-process.
 * - Each transfer has a stable key (the storage object id for uploads) persisted with its state, so a transfer that
 *   finished while nobody waited (screen gone, job cancelled, coordinator recreated) is picked up by the next attempt
 *   instead of being repeated. Prepare and complete stay with the caller ([TowerAPI.store]), which commits only after
 *   Tower verifies size and hash.
 * - Waiting is cancellable without cancelling the transfer itself (lock, job expiry).
 */
class BackgroundTransfers(val directory: File, private val backend: TransferBackend) : ObjectTransfers {
    @Serializable
    data class Record(
        val key: String,
        val kind: Kind,
        val url: String,
        val state: State,
        val status: Int? = null,
        val reason: String? = null,
        val resultFile: String? = null,
        val sha256: String? = null,
        val createdAt: String,
    ) {
        /** Stored names match the iOS index. */
        @Serializable enum class Kind { @SerialName("upload") UPLOAD, @SerialName("download") DOWNLOAD }
        @Serializable enum class State { @SerialName("running") RUNNING, @SerialName("succeeded") SUCCEEDED, @SerialName("failed") FAILED }
    }

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()
    private val waiters = HashMap<String, MutableList<CompletableDeferred<Record>>>()
    private val indexFile: File get() = File(directory, "transfers.json")

    init {
        directory.mkdirs()
        runCatching { json.decodeFromString(index, indexFile.readText()) }.getOrNull()?.let { records.putAll(it) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val index = MapSerializer(String.serializer(), Record.serializer())

        fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /** Connects backend callbacks. Call once when the coordinator is created, before transfers report. */
    fun activate() {
        backend.setCompletionHandler { finished(it) }
    }

    fun record(key: String): Record? = synchronized(lock) { records[key] }
    val all: List<Record> get() = synchronized(lock) { records.values.toList() }

    /** Uploads `bytes` with an unsigned PUT and returns when the object store accepted them. */
    override suspend fun upload(key: String, url: String, bytes: ByteArray, contentType: String, towerHost: String?) {
        TransferPolicy.validate(url, towerHost)
        if (settled(key) != null) return
        val active = backend.activeKeys()
        val start = synchronized(lock) {
            if (records[key]?.state != Record.State.RUNNING || key !in active) {
                records[key] = Record(key, Record.Kind.UPLOAD, url, Record.State.RUNNING, createdAt = Instant.now().toString())
                true
            } else false
        }
        if (start) {
            val file = File(directory, safeName(key) + ".upload")
            AtomicFiles.write(file, bytes)
            save()
            backend.startUpload(TransferPolicy.unsignedRequest(url, "PUT", contentType), file, key)
        }
        wait(key)
        settled(key)
    }

    /**
     * Downloads with an unsigned GET and returns the downloaded file (inside [directory]). Call [forget] once the caller
     * has verified and moved it.
     */
    suspend fun download(key: String, url: String, towerHost: String?): File {
        TransferPolicy.validate(url, towerHost)
        settled(key, keepResult = true)?.resultFile?.let { return File(directory, it) }
        val active = backend.activeKeys()
        val start = synchronized(lock) {
            if (records[key]?.state != Record.State.RUNNING || key !in active) {
                records[key] = Record(key, Record.Kind.DOWNLOAD, url, Record.State.RUNNING, createdAt = Instant.now().toString())
                true
            } else false
        }
        if (start) {
            save()
            backend.startDownload(TransferPolicy.unsignedRequest(url, "GET", null), key)
        }
        wait(key)
        val done = settled(key, keepResult = true)?.resultFile ?: throw TransferError.Lost
        return File(directory, done)
    }

    /**
     * Downloads into a content-addressed cache: `cache/<sha256>`. An existing verified copy is returned without a
     * transfer; a fresh body is accepted only if its sha256 matches [sha256] (hashed while streaming when the backend
     * can), otherwise it is discarded and [FileTransferError.Integrity] is thrown.
     */
    suspend fun downloadVerified(key: String, url: String, towerHost: String?, sha256: String, cache: File): File {
        val expected = sha256.lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(expected)) throw FileTransferError.Integrity
        val target = File(cache, expected)
        if (target.isFile && Sha256Hex.ofFile(target) == expected) {
            forget(key)
            return target
        }
        val file = download(key, url, towerHost)
        val actual = record(key)?.sha256 ?: Sha256Hex.ofFile(file)
        if (actual != expected) {
            forget(key)
            throw FileTransferError.Integrity
        }
        cache.mkdirs()
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        forget(key)
        return target
    }

    /** A finished record (removed unless `keepResult`); throws for a failed one; null while running or absent. */
    private fun settled(key: String, keepResult: Boolean = false): Record? {
        val record = synchronized(lock) { records[key] } ?: return null
        return when (record.state) {
            Record.State.RUNNING -> null
            Record.State.SUCCEEDED -> {
                if (!keepResult) forget(key)
                record
            }
            Record.State.FAILED -> {
                forget(key)
                throw TransferError.Failed(record.status, record.reason)
            }
        }
    }

    private suspend fun wait(key: String): Record {
        val waiter = CompletableDeferred<Record>()
        synchronized(lock) {
            val record = records[key]
            if (record != null && record.state != Record.State.RUNNING) return record
            waiters.getOrPut(key) { ArrayList() }.add(waiter)
        }
        try {
            return waiter.await()
        } finally {
            synchronized(lock) { waiters[key]?.remove(waiter) }
        }
    }

    /** Backend completion (also for transfers that outlived the caller that started them). */
    fun finished(completion: TransferCompletion) {
        val key = completion.key
        val resume: List<CompletableDeferred<Record>>
        val record: Record
        synchronized(lock) {
            val current = records[key]
            if (current == null) {
                completion.file?.delete()
                return
            }
            val ok = completion.reason == null && completion.status?.let { it in 200..299 } == true &&
                (current.kind == Record.Kind.UPLOAD || completion.file != null)
            record = current.copy(
                state = if (ok) Record.State.SUCCEEDED else Record.State.FAILED, status = completion.status,
                reason = if (ok) null else (completion.reason ?: "status ${completion.status?.toString() ?: "none"}"),
                resultFile = if (ok) completion.file?.name else null, sha256 = if (ok) completion.sha256 else null,
            )
            if (!ok) completion.file?.delete()
            if (current.kind == Record.Kind.UPLOAD) File(directory, safeName(key) + ".upload").delete()
            records[key] = record
            resume = waiters.remove(key)?.toList() ?: emptyList()
        }
        save()
        resume.forEach { it.complete(record) }
    }

    /**
     * After the process was recreated: a running record whose transfer the backend no longer has (and that never
     * reported) is marked lost, so the next attempt starts it again.
     */
    suspend fun recover() {
        val active = backend.activeKeys()
        val resumed = ArrayList<Pair<CompletableDeferred<Record>, Record>>()
        synchronized(lock) {
            for ((key, record) in records.entries.toList()) {
                if (record.state != Record.State.RUNNING || key in active) continue
                val lost = record.copy(state = Record.State.FAILED, reason = "lost")
                records[key] = lost
                waiters.remove(key)?.forEach { resumed.add(it to lost) }
            }
        }
        save()
        resumed.forEach { (w, r) -> w.complete(r) }
    }

    fun forget(key: String) {
        val record = synchronized(lock) { records.remove(key) } ?: return
        record.resultFile?.let { File(directory, it).delete() }
        save()
    }

    private fun save() {
        val text = synchronized(lock) { json.encodeToString(index, LinkedHashMap(records)) }
        runCatching { AtomicFiles.write(indexFile, text.toByteArray(Charsets.UTF_8)) }
    }
}

/**
 * OkHttp backend for [BackgroundTransfers]: unsigned PUT/GET to presigned object-store URLs with redirects refused
 * (a 3xx is reported as a failed status), no cookie jar and no cache. Downloads stream into the transfers directory
 * while being hashed and are bounded by [maxDownloadBytes]. Platform-free; the app may run the coordinator inside a
 * longer-lived scheduler.
 */
class OkHttpTransferBackend(
    private val directory: File,
    base: OkHttpClient = OkHttpClient(),
    timeoutSeconds: Long = 120,
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES.toLong(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : TransferBackend {
    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private val running = HashMap<String, Job>()
    @Volatile private var handler: ((TransferCompletion) -> Unit)? = null

    override fun setCompletionHandler(handler: (TransferCompletion) -> Unit) { this.handler = handler }

    override suspend fun activeKeys(): Set<String> = synchronized(lock) { running.keys.toSet() }

    override fun startUpload(request: HttpRequest, file: File, key: String) {
        require(request.header("Authorization") == null) { "object-store transfers are never signed" }
        launch(key) {
            val contentType = request.header("Content-Type")?.toMediaTypeOrNull()
            val call = Request.Builder().url(request.url).put(file.asRequestBody(contentType)).build()
            execute(call).use { response -> TransferCompletion(key, response.code, null) }
        }
    }

    override fun startDownload(request: HttpRequest, key: String) {
        require(request.header("Authorization") == null) { "object-store transfers are never signed" }
        launch(key) {
            execute(Request.Builder().url(request.url).get().build()).use { response ->
                if (response.code !in 200..299) return@use TransferCompletion(key, response.code, null)
                val target = File(directory, BackgroundTransfers.safeName(key) + ".download")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    response.body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > maxDownloadBytes) throw IOException("The file is larger than this app downloads.")
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                } catch (e: IOException) {
                    target.delete()
                    throw e
                }
                TransferCompletion(key, response.code, null, target, Hex.encode(digest.digest()))
            }
        }
    }

    private fun launch(key: String, block: suspend () -> TransferCompletion) {
        synchronized(lock) {
            if (running.containsKey(key)) return
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val completion = try {
                    block()
                } catch (e: IOException) {
                    TransferCompletion(key, null, e.message ?: e.javaClass.simpleName)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    TransferCompletion(key, null, e.message ?: e.javaClass.simpleName)
                }
                synchronized(lock) { running.remove(key) }
                handler?.invoke(completion)
            }
            running[key] = job
            job.start()
        }
    }

    private suspend fun execute(request: Request): Response {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) }
            })
        }
    }
}
