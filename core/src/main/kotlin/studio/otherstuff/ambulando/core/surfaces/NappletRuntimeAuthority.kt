package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.io.File
import java.net.URI

/** Authenticated server authority is separate from untrusted cartridge declarations. */
data class NappletRuntimeInstallation(val raw: JsonObject) {
    val id get()=NappletRuntimeProtocol.text(raw,"napplet_id")
    val publisher get()=NappletRuntimeProtocol.text(raw,"publisher_npub")
    val manifest=NappletRuntimeProtocol.manifest(raw.getValue("manifest").jsonObject)
    val bundle get()=raw.getValue("bundle").jsonObject
    val revision get()=NappletRuntimeProtocol.integer(raw,"revision")
    val removed get()=raw["removed"]==JsonPrimitive(true)
    fun validate(workspace: String,app: String): NappletRuntimeInstallation {
        require(raw["workspace_id"]==JsonPrimitive(workspace) && raw["app_npub"]==JsonPrimitive(app))
        require(NappletRuntimeProtocol.name.matches(id) && NostrKeys.isValidNpub(publisher) && NostrKeys.isValidNpub(NappletRuntimeProtocol.text(raw,"owner_npub")))
        NappletRuntimeProtocol.manifest(manifest); require(manifest["napplet_id"]==JsonPrimitive(id)); revision
        require(raw["removed"]==JsonPrimitive(true) || raw["removed"]==JsonPrimitive(false))
        GenericNapplet.exact(bundle,"file_id","file_version_id","sha256_hex")
        require(GenericNapplet.uuid.matches(NappletRuntimeProtocol.text(bundle,"file_id")) && GenericNapplet.uuid.matches(NappletRuntimeProtocol.text(bundle,"file_version_id")))
        require(NappletRuntimeProtocol.text(bundle,"sha256_hex").matches(Regex("[a-f0-9]{64}")))
        val audience=raw.getValue("audience_npubs").jsonArray
        require(audience.size<=200 && audience.distinct().size==audience.size)
        audience.forEach { require(it.jsonPrimitive.isString && NostrKeys.isValidNpub(it.jsonPrimitive.content)) }
        return this
    }
    fun requireAudience(viewer: String) {
        require(!removed && (raw["owner_npub"]==JsonPrimitive(viewer) || publisher==viewer || JsonPrimitive(viewer) in raw.getValue("audience_npubs").jsonArray))
    }
    fun verify(bytes: ByteArray): String {
        require(!removed && bytes.size in 1..2097152 && Sha256Hex.of(bytes)==NappletRuntimeProtocol.text(bundle,"sha256_hex"))
        return GenericJson.text(bytes).also { require(NappletRuntimeProtocol.parse(it)==manifest) }
    }
}

/** Identity-bound remembered access; changing code bytes alone never causes re-consent. */
class NappletRuntimeApprovals(private val directory: File,private val context: JsonObject) {
    init {
        listOf("baseUrl","tower","service","workspaceId","appNpub","viewer").forEach { require(NappletRuntimeProtocol.text(context,it).isNotEmpty()) }
        val u=URI(NappletRuntimeProtocol.text(context,"baseUrl")); require(u.scheme in setOf("http","https","fips") && u.rawUserInfo==null)
    }
    private fun file(i: NappletRuntimeInstallation): File {
        val identity=buildJsonObject { put("context",context); put("napplet_id",i.id) }
        return File(directory,Sha256Hex.of(GenericJson.canonical(identity).toByteArray())+".json")
    }
    @Synchronized fun approved(i: NappletRuntimeInstallation): Boolean = runCatching {
        val saved=GenericJson.decode(file(i).readBytes()); GenericNapplet.exact(saved,"context","publisher","manifest")
        NappletRuntimeProtocol.manifest(saved.getValue("manifest").jsonObject)
        saved["context"]==context && saved["publisher"]==JsonPrimitive(i.publisher) && NappletRuntimeProtocol.permits(saved.getValue("manifest").jsonObject,i.manifest)
    }.getOrDefault(false)
    @Synchronized fun remember(i: NappletRuntimeInstallation): Boolean = runCatching {
        require(NappletRuntimeProtocol.support(i.manifest)==null)
        check(directory.isDirectory || directory.mkdirs())
        val f=file(i); val tmp=File.createTempFile("approval-",".tmp",directory)
        try {
            tmp.outputStream().use { stream -> stream.write(buildJsonObject { put("context",context); put("publisher",i.publisher); put("manifest",i.manifest) }.toString().toByteArray()); stream.fd.sync() }
            check(tmp.renameTo(f))
        } finally { tmp.delete() }
        check(approved(i))
    }.isSuccess
    @Synchronized fun forget(i: NappletRuntimeInstallation): Boolean = runCatching { val f=file(i); check(!f.exists() || f.delete()); check(!f.exists()) }.isSuccess
}

/** Exact authenticated authority binding; missing service identity is a compatibility failure, never a fallback. */
object NappletRuntimeContext {
    fun create(connection: studio.otherstuff.ambulando.core.store.ConnectionRecord,workspace: studio.otherstuff.ambulando.core.transport.WorkspaceRecord): JsonObject {
        val identity=workspace.raw.getValue("identity").jsonObject
        val tower=requireNotNull(connection.towerNpub)
        val service=NappletRuntimeProtocol.text(identity,"workspace_service_npub")
        require(NostrKeys.isValidNpub(tower) && NostrKeys.isValidNpub(service) && NostrKeys.isValidNpub(connection.signerNpub))
        require(identity["tower_service_npub"]==JsonPrimitive(tower) && identity["workspace_id"]==JsonPrimitive(workspace.id) && identity["app_npub"]==JsonPrimitive(workspace.appNpub))
        return buildJsonObject {put("baseUrl",connection.origin);put("tower",tower);put("service",service);put("workspaceId",workspace.id);put("appNpub",workspace.appNpub);put("viewer",connection.signerNpub)}
    }
}
