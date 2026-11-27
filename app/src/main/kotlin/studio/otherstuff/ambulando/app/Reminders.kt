package studio.otherstuff.ambulando.app

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

internal fun AppModel.newReminderSaver(store: LocalStore) = ReminderSaver(object : ReminderJournal {
    override suspend fun read(key: String): JsonObject? = store.reader.state(key)?.let { Json.parseToJsonElement(it).jsonObject }
    override suspend fun write(key: String, body: JsonObject) { store.writer.setState(key, body.toString()) }
}, reminderMutex)

/** Capture and recheck the exact signed-in boundary around every suspension; never substitute the bot/owner. */
internal suspend fun AppModel.personalReminderBoard(entry: WorkspaceEntry): ChannelRecord {
    check(!BuildConfig.GRANDPARENT_CHAT)
    fun current() { check(activeEntry === entry && entry.enabled && !entry.workspace.accessRemoved && identityState is IdentityState.Unlocked && identityState?.npub == entry.connection.signerNpub) { "Unlock the original account and workspace to open Reminders." } }
    current()
    val api = towerAPI(entry.connection)
    // /me binds the real human, rather than a workspace owner or cached assignment.
    val me = Json.parseToJsonElement(api.client.request(path = TowerAPI.base(entry.workspace.id) + "/me", appNpub = entry.workspace.appNpub).toString(Charsets.UTF_8)).jsonObject
    current()
    Reminders.requireHuman(me, entry.connection.signerNpub, entry.workspace.id)
    val board = api.reminderBoard(entry.workspace.id, entry.workspace.appNpub, entry.connection.signerNpub)
    current()
    return board
}

internal suspend fun AppModel.saveChatReminder(entry: WorkspaceEntry, source: MessageRecord, threadId: String?): TaskRecord {
    check(source.workspaceId == entry.workspace.id) { "The source belongs to another workspace." }
    val board = personalReminderBoard(entry)
    val c = entry.connection; val w = entry.workspace; val store = requireNotNull(entry.runtime).store
    val api = towerAPI(c)
    fun current() { check(activeEntry === entry && identityState is IdentityState.Unlocked && identityState?.npub == c.signerNpub && entry.enabled && !entry.workspace.accessRemoved) { "The account or workspace changed. Return to the original workspace to retry." } }
    current()
    val fresh = api.message(w.id, w.appNpub, source.id) ?: error("The source message is no longer available to you.")
    check(fresh.id == source.id && fresh.channelId == source.channelId && !fresh.deleted && !fresh.accessRemoved)
    current()
    val title = threadId?.let {
        val response = Json.parseToJsonElement(api.client.request(path = TowerAPI.base(w.id) + "/threads/${TowerAPI.uuid(it)}", appNpub = w.appNpub).toString(Charsets.UTF_8)).jsonObject
        val thread = ThreadRecord.parse(response.obj("thread") ?: error("The conversation title could not be verified."))
        check(thread.workspaceId == w.id && thread.id == it && thread.channelId == fresh.channelId && !thread.accessRemoved)
        current()
        thread.title
    }
    val task = newReminderSaver(store).save(entry.boundary.id, w.id, c.signerNpub, source.id,
        prepare = { Reminders.body(board.id, c.signerNpub, title, fresh) },
        post = { request ->
            current()
            check(request.string("channel_id") == board.id) { "Your personal board changed. The original reminder request was preserved." }
            api.saveReminder(w.id, w.appNpub, request)
        })
    current()
    store.writer.upsertTasks(w.id, listOf(task))
    return task
}
