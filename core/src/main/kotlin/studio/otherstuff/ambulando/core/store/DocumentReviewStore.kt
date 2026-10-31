package studio.otherstuff.ambulando.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.docs.DocumentReviewJournal
import studio.otherstuff.ambulando.core.docs.PreservedDocumentSnapshot
import studio.otherstuff.ambulando.core.docs.PreservedMergePreview
import java.util.Base64

/**
 * The review journal's stored form (`app_state` key `doc.review.v1:<draft key>`). Body bytes are base64 so any stored
 * document, including non-UTF-8 or invalid-JSON text, survives exactly.
 */
object DocumentReviewCodec {
    fun key(draftKey: String): String = "doc.review.v1:$draftKey"
    fun corruptKey(draftKey: String): String = "doc.review.corrupt.v1:$draftKey"

    /** Receipts of document uploads that completed before a save did; keyed like drafts (connection id first). */
    const val UPLOAD_RECEIPT_PREFIX = "doc.completed-upload:"

    fun encode(journal: DocumentReviewJournal): String = buildJsonObject {
        put("pendingMergedSave", journal.pendingMergedSave)
        journal.base?.let { put("base", snapshot(it)) }
        put("retained", buildJsonArray { journal.retained.forEach { add(snapshot(it)) } })
        put("savedBases", buildJsonArray { journal.savedBases.forEach { add(snapshot(it)) } })
        journal.reviewOriginal?.let { put("reviewOriginal", snapshot(it)) }
        journal.mergeBase?.let { put("mergeBase", snapshot(it)) }
        journal.mergeLocal?.let { put("mergeLocal", snapshot(it)) }
        journal.mergeLatest?.let { put("mergeLatest", snapshot(it)) }
        put("choices", buildJsonObject { journal.choices.forEach { (k, v) -> put(k, v) } })
        journal.previewText?.let { put("previewText", it) }
        journal.previewTitle?.let { put("previewTitle", it) }
        put("previousPreviews", buildJsonArray {
            journal.previousPreviews.forEach { p ->
                add(buildJsonObject { put("id", p.id); put("title", p.title); put("text", p.text); put("head", snapshot(p.head)) })
            }
        })
    }.toString()

    fun decode(text: String): DocumentReviewJournal {
        val o = Json.parseToJsonElement(text) as JsonObject
        return DocumentReviewJournal().apply {
            pendingMergedSave = (o["pendingMergedSave"] as? JsonPrimitive)?.boolean ?: false
            base = (o["base"] as? JsonObject)?.let(::snapshot)
            (o["retained"] as? JsonArray)?.forEach { retained.add(snapshot(it as JsonObject)) }
            (o["savedBases"] as? JsonArray)?.forEach { savedBases.add(snapshot(it as JsonObject)) }
            reviewOriginal = (o["reviewOriginal"] as? JsonObject)?.let(::snapshot)
            mergeBase = (o["mergeBase"] as? JsonObject)?.let(::snapshot)
            mergeLocal = (o["mergeLocal"] as? JsonObject)?.let(::snapshot)
            mergeLatest = (o["mergeLatest"] as? JsonObject)?.let(::snapshot)
            (o["choices"] as? JsonObject)?.forEach { (k, v) -> choices[k] = (v as JsonPrimitive).content }
            previewText = str(o["previewText"])
            previewTitle = str(o["previewTitle"])
            (o["previousPreviews"] as? JsonArray)?.forEach {
                val p = it as JsonObject
                previousPreviews.add(PreservedMergePreview(str(p["title"]) ?: "", str(p["text"]) ?: "", snapshot(p["head"] as JsonObject), str(p["id"]) ?: java.util.UUID.randomUUID().toString()))
            }
        }
    }

    private fun str(e: kotlinx.serialization.json.JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun snapshot(s: PreservedDocumentSnapshot): JsonObject = buildJsonObject {
        put("id", s.id); put("title", s.title); put("contentType", s.contentType); put("rowVersion", s.rowVersion)
        put("sha256", s.sha256); put("storageObjectId", s.storageObjectId)
        put("bytes", Base64.getEncoder().encodeToString(s.bytes))
    }

    private fun snapshot(o: JsonObject): PreservedDocumentSnapshot = PreservedDocumentSnapshot(
        title = str(o["title"]) ?: "", bytes = Base64.getDecoder().decode(str(o["bytes"]) ?: ""), contentType = str(o["contentType"]) ?: "",
        rowVersion = (o["rowVersion"] as JsonPrimitive).int, sha256 = str(o["sha256"]) ?: "", storageObjectId = str(o["storageObjectId"]) ?: "",
        id = str(o["id"]) ?: java.util.UUID.randomUUID().toString(),
    )
}

/** Stores the journal; an empty one removes the key. */
suspend fun StoreWriter.saveDocumentReview(journal: DocumentReviewJournal, draftKey: String) {
    setState(DocumentReviewCodec.key(draftKey), if (journal.isEmpty) null else DocumentReviewCodec.encode(journal))
}

/**
 * Reads the journal and, when it cannot be decoded, keeps its raw text under the corrupt key (never discarded). The merged-draft
 * hold may have been in the lost journal, so the replacement fails closed: nothing autosaves until the person saves explicitly.
 */
suspend fun LocalStore.loadDocumentReview(draftKey: String): DocumentReviewJournal {
    val text = reader.state(DocumentReviewCodec.key(draftKey)) ?: return DocumentReviewJournal()
    return try {
        DocumentReviewCodec.decode(text)
    } catch (_: Exception) {
        writer.setState(DocumentReviewCodec.corruptKey(draftKey), text)
        DocumentReviewJournal().also { it.pendingMergedSave = true }
    }
}
