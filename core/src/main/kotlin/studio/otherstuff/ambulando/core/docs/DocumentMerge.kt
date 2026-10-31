package studio.otherstuff.ambulando.core.docs

import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * An exact local copy of a document version: title, body bytes and the storage identity they were saved under. Review
 * history is local recovery, never a claim that Tower published a version.
 */
class PreservedDocumentSnapshot(
    val title: String,
    val bytes: ByteArray,
    val contentType: String,
    val rowVersion: Int,
    val sha256: String,
    val storageObjectId: String,
    val id: String = UUID.randomUUID().toString().lowercase(),
) {
    val document: DocumentBody get() = DocumentBody(bytes, contentType)

    /** Whether this is the version [body] describes (row version, hash and storage object all agree). */
    fun matches(body: DocBodyRecord): Boolean = rowVersion.toLong() == body.docRowVersion && sha256 == body.sha256 && storageObjectId == body.storageObjectId

    /** The same version identity (not the same bytes). */
    fun sameVersion(other: PreservedDocumentSnapshot): Boolean =
        rowVersion == other.rowVersion && sha256 == other.sha256 && storageObjectId == other.storageObjectId

    /** The retained bytes still hash to the identity they claim. */
    val verified: Boolean get() = Sha256Hex.of(bytes) == sha256

    override fun equals(other: Any?): Boolean = other is PreservedDocumentSnapshot && title == other.title && bytes.contentEquals(other.bytes) &&
        contentType == other.contentType && rowVersion == other.rowVersion && sha256 == other.sha256 && storageObjectId == other.storageObjectId

    override fun hashCode(): Int = listOf(title, bytes.contentHashCode(), contentType, rowVersion, sha256, storageObjectId).hashCode()

    companion object {
        fun of(body: DocBodyRecord, title: String): PreservedDocumentSnapshot =
            PreservedDocumentSnapshot(title, body.bytes, body.contentType, body.docRowVersion.toInt(), body.sha256, body.storageObjectId)
    }
}

/** A merge preview kept when it is replaced, with the head it was prepared against. */
class PreservedMergePreview(val title: String, val text: String, val head: PreservedDocumentSnapshot, val id: String = UUID.randomUUID().toString().lowercase()) {
    override fun equals(other: Any?): Boolean = other is PreservedMergePreview && title == other.title && text == other.text && head == other.head
    override fun hashCode(): Int = listOf(title, text, head).hashCode()
}

/**
 * Durable review state for one draft key (connection, workspace, document): the retained common base, preserved
 * copies, the merge inputs, partial overlap choices and the edited preview. Persisted separately from the mutable
 * remote cache so a restored older draft can still find its exact base.
 */
class DocumentReviewJournal {
    var pendingMergedSave = false
    var base: PreservedDocumentSnapshot? = null
    val retained = mutableListOf<PreservedDocumentSnapshot>()
    val savedBases = mutableListOf<PreservedDocumentSnapshot>()
    var reviewOriginal: PreservedDocumentSnapshot? = null
    var mergeBase: PreservedDocumentSnapshot? = null
    var mergeLocal: PreservedDocumentSnapshot? = null
    var mergeLatest: PreservedDocumentSnapshot? = null
    var choices = mutableMapOf<String, String>()
    var previewText: String? = null
    var previewTitle: String? = null
    val previousPreviews = mutableListOf<PreservedMergePreview>()

    fun retainBase(snapshot: PreservedDocumentSnapshot) {
        val old = base
        if (old != null && savedBases.none { it.sameVersion(old) }) savedBases.add(old)
        base = snapshot
    }

    /** The retained base for exactly this version, or null when none verifies against its hash. */
    fun commonBase(snapshot: PreservedDocumentSnapshot): PreservedDocumentSnapshot? =
        (listOfNotNull(base) + savedBases).firstOrNull { it.sameVersion(snapshot) && it.verified }

    fun preserve(snapshot: PreservedDocumentSnapshot) {
        if (retained.none { it.bytes.contentEquals(snapshot.bytes) && it.title == snapshot.title && it.rowVersion == snapshot.rowVersion }) retained.add(snapshot)
    }

    val isEmpty: Boolean get() = base == null && retained.isEmpty() && savedBases.isEmpty() && previewText == null && previousPreviews.isEmpty() &&
        mergeBase == null && !pendingMergedSave
}

/**
 * Conservative structural three-way merge. Rich state, marks, attributes and unknown envelope fields are retained;
 * derived Markdown is regenerated only after merging authoritative rich state. Ambiguous changes need a choice, and
 * large inputs become explicit conflicts rather than guesses.
 */
object DocumentMerge {
    enum class Choice(val raw: String) {
        LOCAL("local"), LATEST("latest");

        companion object { fun from(raw: String?): Choice? = entries.firstOrNull { it.raw == raw } }
    }

    class Conflict(val id: String, val label: String, val local: String, val latest: String)

    class Result(val title: String, val bytes: ByteArray, val contentType: String, val conflicts: List<Conflict>)

    fun merge(
        base: PreservedDocumentSnapshot,
        local: PreservedDocumentSnapshot,
        latest: PreservedDocumentSnapshot,
        choices: Map<String, Choice> = emptyMap(),
    ): Result {
        if (!base.verified) throw DocumentEditError(DocumentEditError.Reason.INTEGRITY)
        val b = base.document
        val l = local.document
        val r = latest.document
        if (!b.isEditable || !l.isEditable || !r.isEditable || b.representation != l.representation || b.representation != r.representation ||
            b.editing != l.editing || b.editing != r.editing) throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)

        val conflicts = mutableListOf<Conflict>()
        fun conflict(path: String, local: JsonValue?, remote: JsonValue?): JsonValue? {
            if (choices[path] == null) {
                val label = when {
                    path == "/title" -> "Title"
                    path.contains("/marks") -> "Text formatting"
                    path.contains("/attrs") -> "Document formatting"
                    else -> "Overlapping passage"
                }
                conflicts.add(Conflict(path, label, readable(local), readable(remote)))
            }
            return if (choices[path] == Choice.LATEST) remote else local
        }

        fun mergeValue(base: JsonValue?, local: JsonValue?, remote: JsonValue?, path: String): JsonValue? {
            if (local == remote) return local
            if (local == base) return remote
            if (remote == base) return local
            if (base is JsonValue.Obj && local is JsonValue.Obj && remote is JsonValue.Obj &&
                base.pairs.map { it.first }.toSet().size == base.pairs.size && local.pairs.map { it.first }.toSet().size == local.pairs.size &&
                remote.pairs.map { it.first }.toSet().size == remote.pairs.size) {
                val keys = LinkedHashSet<String>().apply { (base.pairs + local.pairs + remote.pairs).forEach { add(it.first) } }
                return JsonValue.Obj(keys.mapNotNull { key ->
                    val escaped = key.replace("~", "~0").replace("/", "~1")
                    mergeValue(base[key], local[key], remote[key], "$path/$escaped")?.let { key to it }
                })
            }
            if (base is JsonValue.Arr && local is JsonValue.Arr && remote is JsonValue.Arr) {
                // Align unchanged nodes before recursive merging, so insertions/deletions do not shift identities.
                combine(base.items, local.items, remote.items)?.let { return JsonValue.Arr(it) }
                if (base.items.size == local.items.size && base.items.size == remote.items.size) {
                    return JsonValue.Arr(base.items.indices.mapNotNull { mergeValue(base.items[it], local.items[it], remote.items[it], "$path/$it") })
                }
            }
            if (base is JsonValue.Str && local is JsonValue.Str && remote is JsonValue.Str) {
                combine(base.value.split("\n"), local.value.split("\n"), remote.value.split("\n"))?.let { return JsonValue.Str(it.joinToString("\n")) }
                combine(graphemes(base.value), graphemes(local.value), graphemes(remote.value))?.let { return JsonValue.Str(it.joinToString("")) }
            }
            return conflict(path, local, remote)
        }

        val titleMerged = mergeValue(JsonValue.Str(base.title), JsonValue.Str(local.title), JsonValue.Str(latest.title), "/title")?.stringValue ?: local.title

        fun normalized(doc: DocumentBody): JsonValue? {
            var root = doc.root ?: return null
            if (doc.editing == DocumentBody.Editing.ProseMirror || doc.editing == DocumentBody.Editing.MarkdownModel) {
                var model = doc.contentModel ?: return null
                val derived = if (doc.editing == DocumentBody.Editing.ProseMirror)
                    listOf("content", "content_blocks", "content_format", "editor_state_format", "editor_state_version") else listOf("content_blocks")
                for (key in derived) model = model.setting(key, null)
                root = if (doc.representation == DocumentBody.Representation.LEGACY_MODEL) model else root.setting("content_model", model)
            }
            return root
        }

        val bytes: ByteArray = if (b.representation == DocumentBody.Representation.RAW_MARKDOWN) {
            val merged = mergeValue(JsonValue.Str(b.markdown), JsonValue.Str(l.markdown), JsonValue.Str(r.markdown), "/body")?.stringValue ?: l.markdown
            merged.toByteArray(Charsets.UTF_8)
        } else {
            val root = mergeValue(normalized(b), normalized(l), normalized(r), "/body") ?: throw DocumentEditError(DocumentEditError.Reason.INTEGRITY)
            val merged = DocumentBody(root.serialized(), latest.contentType)
            val state = merged.editorState
            if (state != null) {
                DocumentBody.checkEdited(state, state)
                merged.encoded(state).bytes
            } else merged.encoded(merged.markdown).bytes
        }
        return Result(titleMerged, bytes, latest.contentType, conflicts)
    }

    private fun readable(value: JsonValue?): String {
        if (value == null) return "Deleted"
        value.stringValue?.let { return it }
        value.arrayValue?.let { nodes -> return if (nodes.isEmpty()) "Deleted" else nodes.joinToString("\n\n") { readable(it) } }
        if (value["type"]?.stringValue == "doc") return ProseMirrorMarkdown.markdown(value)
        value["text"]?.stringValue?.let { return it }
        if (value["type"]?.stringValue != null) {
            val text = ProseMirrorMarkdown.blockMarkdown(value)
            return text.ifEmpty { "Formatting or embedded content changed" }
        }
        return "Document details changed"
    }

    /** Extended grapheme clusters, so a merge never splits an emoji, a surrogate pair or a combining sequence. */
    internal fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val out = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return out
    }

    private class Edit<T>(val start: Int, val end: Int, val replacement: List<T>)

    /** Bounded LCS. Excessively large inputs return null (callers turn that into an explicit conflict, never a guess). */
    private fun <T> edits(base: List<T>, next: List<T>): List<Edit<T>>? {
        if (base.size > 4000 || next.size > 4000 || (base.size + 1).toLong() * (next.size + 1) > 1_000_000) return null
        val width = next.size + 1
        val table = IntArray((base.size + 1) * width)
        if (base.isNotEmpty() && next.isNotEmpty()) {
            for (i in base.size - 1 downTo 0) for (j in next.size - 1 downTo 0) {
                table[i * width + j] = if (base[i] == next[j]) 1 + table[(i + 1) * width + j + 1]
                else maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
            }
        }
        var i = 0
        var j = 0
        val result = mutableListOf<Edit<T>>()
        while (i < base.size || j < next.size) {
            if (i < base.size && j < next.size && base[i] == next[j]) { i++; j++; continue }
            val start = i
            val replacement = mutableListOf<T>()
            while (i < base.size || j < next.size) {
                if (i < base.size && j < next.size && base[i] == next[j]) break
                if (j < next.size && (i == base.size || table[i * width + j + 1] >= table[(i + 1) * width + j])) {
                    replacement.add(next[j]); j++
                } else i++
            }
            result.add(Edit(start, i, replacement))
        }
        return result
    }

    private fun <T> overlaps(l: Edit<T>, r: Edit<T>): Boolean {
        val lEmpty = l.start == l.end
        val rEmpty = r.start == r.end
        return (l.start < r.end && r.start < l.end) ||
            (lEmpty && rEmpty && l.start == r.start) ||
            (lEmpty && r.start < l.start && l.start < r.end) ||
            (rEmpty && l.start < r.start && r.start < l.end)
    }

    private fun <T> combine(base: List<T>, local: List<T>, remote: List<T>): List<T>? {
        val left = edits(base, local) ?: return null
        val right = edits(base, remote) ?: return null
        val all = left.toMutableList()
        for (r in right) {
            if (all.any { it.start == r.start && it.end == r.end && it.replacement == r.replacement }) continue
            if (left.any { overlaps(it, r) }) return null
            all.add(r)
        }
        val result = base.toMutableList()
        val ordered = all.sortedWith { a, b ->
            if (a.start == b.start) (b.end - b.start).compareTo(a.end - a.start) else b.start.compareTo(a.start)
        }
        for (edit in ordered) {
            for (k in edit.end - 1 downTo edit.start) result.removeAt(k)
            result.addAll(edit.start, edit.replacement)
        }
        return result
    }
}

/** Readable two-way line diff, explicitly distinct from the common-base merge engine. */
object DocumentReviewDiff {
    class Line(val id: Int, val prefix: String, val text: String)

    /** Context lines have a space prefix, removals "−" (local only) and additions "+" (latest only). Empty when the inputs are too large to diff. */
    fun lines(local: String, latest: String): List<Line> {
        val a = local.split("\n")
        val b = latest.split("\n")
        // Avoid unbounded diff work on large documents; the two labelled full versions remain available.
        if (a.size + b.size > 10_000) return emptyList()
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val ma = a.subList(head, a.size - tail)
        val mb = b.subList(head, b.size - tail)
        val out = mutableListOf<Line>()
        fun add(prefix: String, text: String) { out.add(Line(out.size, prefix, text)) }
        for (k in 0 until head) add(" ", a[k])
        if ((ma.size + 1).toLong() * (mb.size + 1) > 4_000_000) {
            ma.forEach { add("−", it) }
            mb.forEach { add("+", it) }
        } else {
            val width = mb.size + 1
            val table = IntArray((ma.size + 1) * width)
            for (i in ma.size - 1 downTo 0) for (j in mb.size - 1 downTo 0) {
                table[i * width + j] = if (ma[i] == mb[j]) 1 + table[(i + 1) * width + j + 1] else maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
            }
            var i = 0
            var j = 0
            while (i < ma.size || j < mb.size) {
                when {
                    i < ma.size && j < mb.size && ma[i] == mb[j] -> { add(" ", ma[i]); i++; j++ }
                    i < ma.size && (j == mb.size || table[(i + 1) * width + j] >= table[i * width + j + 1]) -> { add("−", ma[i]); i++ }
                    else -> { add("+", mb[j]); j++ }
                }
            }
        }
        for (k in a.size - tail until a.size) add(" ", a[k])
        return out
    }
}
