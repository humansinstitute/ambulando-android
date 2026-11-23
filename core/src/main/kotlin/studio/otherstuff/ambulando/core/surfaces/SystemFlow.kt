package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*

/** Web schema 1–3. Edits replace only the selected field: scenes, styling and linked diagrams survive intact. */
object SystemFlow {
    const val MAX_BYTES = 32 * 1024 * 1024
    val kinds = listOf("view", "action", "state", "decision", "exception", "whiteboard", "system", "component")
    private fun fail(): Nothing = throw IllegalArgumentException("Invalid or unsupported System Flow. Open the original source; no content was changed.")
    private fun obj(v: JsonElement?, keys: Set<String>): JsonObject = (v as? JsonObject)?.also { if (!keys.containsAll(it.keys)) fail() } ?: fail()
    private fun text(v: JsonElement?, limit: Int = 5000): String = (v as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= limit }?.content ?: fail()
    private fun id(v: JsonElement?): String = text(v, 100).also { if (!Regex("[A-Za-z0-9_-]{1,100}").matches(it) || it in setOf("__proto__", "constructor", "prototype")) fail() }
    private fun num(v: JsonElement?): Double = (v as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 1e7 } ?: fail()
    private fun array(v: JsonElement?, max: Int): JsonArray = (v as? JsonArray)?.also { if (it.size > max) fail() } ?: fail()
    private fun optional(o: JsonObject, key: String, f: (JsonElement) -> Unit) { o[key]?.let(f) }
    fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size <= MAX_BYTES) { "System Flow exceeds the 32 MB limit." }
        return validate(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)))
    }
    fun validate(value: JsonElement): JsonObject {
        val flow = value as? JsonObject ?: fail()
        if (flow.toString().toByteArray().size > MAX_BYTES) fail()
        val schema = (flow["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail()
        if (schema == 1) { diagram(flow, true); return flow }
        if (schema !in 2..3) fail()
        obj(flow, setOf("schemaVersion", "title", "rootDiagramId", "diagrams") + if (schema == 3) setOf("whiteboards", "scenes") else emptySet())
        optional(flow, "title") { text(it, 200) }
        val diagrams = array(flow["diagrams"], 100).map { it as? JsonObject ?: fail() }
        if (diagrams.isEmpty()) fail()
        val diagramIds = diagrams.map { id(it["id"]) }.toSet()
        if (diagramIds.size != diagrams.size || id(flow["rootDiagramId"]) !in diagramIds) fail()
        val boardIds = mutableSetOf<String>(); val sceneIds = mutableSetOf<String>()
        if (schema == 3) {
            for (b in array(flow["whiteboards"], 100)) {
                val board = obj(b, setOf("id", "title", "sceneId", "currentSceneId", "proposedSceneId"))
                if (!boardIds.add(id(board["id"]))) fail()
                text(board["title"], 200)
                if (board["sceneId"] == null && (board["currentSceneId"] == null || board["proposedSceneId"] == null)) fail()
                if ((board["currentSceneId"] == null) != (board["proposedSceneId"] == null)) fail()
                for (key in listOf("sceneId", "currentSceneId", "proposedSceneId")) board[key]?.let { if (!sceneIds.add(id(it))) fail() }
            }
            // Editable snapshots must contain every scene, even though persisted legacy indexes may omit them.
            val scenes = flow["scenes"] as? JsonObject ?: fail()
            if (scenes.keys != sceneIds) fail()
            scenes.values.forEach(::scene)
        }
        if (diagramIds.any { it in boardIds || it in sceneIds }) fail()
        var nodeCount = 0; var edgeCount = 0
        for (d in diagrams) {
            obj(d, setOf("id", "title", "nodes", "edges", "viewport")); text(d["title"], 200)
            val clean = array(d["nodes"], 500).map { n ->
                val node = n as? JsonObject ?: fail()
                val data = obj(node["data"], setOf("kind", "label", "description", "owner", "detailFlowId", "whiteboardId"))
                data["detailFlowId"]?.let { if (id(it) !in diagramIds) fail() }
                data["whiteboardId"]?.let { if (id(it) !in boardIds || data["kind"] != JsonPrimitive("whiteboard")) fail() }
                if (data["kind"] == JsonPrimitive("whiteboard") && data["whiteboardId"] == null) fail()
                JsonObject(node + ("data" to JsonObject(data - setOf("detailFlowId", "whiteboardId"))))
            }
            val single = JsonObject((d - "id") + ("schemaVersion" to JsonPrimitive(1)) + ("nodes" to JsonArray(clean)))
            diagram(single, true); nodeCount += clean.size; edgeCount += (d["edges"] as JsonArray).size
        }
        if (nodeCount > 2000 || edgeCount > 6000 || JsonObject(flow - "scenes").toString().length > 2_000_000) fail()
        return flow
    }
    private fun diagram(d: JsonObject, root: Boolean) {
        obj(d, setOf("schemaVersion", "title", "nodes", "edges", "viewport"))
        if (root && d["schemaVersion"] != JsonPrimitive(1)) fail()
        optional(d, "title") { text(it, 200) }
        val nodes = array(d["nodes"], 500).map { obj(it, setOf("id", "type", "position", "data", "parentId", "width", "height")) }
        val ids = nodes.map { id(it["id"]) }; if (ids.toSet().size != ids.size) fail()
        val byId = nodes.associateBy { id(it["id"]) }
        for (n in nodes) {
            if (n["type"] != JsonPrimitive("flow")) fail()
            val pos = obj(n["position"], setOf("x", "y")); num(pos["x"]); num(pos["y"])
            val data = obj(n["data"], setOf("kind", "label", "description", "owner"))
            if (text(data["kind"]) !in kinds) fail()
            text(data["label"], 200); optional(data, "description") { text(it) }; optional(data, "owner") { text(it, 200) }
            for (key in listOf("width", "height")) optional(n, key) { if (num(it) !in 100.0..10000.0 || data["kind"] != JsonPrimitive("system")) fail() }
            val seen = mutableSetOf(id(n["id"])); var parent = n["parentId"]
            while (parent != null) {
                val p = id(parent); val ancestor = byId[p] ?: fail()
                if (!seen.add(p) || (ancestor["data"] as JsonObject)["kind"] != JsonPrimitive("system")) fail()
                parent = ancestor["parentId"]
            }
        }
        val edgeIds = mutableSetOf<String>()
        for (raw in array(d["edges"], 1500)) {
            val e = obj(raw, setOf("id", "source", "target", "sourceHandle", "targetHandle", "type", "label", "markerEnd", "style", "labelStyle", "labelBgStyle", "relationship"))
            if (!edgeIds.add(id(e["id"])) || text(e["source"]) !in ids || text(e["target"]) !in ids) fail()
            optional(e, "label") { text(it, 500) }; optional(e, "type") { if (it != JsonPrimitive("smoothstep")) fail() }
            optional(e, "relationship") {
                val r = text(it); if (r !in listOf("flow", "design-detail")) fail()
                if (r == "design-detail" && listOf(e["source"], e["target"]).none { ref -> (byId[text(ref)]?.get("data") as? JsonObject)?.get("kind") == JsonPrimitive("whiteboard") }) fail()
            }
            for (key in listOf("sourceHandle", "targetHandle")) optional(e, key) { if (it != JsonNull && !Regex("${if (key == "sourceHandle") "s" else "t"}-(left|right|top|bottom)").matches(text(it))) fail() }
            optional(e, "markerEnd") { if (obj(it, setOf("type"))["type"] != JsonPrimitive("arrowclosed")) fail() }
            for ((key, allowed) in listOf("style" to setOf("stroke", "strokeWidth"), "labelStyle" to setOf("fontSize"), "labelBgStyle" to setOf("fill"))) optional(e, key) {
                for ((k, v) in obj(it, allowed)) if (k in setOf("stroke", "fill")) { if (!Regex("(#[a-fA-F0-9]{3,8}|[a-zA-Z]{1,20})").matches(text(v, 30))) fail() } else if (num(v) !in 0.0..100.0) fail()
            }
        }
        optional(d, "viewport") { val v = obj(it, setOf("x", "y", "zoom")); num(v["x"]); num(v["y"]); if (num(v["zoom"]) !in .05..10.0) fail() }
    }
    private fun scene(raw: JsonElement) {
        val s = obj(raw, setOf("elements", "appState", "files"))
        if (s.toString().length > 10_000_000) fail()
        val state = obj(s["appState"], setOf("viewBackgroundColor", "scrollX", "scrollY", "zoom"))
        optional(state, "viewBackgroundColor") { if (!Regex("(#[a-fA-F0-9]{3,8}|[a-zA-Z]{1,20})").matches(text(it))) fail() }
        for (k in listOf("scrollX", "scrollY")) optional(state, k) { num(it) }
        optional(state, "zoom") { if (num(obj(it, setOf("value"))["value"]) !in .05..30.0) fail() }
        val files = s["files"] as? JsonObject ?: fail(); if (files.size > 200) fail()
        for ((key, value) in files) {
            id(JsonPrimitive(key)); val f = value as? JsonObject ?: fail()
            if (f["id"] != JsonPrimitive(key)) fail()
            val mime = text(f["mimeType"])
            if (mime !in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) fail()
            if (!Regex("data:${Regex.escape(mime)};base64,[A-Za-z0-9+/]+={0,2}").matches(text(f["dataURL"], 8_000_000))) fail()
        }
        val ids = mutableSetOf<String>()
        for (rawElement in array(s["elements"], 5000)) {
            val e = rawElement as? JsonObject ?: fail(); if (!ids.add(id(e["id"]))) fail()
            val type = text(e["type"]); if (type !in listOf("rectangle", "diamond", "ellipse", "text", "arrow", "line", "freedraw", "image", "frame") || e["link"]?.let { it != JsonNull && it != JsonPrimitive("") } == true) fail()
            for (k in listOf("x", "y", "width", "height")) num(e[k])
            if (type == "image" && e["isDeleted"] != JsonPrimitive(true) && id(e["fileId"]) !in files) fail()
        }
        fun inspect(v: JsonElement, depth: Int) {
            if (depth > 20) fail()
            when (v) {
                is JsonObject -> v.forEach { (k, x) -> if (k in setOf("__proto__", "constructor", "prototype")) fail(); inspect(x, depth + 1) }
                is JsonArray -> v.forEach { inspect(it, depth + 1) }
                else -> Unit
            }
        }
        inspect(s, 0)
    }
    fun diagrams(flow: JsonObject): List<JsonObject> = if (flow["schemaVersion"] == JsonPrimitive(1)) listOf(flow) else (flow["diagrams"] as JsonArray).map { it.jsonObject }
    fun items(flow: JsonObject, diagram: Int, key: String): List<JsonObject> = (diagrams(flow)[diagram][key] as JsonArray).map { it.jsonObject }
    fun updateItems(flow: JsonObject, diagram: Int, key: String, items: List<JsonObject>): JsonObject {
        val d = JsonObject(diagrams(flow)[diagram] + (key to JsonArray(items)))
        return validate(if (flow["schemaVersion"] == JsonPrimitive(1)) d else JsonObject(flow + ("diagrams" to JsonArray(diagrams(flow).mapIndexed { i, old -> if (i == diagram) d else old }))))
    }
    fun node(id: String, kind: String, label: String, x: Double = 0.0): JsonObject = buildJsonObject {
        put("id", id); put("type", "flow"); put("position", buildJsonObject { put("x", x); put("y", 0) })
        put("data", buildJsonObject { put("kind", kind); put("label", label); put("owner", "") })
    }
    fun edge(id: String, source: String, target: String, label: String = ""): JsonObject = buildJsonObject {
        put("id", id); put("source", source); put("target", target); put("type", "smoothstep"); put("relationship", "flow"); put("label", label)
    }
}
