package studio.otherstuff.ambulando.app.background

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.otherstuff.ambulando.app.AppEnvironment
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Presigned object transfers survive UI teardown and process death without a signing credential. */
class WorkTransferBackend(private val context: Context, private val directory: File) : TransferBackend {
    companion object { internal val listeners = ConcurrentHashMap<String, (TransferCompletion) -> Unit>() }
    override fun setCompletionHandler(handler: (TransferCompletion) -> Unit) {
        listeners[directory.absolutePath] = handler
        directory.listFiles { file -> file.name.endsWith(".completed") }?.forEach { file ->
            runCatching { decode(file, directory) }.onSuccess { handler(it); file.delete() }
        }
    }
    override fun startUpload(request: HttpRequest, file: File, key: String) = schedule(request, key, file.name)
    override fun startDownload(request: HttpRequest, key: String) = schedule(request, key, null)
    private fun schedule(request: HttpRequest, key: String, upload: String?) {
        TransferPolicy.validate(request.url, null)
        require(request.headers.keys.all { it.equals("Content-Type", true) })
        require(upload == null || File(directory, upload).canonicalFile.parentFile == directory.canonicalFile)
        directory.mkdirs()
        val name = BackgroundTransfers.safeName(key)
        val value = JSONObject().put("key", key).put("url", request.url).put("method", request.method)
            .put("type", request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value).put("upload", upload)
        AtomicFiles.write(File(directory, "$name.request"), value.toString().toByteArray())
        val work = OneTimeWorkRequestBuilder<ObjectTransferWorker>().setInputData(workDataOf("name" to name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("ambulando.transfers").build()
        WorkManager.getInstance(context).enqueueUniqueWork("ambulando.transfer:$name", ExistingWorkPolicy.KEEP, work).result.get()
    }
    override suspend fun activeKeys(): Set<String> = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        directory.listFiles { file -> file.name.endsWith(".request") }?.mapNotNull { file ->
            val name = file.name.removeSuffix(".request")
            val infos = manager.getWorkInfosForUniqueWork("ambulando.transfer:$name").get()
            if (infos.any { !it.state.isFinished }) runCatching { JSONObject(file.readText()).getString("key") }.getOrNull() else null
        }?.toSet() ?: emptySet()
    }
}

private fun decode(file: File, directory: File): TransferCompletion {
    val o = JSONObject(file.readText())
    return TransferCompletion(o.getString("key"), o.optInt("status").takeIf { it > 0 }, o.optString("reason").takeIf { it.isNotBlank() && it != "null" },
        o.optString("file").takeIf { it.isNotBlank() && it != "null" }?.let { File(directory, it) }, o.optString("sha256").takeIf { it.isNotBlank() && it != "null" })
}

class ObjectTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString("name")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: return@withContext Result.failure()
        val directory = File(AppEnvironment.Production(applicationContext).baseDir, "Transfers")
        val requestFile = File(directory, "$name.request")
        if (!requestFile.isFile) return@withContext Result.failure()
        val o = JSONObject(requestFile.readText())
        val url = o.getString("url")
        TransferPolicy.validate(url, null)
        val method = o.getString("method")
        val key = o.getString("key")
        val type = o.optString("type").takeIf { it != "null" && it.isNotBlank() }
        val request = TransferPolicy.unsignedRequest(url, method, type)
        val backend = OkHttpTransferBackend(directory)
        val result = CompletableDeferred<TransferCompletion>()
        backend.setCompletionHandler { result.complete(it) }
        if (method == "PUT") {
            val upload = File(directory, o.getString("upload"))
            require(upload.canonicalFile.parentFile == directory.canonicalFile)
            backend.startUpload(request, upload, key)
        } else if (method == "GET") backend.startDownload(request, key) else return@withContext Result.failure()
        val completed = result.await()
        val value = JSONObject().put("key", key).put("status", completed.status).put("reason", completed.reason).put("file", completed.file?.name).put("sha256", completed.sha256)
        val completionFile = File(directory, "$name.completed")
        AtomicFiles.write(completionFile, value.toString().toByteArray())
        requestFile.delete()
        WorkTransferBackend.listeners[directory.absolutePath]?.let { handler -> handler(completed); completionFile.delete() }
        Result.success()
    }
}
