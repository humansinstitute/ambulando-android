package studio.otherstuff.ambulando.core.docs

import java.net.URLDecoder

/**
 * Document links as the web and the iPhone/Mac apps write them: `[Title](wiki:<document-id>)` in Markdown and an
 * `fdWikiLink` node `{documentId, title}` in a rich document. The id is the identity; a link grants no access, and a link
 * to a page this device has not saved is still opened by id so Tower decides. Matches inside code are plain text.
 */
object WikiLinks {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val markdownLink = Regex("\\]\\(\\s*wiki:([^)\\s]+)\\s*\\)")
    private val fencedCode = Regex("(?s)(```|~~~).*?(\\1|\\z)")
    private val inlineCode = Regex("`[^`\\n]*`")

    /** The document id a `wiki:<id>` address names, or null for any other address. */
    fun targetId(address: String?): String? {
        if (address == null || !address.startsWith("wiki:", ignoreCase = true)) return null
        val raw = address.substring(5).trim()
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return decoded.lowercase().takeIf { uuid.matches(it) }
    }

    /** Whether the body links to [target] outside code. */
    fun links(body: DocumentBody, target: String): Boolean {
        val id = target.lowercase()
        val state = body.editorState
        if (state != null) return richLinks(state, id)
        val withoutCode = body.markdown.replace(fencedCode, " ").replace(inlineCode, " ")
        return markdownLink.findAll(withoutCode).any { targetId("wiki:" + it.groupValues[1]) == id }
    }

    private fun richLinks(node: JsonValue, id: String): Boolean {
        // A code block's text is not a link; its descendants are plain text.
        if (node["type"]?.stringValue == "codeBlock") return false
        if (node["type"]?.stringValue == "fdWikiLink" && node["attrs"]?.get("documentId")?.stringValue?.lowercase() == id) return true
        val marked = node["marks"]?.arrayValue?.any {
            it["type"]?.stringValue == "link" && targetId(it["attrs"]?.get("href")?.stringValue) == id &&
                node["marks"]?.arrayValue?.none { m -> m["type"]?.stringValue == "code" } == true
        } == true
        return marked || (node["content"]?.arrayValue?.any { richLinks(it, id) } == true)
    }
}
