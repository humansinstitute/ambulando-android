package studio.otherstuff.ambulando.core.chat

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The text-only parts of composer uploads: user-facing errors, the names pasted items get, and the Markdown a
 * finished upload puts in place of its [UploadToken]. Staging, the upload queue and delivery to live composers are
 * platform work and live in the app.
 */
enum class ComposerUploadError(val message: String) {
    LOCKED("Unlock to upload."),
    OTHER_CONNECTION("This upload belongs to another connection."),
    TOO_LARGE("Pasted items over 100 MB are not uploaded from this app."),
    UNREADABLE("Could not read the pasted item."),
    UNRESOLVED("Wait for pasted items to finish uploading, or remove them."),
}

/** Thrown when a body still holds an upload placeholder or an upload cannot proceed. */
class ComposerUploadException(val error: ComposerUploadError) : Exception(error.message)

object ComposerUploadText {
    /** At most this many items per paste (as the photo picker). */
    const val MAX_ITEMS_PER_PASTE = 4

    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** `<context>-image-<UTC time>.png`, as the web names pasted images. */
    fun imageName(context: String, now: Instant = Instant.now()): String {
        val stamp = stampFormat.format(now.truncatedTo(ChronoUnit.SECONDS)).replace(":", "-").replace("Z", "")
        return "$context-image-$stamp.png"
    }

    /** The pasted file's own name ("Attachment" when it has none), with the type's extension when it lacks one. */
    fun fileName(suggested: String?, preferredExtension: String?): String {
        val base = (suggested ?: "").trim()
        val name = base.ifEmpty { "Attachment" }
        if (preferredExtension.isNullOrEmpty() || pathExtension(name).isNotEmpty()) return name
        return "$name.$preferredExtension"
    }

    /** `NSString.pathExtension`: the text after the last dot of the last path component, if any. */
    internal fun pathExtension(name: String): String {
        val component = name.trimEnd('/').substringAfterLast('/')
        val dot = component.lastIndexOf('.')
        if (dot <= 0 || dot == component.length - 1) return ""
        return component.substring(dot + 1)
    }

    /** The Markdown that replaces a finished upload's placeholder. */
    fun markdown(kind: UploadToken.Kind, objectId: String, filename: String): String = when (kind) {
        UploadToken.Kind.IMAGE -> StorageMarkdown.image(objectId, filename)
        UploadToken.Kind.FILE -> StorageMarkdown.file(objectId, filename)
    }
}
