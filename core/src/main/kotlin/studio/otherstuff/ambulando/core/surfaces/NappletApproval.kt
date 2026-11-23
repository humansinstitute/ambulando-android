package studio.otherstuff.ambulando.core.surfaces

import java.io.File
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*

/** A local consent fingerprint, never a credential or a substitute for current server authorization. */
@ConsistentCopyVisibility
data class NappletApproval private constructor(val slot: String, val fingerprint: String) {
    companion object {
        /** Separate receipt policy: only exact code and workspace-bound read capability, never counts/channel sets. */
        fun report(origin: String, connection: String, service: String?, namespace: String, workspace: String,
                   app: String, viewer: String, code: String, html: String, metadata: JsonObject, manifest: JsonObject): NappletApproval {
            require(WorkspaceReport.binding(metadata,manifest)==workspace && isUUID(code))
            val boundary=buildJsonObject {
                put("origin",TowerOrigin(origin).url); put("connection",connection); put("service",service)
                put("namespace",namespace); put("workspace",workspace); put("app",app); put("viewer",viewer); put("codeFile",code)
                put("capability","workspace-report-v1:read")
            }
            fun hash(v: JsonObject)=Sha256Hex.of(sortedJson(v).toString().toByteArray())
            return NappletApproval(hash(boundary),hash(buildJsonObject {
                put("boundary",boundary); put("codeSha256",Sha256Hex.of(html.toByteArray())); put("manifest",manifest)
                put("binding",workspace); put("policy",1)
            }))
        }

        /** Call only after validating code bytes and freshly authorizing these exact bindings. */
        fun verified(origin: String, connection: String, service: String?, namespace: String, workspace: String,
                     app: String, viewer: String, codeId: String, html: String, metadata: JsonObject,
                     manifest: JsonObject, dataId: String, canWrite: Boolean): NappletApproval {
            require(FileNapplet.binding(metadata, manifest) == dataId)
            require(isUUID(workspace) && isUUID(codeId))
            val boundary = buildJsonObject {
                put("origin", TowerOrigin(origin).url); put("connection", connection); put("service", service?.let(::JsonPrimitive) ?: JsonNull)
                put("namespace", namespace); put("workspace", workspace); put("app", app); put("viewer", viewer); put("codeFile", codeId)
            }
            val capability = buildJsonObject {
                put("schema", 1); put("policy", 1); put("boundary", boundary)
                put("codeSha256", Sha256Hex.of(html.toByteArray(Charsets.UTF_8)))
                put("manifestVersion", manifest.getValue("version")); put("appId", manifest.getValue("app_id"))
                put("bindings", buildJsonObject { put("tracker", buildJsonObject {
                    val declaration = manifest.getValue("bindings").jsonObject.getValue("tracker").jsonObject
                    put("fileId", dataId); put("format", declaration.getValue("format")); put("privacy", declaration.getValue("privacy"))
                    put("operations", JsonArray(declaration.getValue("operations").jsonArray.sortedBy { it.jsonPrimitive.content }))
                }) }); put("canWrite", canWrite)
            }
            fun hash(value: JsonObject) = Sha256Hex.of(sortedJson(value).toString().toByteArray(Charsets.UTF_8))
            return NappletApproval(hash(boundary), hash(capability))
        }
    }
}

/** Store beneath the app's no-backup directory. Only opaque fingerprints persist; no tracker values or keys. */
class NappletApprovalStore(private val directory: File) {
    private fun file(approval: NappletApproval) = File(directory, approval.slot + ".json")
    @Synchronized fun approved(approval: NappletApproval): Boolean = runCatching {
        val target = file(approval)
        require(target.length() in 1..256)
        val value = Json.parseToJsonElement(target.readText()).jsonObject
        value.keys == setOf("schema", "fingerprint") && value["schema"] == JsonPrimitive(1) &&
            value["fingerprint"] == JsonPrimitive(approval.fingerprint)
    }.getOrDefault(false)
    @Synchronized fun remember(approval: NappletApproval): Boolean = runCatching {
        check(directory.isDirectory || directory.mkdirs())
        val target = file(approval)
        val pending = File(directory, approval.slot + ".pending")
        try {
            pending.writeText(buildJsonObject { put("schema", 1); put("fingerprint", approval.fingerprint) }.toString())
            check(pending.renameTo(target))
            check(approved(approval))
        } finally { pending.delete() }
    }.isSuccess
    @Synchronized fun forget(approval: NappletApproval): Boolean = runCatching {
        val target = file(approval)
        check(!target.exists() || target.delete())
        check(!target.exists())
    }.isSuccess
}
