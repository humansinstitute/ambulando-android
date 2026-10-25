package studio.otherstuff.ambulando.core.chat

import studio.otherstuff.ambulando.core.docs.TextRange
import studio.otherstuff.ambulando.core.docs.TextSupport
import studio.otherstuff.ambulando.core.transport.MessageAttachment

/**
 * Storage Markdown as the web client writes and reads it (its storage-image and storage-file Markdown builders and
 * its chat attachment helpers).
 */
object StorageMarkdown {
    /** `![alt](storage://id)`: brackets dropped from the alt text, "Image" when nothing is left. */
    fun image(objectId: String, alt: String): String {
        val safe = TextSupport.trimWhitespacesAndNewlines(alt.replace("[", "").replace("]", ""))
        return "![${safe.ifEmpty { "Image" }}](storage://$objectId)"
    }

    /** `[name](storage://id)`: backslashes and brackets escaped, "Uploaded file" when the name is empty. */
    fun file(objectId: String, name: String): String {
        val escaped = TextSupport.trimWhitespacesAndNewlines(
            name.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]"))
        return "[${escaped.ifEmpty { "Uploaded file" }}](storage://$objectId)"
    }

    /** The web's storage Markdown pattern, also accepting the backslash escapes [file] writes. */
    private val reference = Regex("""(!?)\[((?:\\.|[^\]\n\\])*)\]\(storage://([A-Za-z0-9-]+)(?:\s+"[^"]*")?\)""")
    private val escape = Regex("""\\(.)""")

    /** Storage references in a body, first occurrence of each object only (kind from the `!` prefix). */
    fun references(body: String): List<MessageAttachment> {
        val seen = mutableSetOf<String>()
        val found = mutableListOf<MessageAttachment>()
        for (match in reference.findAll(body)) {
            val id = TextSupport.trimWhitespaces(match.groupValues[3])
            if (id.isEmpty() || !seen.add(id.lowercase())) continue
            val label = TextSupport.trimWhitespacesAndNewlines(escape.replace(match.groupValues[2], "$1"))
            found.add(MessageAttachment(
                kind = if (match.groupValues[1] == "!") "image" else "file",
                storageObjectId = id,
                filename = label.ifEmpty { "Attachment" },
                mimeType = null,
                sizeBytes = null,
            ))
        }
        return found
    }

    /** Attachments plus the body's storage references not already among them (the web's attachment merge). */
    fun merged(attachments: List<MessageAttachment>, body: String): List<MessageAttachment> {
        val seen = attachments.map { it.storageObjectId.lowercase() }.toMutableSet()
        return attachments + references(body).filter { seen.add(it.storageObjectId.lowercase()) }
    }

    /** Attachments not already shown inline by a storage link in the body. */
    fun standalone(attachments: List<MessageAttachment>, body: String): List<MessageAttachment> {
        if (attachments.isEmpty() || !body.contains("storage://")) return attachments
        val linked = references(body).map { it.storageObjectId.lowercase() }.toSet()
        return attachments.filter { it.storageObjectId.lowercase() !in linked }
    }
}

/** The placeholder a pasted image or file holds in the composer until its upload resolves. */
object UploadToken {
    enum class Kind(val raw: String) {
        IMAGE("image"), FILE("file");

        companion object {
            fun fromRaw(raw: String): Kind? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** One identified placeholder found in text. */
    data class Found(val kind: Kind, val id: String)

    fun make(kind: Kind, id: String): String = "[ Uploading ${kind.raw} $id... ]"

    /** Any upload placeholder, including the web's numbered and plain forms. */
    private val any = Regex("""\[ Uploading (?:image|file)(?: [A-Za-z0-9-]+)?(?:\.\.\.|…) \]""")
    private val identified = Regex("""\[ Uploading (image|file) ([A-Za-z0-9-]+)(?:\.\.\.|…) \]""")

    fun containsAny(text: String): Boolean = any.containsMatchIn(text)

    /** True when `token` (or its typographic-ellipsis form) is in `text`. */
    fun containsToken(token: String, text: String): Boolean = text.contains(token) || text.contains(token.replace("...", "…"))

    /** Upload ids of the placeholders in `text`, in order. */
    fun ids(text: String): List<Found> = identified.findAll(text).mapNotNull { m ->
        Kind.fromRaw(m.groupValues[1])?.let { Found(it, m.groupValues[2]) }
    }.toList()
}

/** Replaced composer text and where its selection moved. */
data class ComposerEdit(val text: String, val selection: TextRange?)

/** Text edits for the composer, in UTF-16 offsets (the text field's selection units). */
object ComposerText {
    /**
     * Replaces the first occurrence of `token`; the selection keeps its place relative to the surrounding text
     * (inside the token it moves to the end of the replacement). Null when the token is not there.
     */
    fun replacing(token: String, replacement: String, text: String, selection: TextRange?): ComposerEdit? {
        var location = text.indexOf(token)
        var length = token.length
        if (location < 0 && UploadToken.containsAny(token)) {
            val smart = token.replace("...", "…")
            location = text.indexOf(smart)
            length = smart.length
        }
        if (location < 0) return null
        val next = text.substring(0, location) + replacement + text.substring(location + length)
        val delta = replacement.length - length
        fun remap(offset: Int): Int = when {
            offset <= location -> offset
            offset < location + length -> location + replacement.length
            else -> offset + delta
        }
        val moved = selection?.let { s ->
            val start = remap(s.location)
            val end = remap(s.end)
            TextRange(start, maxOf(0, end - start))
        }
        return ComposerEdit(next, moved)
    }

    /**
     * Where the selection belongs after `old` became `new` through one contiguous edit (a mention completion, a
     * replaced placeholder): offsets before the edit stay, offsets after it shift, offsets inside it go to its end.
     */
    fun remap(selection: TextRange, old: String, new: String): TextRange {
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val oldEnd = old.length - suffix
        val newEnd = new.length - suffix
        fun map(offset: Int): Int = when {
            // Before the edit (an insertion exactly at the caret moves the caret after it).
            offset < prefix || (offset == prefix && prefix < oldEnd) -> minOf(offset, new.length)
            offset >= oldEnd -> offset - oldEnd + newEnd
            else -> newEnd
        }
        val start = map(selection.location)
        val end = map(selection.end)
        return TextRange(start, maxOf(0, end - start))
    }
}
