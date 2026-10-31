package studio.otherstuff.ambulando.core.docs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.DocumentEditInspection
import studio.otherstuff.ambulando.core.transport.EditLease
import studio.otherstuff.ambulando.core.transport.CONCURRENT_DOCUMENT_EDIT_PROTOCOL
import studio.otherstuff.ambulando.core.transport.DOCUMENT_EDIT_PROTOCOL
import studio.otherstuff.ambulando.core.transport.TowerError
import java.net.UnknownHostException

internal object Docs {
    const val OBJECT = "00000000-0000-4000-8000-0000000000d1"

    /** A rich envelope with one paragraph per text, an unknown envelope field and an unknown model field. */
    fun rich(vararg paragraphs: String, marks: Map<Int, String> = emptyMap(), extra: String = """"future_field":{"keep":true}"""): ByteArray {
        val nodes = paragraphs.mapIndexed { i, text ->
            val mark = marks[i]?.let { ""","marks":[{"type":"$it"}]""" } ?: ""
            """{"type":"paragraph","attrs":{"fdBlockId":"b$i"},"content":[{"type":"text","text":${quote(text)}$mark}]}"""
        }
        val state = JsonValue.parse("""{"type":"doc","content":[${nodes.joinToString(",")}]}""")
        val model = JsonValue.Obj(ProseMirrorMarkdown.contentModelFields(state)).serializedString().dropLast(1) + ""","references":["r1"]}"""
        return """{"format":"document_content_v1","content_model":$model,$extra}""".toByteArray()
    }

    private fun quote(text: String) = studio.otherstuff.ambulando.core.nostr.NostrSerialization.quote(text)

    fun snapshot(bytes: ByteArray, title: String = "Plan", version: Int = 1, type: String = DocumentBody.ENVELOPE_CONTENT_TYPE, obj: String = OBJECT) =
        PreservedDocumentSnapshot(title, bytes, type, version, Sha256Hex.of(bytes), obj)

    fun markdown(text: String, title: String = "Plan", version: Int = 1) = snapshot(text.toByteArray(), title, version, "text/markdown")

    fun paragraphs(bytes: ByteArray): List<String> =
        DocumentBody(bytes, DocumentBody.ENVELOPE_CONTENT_TYPE).editorState!!["content"]!!.arrayValue!!.map { p ->
            p["content"]?.arrayValue?.joinToString("") { it["text"]?.stringValue ?: "" } ?: ""
        }
}

/** The editor page: holds the text the person sees and answers snapshot() with it. */
internal class FakePage : DocumentPage {
    var text: String = ""
    var editableNow = true
    val loads = mutableListOf<String>()
    override suspend fun snapshot(): String? = text
    override fun load(payload: String) {
        loads.add(payload)
        val o = JsonValue.parse(payload)
        text = if (o["mode"]?.stringValue == "rich") o["state"]!!.serializedString() else o["markdown"]?.stringValue ?: ""
    }
    override fun setEditable(editable: Boolean) { editableNow = editable }

    var selection: WikiSelection? = null
    var titles: Map<String, String> = emptyMap()
    var ready = true

    private fun refs(state: JsonValue): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        fun walk(n: JsonValue) {
            if (n["type"]?.stringValue == "codeBlock") return
            if (n["type"]?.stringValue == "text" && (n["marks"]?.arrayValue ?: emptyList()).all { it["type"]?.stringValue in setOf("bold", "italic", "strike", "underline") }) {
                Regex("\\[\\[([^\\]\\n]+)\\]\\]").findAll(n["text"]?.stringValue ?: "").forEach { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { out.add(null to it) } }
            }
            if (n["type"]?.stringValue == "fdWikiLink") out.add((n["attrs"]?.get("documentId")?.stringValue?.takeIf { it.isNotEmpty() }) to (n["attrs"]?.get("title")?.stringValue ?: ""))
            n["content"]?.arrayValue?.forEach(::walk)
        }
        walk(state)
        return out
    }

    /** What the page's JavaScript does for `wikiBind`: count title-only nodes, refuse when the count changed, otherwise bind. */
    override suspend fun wikiBind(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): Int? {
        if (!ready) return null
        val state = JsonValue.parse(text)
        val total = refs(state).count { it.first == null && WikiLinks.normalized(it.second) == WikiLinks.normalized(title) }
        if (expectedCount != null && expectedCount != total) return -1
        val bound = WikiLinks.binding(title, documentId, state, occurrence) ?: return 0
        text = bound.serializedString()
        return if (occurrence == null) total else 1
    }

    override suspend fun wikiInsert(documentId: String?, title: String, range: WikiRange?): WikiPlacement? {
        if (!ready) return null
        val state = JsonValue.parse(text)
        val node = JsonValue.obj("type" to JsonValue.Str("fdWikiLink"), "attrs" to JsonValue.obj("documentId" to (documentId?.let { JsonValue.Str(it) } ?: JsonValue.Null), "title" to JsonValue.Str(title)))
        val nodes = state["content"]!!.arrayValue!!.toMutableList()
        val last = nodes.last()
        nodes[nodes.lastIndex] = last.setting("content", JsonValue.Arr((last["content"]?.arrayValue ?: emptyList()) + node))
        val next = state.setting("content", JsonValue.Arr(nodes))
        text = next.serializedString()
        if (documentId != null) return WikiPlacement(-1, 0)
        val same = refs(next).filter { it.first == null && WikiLinks.normalized(it.second) == WikiLinks.normalized(title) }
        return WikiPlacement(same.size - 1, same.size)
    }

    override suspend fun wikiSelection(): WikiSelection? = selection
    override fun wikiTitles(titles: Map<String, String>) { this.titles = titles }

    /** The person edits paragraph [index] of a rich page. */
    fun editParagraph(index: Int, text: String) {
        val state = JsonValue.parse(this.text)
        val nodes = state["content"]!!.arrayValue!!.toMutableList()
        nodes[index] = nodes[index].setting("content", JsonValue.Arr(listOf(JsonValue.obj("type" to JsonValue.Str("text"), "text" to JsonValue.Str(text)))))
        this.text = state.setting("content", JsonValue.Arr(nodes)).serializedString()
    }

    fun paragraphs(): List<String> = JsonValue.parse(text)["content"]!!.arrayValue!!.map { p -> p["content"]?.arrayValue?.joinToString("") { it["text"]?.stringValue ?: "" } ?: "" }
}

/**
 * An in-memory Tower for one document. It models the exclusive-lease protocol (one holder, explicit takeover that fences the
 * previous token and advances the version), the concurrent protocol (several sessions at once), expected-version saves that
 * answer recovery for a stale base, recovery copies, lost responses and being offline.
 */
internal class FakeDocTower(val workspace: String = Fixtures.WORKSPACE_ID, val channel: String = Fixtures.CHANNEL_1, val docId: String = DOC_ID) : DocumentSaveApi {
    companion object { const val DOC_ID = "00000000-0000-4000-8000-0000000000a3" }

    class Lease(val id: String, val token: String, val session: String?, val holder: String)

    var title = "Plan"
    var version = 1
    var body: ByteArray = Docs.rich("One", "Two", "Three")
    var objectId = "00000000-0000-4000-8000-0000000000e1"
    var contentType = DocumentBody.ENVELOPE_CONTENT_TYPE
    var offline = false
    var inspectable = true
    var concurrentAdvertised = false
    var protocolMarkerHonoured = true
    var denyTakeover = false
    var denyReads: Int? = null
    var exclusive: Lease? = null
    val concurrent = mutableMapOf<String, Lease>()
    val fenced = mutableSetOf<String>()
    val tombstoned = mutableSetOf<String>()
    var loseNextPatchResponse = false
    /** While set, reading the head waits for it: a version check held in flight. */
    @Volatile var headGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var holderNpub = "npub1other" + "q".repeat(50)
    val patches = mutableListOf<JsonObject>()
    val recoveriesStored = mutableListOf<Triple<String, ByteArray, String>>()
    val objects = mutableMapOf<String, ByteArray>()
    var uploads = 0
    var counter = 0
    var acquires = mutableListOf<String>()
    val sha: String get() = Sha256Hex.of(body)

    private fun gate() { if (offline) throw UnknownHostException("offline") }
    private fun uuid(prefix: String) = "00000000-0000-4000-8000-%012d".format(++counter + prefix.hashCode().and(0xff) * 1000)

    init { objects[objectId] = body }

    /** Another editor saves a new head. */
    fun externalSave(bytes: ByteArray, newTitle: String = title) {
        version++; body = bytes; title = newTitle; objectId = uuid("ext"); objects[objectId] = bytes
    }

    override suspend fun acquireLease(workspaceId: String, appNpub: String, entityType: String, entityId: String): EditLease =
        acquireDocumentLease(workspaceId, appNpub, entityId, "legacy-" + (++counter), DOCUMENT_EDIT_PROTOCOL)

    override suspend fun acquireDocumentLease(workspaceId: String, appNpub: String, docId: String, editorSessionId: String, protocol: String, takeoverLeaseId: String?): EditLease {
        gate()
        acquires.add("$protocol:$editorSessionId" + (takeoverLeaseId?.let { ":takeover" } ?: ""))
        if (editorSessionId in tombstoned) throw LeaseException.Invalid("editor_superseded")
        if (protocol == CONCURRENT_DOCUMENT_EDIT_PROTOCOL) {
            if (!concurrentAdvertised) throw LeaseException.Invalid("concurrent_protocol_not_acknowledged")
            val lease = concurrent.values.firstOrNull { it.session == editorSessionId } ?: Lease(uuid("c"), "token-${++counter}", editorSessionId, "self").also { concurrent[it.token] = it }
            return EditLease(lease.id, lease.token, "2026-01-01T00:02:00Z", if (protocolMarkerHonoured) protocol else null, editorSessionId)
        }
        val held = exclusive
        if (takeoverLeaseId != null) {
            if (denyTakeover) throw TowerError.Http(403, "forbidden", "edit lease")
            if (held == null || held.id != takeoverLeaseId) throw LeaseException.Invalid("takeover_lease_changed")
            fenced.add(held.token); held.session?.let { tombstoned.add(it) }
            version++ // Tower's authority-barrier version: same text, new row version.
            objectId = uuid("barrier"); objects[objectId] = body
            val lease = Lease(uuid("l"), "token-${++counter}", editorSessionId, "self")
            exclusive = lease
            return EditLease(lease.id, lease.token, "2026-01-01T00:02:00Z", DOCUMENT_EDIT_PROTOCOL, editorSessionId)
        }
        if (held != null && held.session != editorSessionId) throw LeaseException.Held(held.holder, "2026-01-01T00:02:00Z")
        val lease = held ?: Lease(uuid("l"), "token-${++counter}", editorSessionId, "self").also { exclusive = it }
        return EditLease(lease.id, lease.token, "2026-01-01T00:02:00Z", DOCUMENT_EDIT_PROTOCOL, editorSessionId)
    }

    override suspend fun renewLease(workspaceId: String, appNpub: String, lease: EditLease): EditLease {
        gate()
        if (lease.token in fenced) throw LeaseException.Invalid("lease_fenced")
        val live = exclusive?.token == lease.token || concurrent.containsKey(lease.token)
        if (!live) throw LeaseException.Invalid("lease_expired")
        return lease
    }

    override suspend fun releaseLease(workspaceId: String, appNpub: String, lease: EditLease) {
        gate()
        if (exclusive?.token == lease.token) exclusive = null
        concurrent.remove(lease.token)
    }

    override suspend fun storeObject(workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String): String {
        gate()
        uploads++
        val id = uuid("o"); objects[id] = bytes; return id
    }

    override suspend fun patchDocument(workspaceId: String, appNpub: String, docId: String, body: JsonObject): SaveReply {
        gate()
        patches.add(body)
        val token = body["lease_token"]?.jsonPrimitive?.content ?: ""
        val baseAvailable = (body["base_available"] as? JsonPrimitive)?.content != "false"
        val rowVersion = body["row_version"]!!.jsonPrimitive.int
        val baseSha = body["base_body_sha256_hex"]!!.jsonPrimitive.content
        val staleBase = rowVersion != version || baseSha != sha
        if (!baseAvailable || staleBase) {
            val id = uuid("rec")
            recoveriesStored.add(Triple(id, objects[body["storage_object_id"]!!.jsonPrimitive.content]!!, body["title"]?.jsonPrimitive?.content ?: title))
            return reply(409, """{"code":"document_recovery_created","recovery":{"id":"$id"}}""")
        }
        val live = exclusive?.token == token || concurrent.containsKey(token)
        if (token in fenced || !live) return reply(409, """{"code":"lease_invalid"}""")
        this.body = objects[body["storage_object_id"]!!.jsonPrimitive.content]!!
        objectId = body["storage_object_id"]!!.jsonPrimitive.content
        title = body["title"]?.jsonPrimitive?.content ?: title
        version++
        if (loseNextPatchResponse) { loseNextPatchResponse = false; throw java.net.SocketTimeoutException("lost") }
        return reply(200, """{"doc":${docJson()},"canonical_version":{"body_sha256_hex":"${sha.uppercase()}"}}""")
    }

    private fun reply(status: Int, json: String) = SaveReply(status, json.toByteArray())

    fun docJson(): String = """{"id":"$docId","workspace_id":"$workspace","channel_id":"$channel","title":${studio.otherstuff.ambulando.core.nostr.NostrSerialization.quote(title)},"row_version":$version,"storage_object_id":"$objectId","created_at":"2026-10-01T00:00:00.000Z","updated_at":"2026-10-01T00:00:00.000Z"}"""

    override suspend fun inspect(workspaceId: String, appNpub: String, docId: String): DocumentEditInspection? {
        gate()
        if (!inspectable) return null
        return DocumentEditInspection(true, exclusive?.id, exclusive?.holder, concurrentAdvertised)
    }

    override suspend fun fetchHead(workspaceId: String, appNpub: String, docId: String): DocHead? {
        gate()
        headGate?.await()
        denyReads?.let { throw TowerError.Http(it, "denied", "document body") }
        val doc = DocRecord.parse(kotlinx.serialization.json.Json.parseToJsonElement(docJson()) as JsonObject)
        return DocHead(doc, DocBodyRecord(workspace, docId, objectId, sha, contentType, version.toLong(), body, "2026-10-01T00:00:00Z"))
    }

    override suspend fun recoveries(workspaceId: String, appNpub: String, docId: String): List<DocumentRecovery> {
        gate()
        return recoveriesStored.map { DocumentRecovery(it.first, "obj-" + it.first, it.third, "2026-10-01T00:00:00Z") }.reversed()
    }

    override suspend fun recoveryBody(workspaceId: String, appNpub: String, docId: String, recoveryId: String): EncodedBody {
        gate()
        val found = recoveriesStored.first { it.first == recoveryId }
        return EncodedBody(found.second, DocumentBody.ENVELOPE_CONTENT_TYPE)
    }

    override suspend fun resolveRecovery(workspaceId: String, appNpub: String, docId: String, recoveryId: String, promote: Boolean, headRowVersion: Int?, headSha256: String?, leaseToken: String?) {
        gate()
        if (promote) {
            if (headRowVersion != version || headSha256 != sha) throw TowerError.Http(409, "stale_head", "recovery")
            val found = recoveriesStored.first { it.first == recoveryId }
            body = found.second; objectId = uuid("promoted"); objects[objectId] = body; version++
        }
        recoveriesStored.removeAll { it.first == recoveryId }
    }
}
