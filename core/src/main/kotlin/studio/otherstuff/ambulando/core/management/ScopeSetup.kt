package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.string

/** The product scope template (bundled `scope-product-template.json`) rendered for one scope title and working directory. */
class ScopeSetupTemplate(val description: String, val channels: List<Channel>) {
    class Channel(val name: String, val description: String, val prompt: String)

    companion object {
        /** Blank has no channels. Product substitutes `$title` and `$workingdir` exactly as the iPhone app does. */
        fun load(json: String?, product: Boolean, title: String, directory: String): ScopeSetupTemplate {
            if (title.isBlank()) throw ManagementError.Invalid("Scope name is required.")
            if (!product) return ScopeSetupTemplate("", emptyList())
            if (directory.isBlank()) throw ManagementError.Invalid("The product template requires a working directory.")
            val root = runCatching { TowerJson.parseToJsonElement(json ?: "") as JsonObject }.getOrNull()
                ?: throw ManagementError.Unexpected("The product template is unavailable in this build.")
            fun render(text: String) = text.replace("\$title", title).replace("\$workingdir", directory)
            return ScopeSetupTemplate(
                render(root.obj("scope")?.string("description") ?: ""),
                (root.objects("channels") ?: emptyList()).map { Channel(it.string("name") ?: "", render(it.string("description") ?: ""), render(it.string("basePrompt") ?: "")) },
            )
        }
    }
}

/**
 * Scope setup is deliberately staged: creating the scope and each template channel are separate confirmed writes. The scope id is
 * saved as soon as Tower returns it and Tower's own channel list decides what is still missing, so an interrupted setup resumes
 * without a duplicate. A scope create that may have reached Tower is never repeated blindly ("attempted"): the person checks
 * Tower's current scopes and adopts the scope by name. The draft lives in the workspace's own partition per connection and workspace.
 */
class ScopeSetup(
    private val manager: ScopeChannelManager,
    private val store: LocalStore,
    private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,
    /** False once the workspace in front changed: no further step starts. */
    private val isCurrent: () -> Boolean = { true },
) {
    private val key = "admin.scope-setup.v1:${connection.id}:${workspace.id}"

    suspend fun load(): MutableMap<String, String> =
        store.reader.state(key)?.let { WorkspaceAdministrator.decodeStrings(it)?.toMutableMap() } ?: mutableMapOf()

    suspend fun persist(draft: Map<String, String>) = store.writer.setState(key, WorkspaceAdministrator.encodeStrings(draft))

    private fun step() { if (!isCurrent()) throw AdministrationSuperseded() }

    /** Creates the scope (once) and then each missing template channel. [draft] is updated and saved after every step. */
    suspend fun execute(draft: MutableMap<String, String>, templateJson: String?) {
        val template = ScopeSetupTemplate.load(templateJson, draft["template"] == "product", draft["name"] ?: "", draft["directory"] ?: "")
        persist(draft)
        step()
        if (draft["scope"].isNullOrEmpty()) {
            if (!ManagementPermissions.canCreateScope(manager.membership(connection, workspace))) {
                throw ManagementError.Forbidden(ManagementOperation.CREATE_SCOPE.permissionSentence)
            }
            if (draft["attempted"] == "true") {
                throw ManagementError.Conflict("A previous scope create may have reached Tower. Check Tower for this scope, or refresh Scopes and inspect its result, before starting another setup. Your entries are kept.")
            }
            step()
            draft["attempted"] = "true"; persist(draft)
            val created = manager.createScope(connection, workspace, draft["name"] ?: "", draft["description"]?.ifEmpty { null } ?: template.description.ifEmpty { null })
            draft["scope"] = created.id; persist(draft)
        }
        val scope = draft["scope"] ?: return
        step()
        val existing = manager.setupChannelNames(connection, workspace, scope)
        for (channel in template.channels) {
            if (channel.name.lowercase() in existing) continue
            step()
            manager.createChannel(connection, workspace, scope, channel.name, channel.description, channel.prompt)
            draft["lastChannel"] = channel.name; persist(draft)
        }
    }

    /** Looks for the scope an interrupted create may have made. None clears the attempt so the person may try again explicitly. */
    suspend fun recover(draft: MutableMap<String, String>): Pair<String, String>? {
        step()
        val found = manager.scopeSetupCandidate(connection, workspace, draft["name"] ?: "")
        if (found == null) { draft.remove("attempted"); persist(draft) }
        return found?.let { it.id to it.name }
    }

    /** Adopts a scope Tower already lists; setup then adds only the missing template channels and keeps existing settings. */
    suspend fun adopt(draft: MutableMap<String, String>, scopeId: String) { draft["scope"] = scopeId; persist(draft) }
}
