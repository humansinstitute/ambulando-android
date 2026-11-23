package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*

/** Versioned, packaged starters shared with web2753. No remote template installation. */
object ArtefactTemplates {
    data class Starter(val id: String, val type: String, val label: String)
    val starters = listOf(Starter("blank", "document", "Blank document"), Starter("brief", "document", "Working brief"),
        Starter("blank", "excalidraw", "Whiteboard"), Starter("explorer", "html", "Interactive explorer"), Starter("system", "system-flow", "System Flow"))
    val styles = linkedMapOf("default" to "Ambulando", "paper" to "Paper")
    fun descriptor(type: String, template: String, style: String): JsonObject {
        require(starters.any { it.type == type && it.id == template } && style in styles)
        return buildJsonObject {
            put("schema_version", 1); put("type", type); put("format_version", 1); put("editor_id", "builtin.$type")
            put("template_id", template); put("template_version", 1); put("style_id", style); put("style_version", 1)
        }
    }
    fun source(type: String, template: String, style: String, title: String): String {
        descriptor(type, template, style)
        val paper = if (style == "paper") "#ffffff" else "#faf8fc"
        val ink = if (style == "paper") "#202124" else "#2b1b3c"
        val accent = if (style == "paper") "#35655c" else "#70509a"
        return when (type) {
            "document" -> if (template == "brief") "# Purpose\n\nDescribe the progress this work should make.\n\n## Evidence\n\nRecord the facts and source links.\n\n## Decisions\n\nExplain the choices and open questions.\n\n## Next steps\n\n- Define the next action and owner.\n" else ""
            "excalidraw" -> JsonObject(BoardScene.empty() + ("appState" to buildJsonObject { put("viewBackgroundColor", paper) })).toString()
            "system-flow" -> buildJsonObject {
                put("schemaVersion", 1); put("title", title)
                put("nodes", JsonArray(listOf(SystemFlow.node("start", "view", "Start", 0.0), SystemFlow.node("action", "action", "Take action", 300.0))))
                put("edges", JsonArray(listOf(SystemFlow.edge("start-action", "start", "action", "Continue"))))
            }.toString()
            else -> {
                val escaped = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
                """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$escaped</title><style>:root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:$paper;color:$ink;font:16px/1.5 system-ui,sans-serif}main{max-width:72rem;margin:auto;padding:clamp(1rem,4vw,3rem)}h1{line-height:1.15}input{accent-color:$accent;font:inherit;min-height:44px}label{display:block;margin:.5rem 0}output{display:block;font-size:2rem;color:$accent}:focus-visible{outline:3px solid $accent;outline-offset:2px}</style></head><body><main><h1>$escaped</h1><p>Change the value to explore a scenario. Edit the source to develop your idea.</p><label>Progress <input id="progress" type="range" min="0" max="100" value="30"></label><output id="result">30%</output><p>Controls change this view. Save source changes as a new artefact version.</p></main><script>document.getElementById('progress').addEventListener('input',event=>{document.getElementById('result').textContent=event.target.value+'%';});</script></body></html>"""
            }
        }
    }
}
