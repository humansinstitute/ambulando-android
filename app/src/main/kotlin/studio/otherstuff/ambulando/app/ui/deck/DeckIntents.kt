package studio.otherstuff.ambulando.app.ui.deck

import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.transport.ViewStateRecord

/**
 * What Deck asks the app model to do. Reads stay in the store; every write and refresh goes through here so the
 * integrator implements it on the sync engine and durable write queue.
 */
interface DeckIntents : PinIntents {
    /**
     * Refreshes what the Inbox shows for `context` (iOS `openInbox` + `openTasks` / `openDocs` / `openFiles` for the
     * context's scope). Called when the card starts for a new context and after an unlock.
     */
    fun refreshInbox(context: WorkContext)

    /**
     * Marks records read (iOS `AppModel.markRead`): local viewed positions first, then `view.mark` writes of up to 500
     * resources each. Mark read on a row passes one record; "Mark all … as read" passes every unread one of that kind.
     */
    fun markRead(resources: List<ViewStateRecord>)

    /** Mark done on an unread review task (iOS Deck `markDone`): queues a `task.state` write with `done`. */
    fun markDone(taskId: String)

    /**
     * Opens a channel in Chat as the context, optionally at a conversation (iOS `openInChat`). Used by New thread (the
     * composer opens there), Recent channels and pinned channels.
     */
    fun openInChat(channelId: String, threadId: String? = null)

    /** Refreshes the canvas list (iOS `openCanvases`). */
    fun refreshCanvases(force: Boolean = false)

    /**
     * Creates a canvas visible to the workspace (iOS `CanvasEditor.createCanvas`, `POST /canvases`), a confirmed request.
     * Returns the new canvas id, or a failure whose message the card shows.
     */
    suspend fun createCanvas(title: String): Result<String>

    /** Refreshes the personal WApps and the My Agents launchers (iOS `openToolbox` + `refreshPersonalAgentLaunchers`). */
    fun refreshLaunchers(force: Boolean = false)

    /** Opens an HTTPS launcher (WApp or personal agent) as an in-app browser tab (`BrowserModel.launch`). */
    fun launchApp(url: String)

    /** Shows the Toolbox section (Manage apps / Choose apps). */
    fun showToolbox()
}
