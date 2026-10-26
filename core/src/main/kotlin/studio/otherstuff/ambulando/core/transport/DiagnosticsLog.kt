package studio.otherstuff.ambulando.core.transport

import java.net.URI
import java.time.Instant
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Strict structural templates: dynamic segments never become route words, even when they spell a known word. */
object DiagnosticsRedactor {
    private val collections = setOf("scopes", "channels", "messages", "threads", "tasks", "docs", "files", "file-folders", "members", "resource-view-states", "reactions", "events", "canvases", "context-components", "workspace-agents", "agent-activities")
    private val templates: List<List<String>> = buildList {
        val base = "/api/v4/flightdeck-pg/workspaces"
        add(base); add("$base/:id")
        for (collection in collections + setOf("scope-order", "personal-wapps", "personal-pins", "sync", "autopilot-agents")) {
            add("$base/:id/$collection")
            add("$base/:id/$collection/:id")
        }
        for (resource in setOf("tasks", "docs", "threads", "messages", "files", "canvases")) {
            for (action in setOf("comments", "body", "content", "snapshot", "state", "priority", "complete", "edit-leases")) {
                add("$base/:id/$resource/:id/$action")
            }
        }
        for (collection in setOf("tasks", "docs", "files", "file-folders", "threads", "messages", "agent-activities")) add("$base/:id/channels/:id/$collection")
        add("$base/:id/scopes/:id/channels")
        add("$base/:id/resource-view-states/mark-viewed")
        add("$base/:id/events/stream")
        add("/api/v4/storage/:id/content")
        add("/.well-known/nstack.json")
    }.map { it.split('/').filter(String::isNotEmpty) }
    private val queries = setOf("channel_id", "cursor", "family", "history_limit", "include_archived", "limit", "row_version", "target_id", "target_type", "thread_id", "scope_id")
    fun endpoint(method: String, url: String): String {
        val verb = method.uppercase().takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") } ?: "OTHER"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "$verb unknown"
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val template = templates.firstOrNull { pattern ->
            pattern.size == parts.size && pattern.zip(parts).all { (expected, actual) -> expected == ":id" || expected == actual }
        }
        val path = template?.joinToString("/", prefix = "/") ?: "/unknown"
        val names = uri.rawQuery?.split('&')?.take(30)?.map { it.substringBefore('=').takeIf { n -> n in queries } ?: "?" }?.sorted().orEmpty()
        // Host is deliberately omitted: exports need no deployment or user identifiers.
        return "$verb $path" + if (names.isEmpty()) "" else "?" + names.joinToString(",")
    }
    internal fun validEndpoint(value: String): Boolean {
        val verb = value.substringBefore(' ')
        val route = value.substringAfter(' ', "")
        val path = route.substringBefore('?')
        val query = if ('?' in route) route.substringAfter('?').split(',') else emptyList()
        return value.length < 1024 && verb in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OTHER") &&
            (path == "/unknown" || templates.any { it.joinToString("/", prefix = "/") == path }) &&
            query.size <= 30 && query.all { it == "?" || it in queries }
    }
    fun correlation(response: HttpResponse): String? {
        val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        for (name in listOf("x-request-id", "x-correlation-id")) response.header(name)?.let { if (uuid.matches(it)) return "$name=${it.lowercase()}" }
        return response.header("cf-ray")?.takeIf { Regex("[0-9a-f]{16}-[A-Z]{3}").matches(it) }?.let { "cf-ray=$it" }
    }
}

/** Bounded app-private ring, persisted after failures and at most once per five seconds for successful traffic. */
class DiagnosticsLog(private val capacity: Int = 300, private val file: File? = null) {
    @Serializable private data class Entry(
        val at: String, val endpoint: String? = null, val duration: Long? = null, val status: Int? = null,
        val error: String? = null, val correlation: String? = null, val retry: String? = null,
    ) {
        fun safe(): Boolean = runCatching { Instant.parse(at) }.isSuccess &&
            (endpoint == null || DiagnosticsRedactor.validEndpoint(endpoint)) &&
            (duration == null || duration in 0..86_400_000) && (status == null || status in 100..599) &&
            (error == null || error in setOf("cancelled", "tls", "timeout", "dns", "connection", "error") || Regex("http-[1-5][0-9]{2}").matches(error)) &&
            (retry == null || retry in setOf("retry", "gave-up", "recovered")) &&
            (correlation == null || correlation == DiagnosticsRedactor.correlation(HttpResponse(200, byteArrayOf(), mapOf(correlation.substringBefore('=') to correlation.substringAfter('=')))))
        fun line(): String = listOfNotNull(at, endpoint, duration?.let { "${it}ms" }, status?.let { "status=$it" }, error?.let { "error=$it" }, correlation, retry).joinToString(" | ")
    }
    private val limit = capacity.coerceIn(1, 300)
    private val entries = ArrayDeque<Entry>()
    private var lastWrite = 0L
    init {
        runCatching {
            if (file != null && file.isFile && file.length() <= 512_000) {
                Json.decodeFromString<List<Entry>>(file.readText()).filter { it.safe() }.takeLast(limit).forEach(entries::addLast)
            }
        }
    }
    @Synchronized private fun append(entry: Entry, urgent: Boolean) {
        if (!entry.safe()) return
        entries.addLast(entry)
        while (entries.size > limit) entries.removeFirst()
        if (urgent || System.nanoTime() - lastWrite > 5_000_000_000) persist()
    }
    private fun persist() {
        lastWrite = System.nanoTime()
        val destination = file ?: return
        // Diagnostics must never cause a request to fail (full disk/unavailable private storage).
        runCatching {
            destination.parentFile?.mkdirs()
            val staged = File(destination.parentFile, destination.name + ".tmp")
            staged.writeText(Json.encodeToString(entries.toList()))
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    @Synchronized fun clear() {
        entries.clear()
        runCatching { file?.delete(); file?.let { File(it.parentFile, it.name + ".tmp").delete() } }
        persist()
    }
    fun retry(outcome: String) { append(Entry(Instant.now().toString(), retry = outcome), true) }
    fun request(request: HttpRequest, duration: Long, response: HttpResponse?, error: Throwable?) {
        append(Entry(Instant.now().toString(), DiagnosticsRedactor.endpoint(request.method, request.url), duration.coerceIn(0, 86_400_000),
            response?.status, error?.let(ConnectionFailure::code), response?.let(DiagnosticsRedactor::correlation)), error != null || response?.status !in 200..299)
    }
    @Synchronized fun export(app: String, build: Int, androidApi: Int): String =
        "Ambulando diagnostics\nApp: $app (build $build); Android API $androidApi\nExported: ${Instant.now()} UTC\nNewest $limit entries; retained privately until replaced or cleared. No hosts, keys, tokens, bodies or user/record identifiers.\n\n" + entries.joinToString("\n") { it.line() } + "\n"
}
