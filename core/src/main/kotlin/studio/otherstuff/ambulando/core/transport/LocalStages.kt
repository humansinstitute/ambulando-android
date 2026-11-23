package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Process-local categorical observations only. No data-bearing strings, files, telemetry or retained identities. */
object LocalStages {
    enum class Stage { SCHEDULER_QUEUE, ACTIVE_FAILED_RESUME_ACCESS, ACTIVE_FAILED_SCOPES, ACTIVE_FAILED_PROFILES, ACTIVE_FAILED_ATTENTION, ACTIVE_FAILED_NOTIFICATIONS, ACTIVE_FAILED_PINS, ACTIVE_FAILED_DISCOVER, ACTIVE_FAILED_MESSAGES, ACTIVE_FAILED_REACTIONS, ACTIVE_FAILED_THREAD, ACTIVE_FAILED_DIRECTORY, ACTIVE_FAILED_CHANNELS, ACTIVE_FAILED_TASKS, ACTIVE_FAILED_TASK, ACTIVE_FAILED_TASK_RELATIONSHIPS, ACTIVE_FAILED_DOCS, ACTIVE_FAILED_DOC, ACTIVE_FAILED_FILES, ACTIVE_FAILED_INBOX, ACTIVE_FAILED_EVENTS, ACTIVE_FAILED_SCOPE_ORDER, ACTIVE_FAILED_COLLAPSED, ACTIVE_FAILED_NOTIFICATIONS_ARCHIVE, ACTIVE_FAILED_NOTIFICATION_ITEM, ACTIVE_FAILED_NOTIFICATION_PREFS, ACTIVE_FAILED_CONTEXT, ACTIVE_FAILED_CONTEXT_REFS, ACTIVE_FAILED_CANVASES, ACTIVE_FAILED_CANVAS, ACTIVE_FAILED_BOARD, ACTIVE_FAILED_AGENTS, ACTIVE_FAILED_AGENT_ACTIVITY, ACTIVE_FAILED_TOOLBOX, ACTIVE_FAILED_WORKSPACE_REPORT, ACTIVE_FAILED_THREAD_RECORD, ACTIVE_FAILED_DOWNLOAD, ACTIVE_FAILED_ATTACHMENT, ACTIVE_FAILED_VOICE, ACTIVE_FAILED_ADMIN, ACTIVE_FAILED_MANAGE, ACTIVE_FAILED_PERSONAL_AGENTS, ACTIVE_FAILED_WRITES, ACTIVE_FAILED_OUTBOX, ACTIVE_FAILED_EXPORT, ACTIVE_FAILED_DOC_MOVE, ACTIVE_FAILED_DOC_SAVE, ACTIVE_FAILED_DOC_LEASE, ACTIVE_FAILED_DOC_INSPECT, ACTIVE_FAILED_DOC_LATEST, ACTIVE_FAILED_DOC_VERSIONS, ACTIVE_FAILED_PASTE, ACTIVE_FAILED_BRANCH, ACTIVE_FAILED_RENAME_THREAD, ACTIVE_FAILED_NAPLET_HTML, ACTIVE_FAILED_PIN_ORDER, ACTIVE_FAILED_CANVAS_CREATE, ACTIVE_FAILED_BOARD_VERSIONS, ACTIVE_FAILED_BOARD_READ, ACTIVE_FAILED_BOARD_PUBLISH, ACTIVE_FAILED_BOARD_RENAME, ACTIVE_FAILED_TASK_TREE, ACTIVE_FAILED_TREE_CREATE, ACTIVE_FAILED_TREE_VIEW, ACTIVE_FAILED_CANVAS_EDIT, ACTIVE_FAILED_OTHER, WRITE_QUEUE, WRITE_GATE, WRITE_ATTEMPT, WRITE_BLOCKED, VALIDATION, MEMBERSHIP, CATCH_UP, LEASE_ADMISSION, LEASE_LOCK, LEASE_CLOSE, RENEW_ADMISSION, RENEW_LOCK, PROVIDER_REQUEST, PROVIDER_CHECK, PROVIDER_RELEASE, DNS, SOCKET, HTTP, HTTP_HEADERS, HTTP_BODY, UPLOAD_PREPARE, UPLOAD_BYTES, UPLOAD_COMPLETE, UPLOAD_VERIFY, UPLOAD_LINK }
    enum class Result { BEGIN, END, FAILED, CANCELLED, CLOSED, CONSENT, PENDING, GRANT_INVALID, PEER_REJECTED, PROVIDER_UNAVAILABLE }
    data class Event(val sequence: Long, val operation: Long, val parent: Long, val elapsedMillis: Long, val stage: Stage, val result: Result)
    private class Operation(val id: Long) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Operation> }
    private val active = ThreadLocal<Long?>()
    fun current(): Long = active.get() ?: 0
    private val ids = AtomicLong()
    private val events = ArrayDeque<Event>()
    private val epoch = System.nanoTime()
    /** Android installs a categorical logcat sink; tests need none. A sink failure cannot change request behavior. */
    @Volatile var observer: ((Event) -> Unit)? = null
    fun next(): Long = ids.incrementAndGet()
    @Synchronized fun emit(operation: Long, stage: Stage, result: Result, parent: Long = 0) {
        val event = Event(next(), operation, parent, (System.nanoTime() - epoch) / 1_000_000, stage, result)
        events.addLast(event)
        while (events.size > 256) events.removeFirst()
        runCatching { observer?.invoke(event) }
    }
    @Synchronized fun snapshot(): List<Event> = events.toList()
    private fun failure(error: Throwable): Result = when (error) {
        is CancellationException -> Result.CANCELLED
        is SafeConnectionIssue -> when (error.connectionIssue) {
            ConnectionIssue.CONSENT -> Result.CONSENT
            ConnectionIssue.PENDING -> Result.PENDING
            ConnectionIssue.PEER -> Result.PEER_REJECTED
            ConnectionIssue.PROVIDER, ConnectionIssue.AMBIGUITY -> Result.PROVIDER_UNAVAILABLE
            else -> Result.GRANT_INVALID
        }
        else -> Result.FAILED
    }
    suspend fun <T> trace(stage: Stage, block: suspend () -> T): T {
        val id = next()
        val parent = coroutineContext[Operation]?.id ?: 0
        emit(id, stage, Result.BEGIN, parent)
        return try {
            withContext(Operation(id) + active.asContextElement(id)) { block() }.also { emit(id, stage, Result.END, parent) }
        } catch (e: Throwable) {
            emit(id, stage, failure(e), parent)
            throw e
        }
    }
    fun <T> blocking(stage: Stage, parent: Long = current(), block: () -> T): T {
        val id = next(); emit(id, stage, Result.BEGIN, parent)
        val previous = active.get(); active.set(id)
        return try { block().also { emit(id, stage, Result.END, parent) } }
        catch (e: Throwable) { emit(id, stage, failure(e), parent); throw e }
        finally { active.set(previous) }
    }
}

/** Fixed public wording; never forwards provider replies or exception messages. */
enum class ConnectionIssue(val userMessage: String) {
    CONSENT("Approve this endpoint in Nostr Browser, then verify the connection again."),
    PENDING("Endpoint approval is pending. Return from Nostr Browser and verify the connection again."),
    PEER("Shared connection peer validation failed. Verify the connection before sending."),
    BINDING("Shared connection grant validation failed. Verify the connection before sending."),
    GRANT("The shared connection grant is unavailable. Verify the connection again."),
    AMBIGUITY("Both trusted Nostr Browser providers are installed. Keep only the intended stable or nightly provider installed, then restart Ambulando and verify the connection again."),
    PROVIDER("The trusted shared provider is unavailable or changed. Restart Ambulando and verify the connection again.")
}
interface SafeConnectionIssue { val connectionIssue: ConnectionIssue }
class SharedConnectionException(override val connectionIssue: ConnectionIssue, detail: String = connectionIssue.userMessage) : java.io.IOException(detail), SafeConnectionIssue
