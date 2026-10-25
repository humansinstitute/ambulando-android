package studio.otherstuff.ambulando.app

import org.json.JSONObject

/** Converts the upload coordinator's storage Markdown to the bundled editor's storage node. */
internal fun editorUploadNode(markdown: String): String? {
    val found = Regex("^(!?)\\[([^]\\n]*)]\\(storage://([A-Fa-f0-9-]{36})\\)$").matchEntire(markdown) ?: return null
    val image = found.groupValues[1] == "!"
    val name = found.groupValues[2]; val id = found.groupValues[3]
    val attrs = JSONObject().put("src", "storage://$id").put("title", name)
    if (image) attrs.put("objectId", id).put("alt", name) else attrs.put("label", name)
    return JSONObject().put("type", if (image) "fdStorageImage" else "fdStorageFile").put("attrs", attrs).toString()
}
