package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.docs.JsonValue
import studio.otherstuff.ambulando.core.docs.ProseMirrorMarkdown
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.profiles.ProfileRecord
import studio.otherstuff.ambulando.core.profiles.ProfileSource
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.toJsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Synthetic ids, people and content of sample-data mode. Every id is a placeholder UUID with the `5a00` ("sample")
 * prefix, every person is fictional (their npubs are repeated-byte placeholders, not keys anyone holds) and every host is
 * under `.invalid`. Labels say "sample" so screens visibly show sample data.
 */
object SampleIds {
    const val STUDIO = "5a000000-0000-4000-8000-000000000001"
    const val LAB = "5a000000-0000-4000-8000-000000000002"
    /** A workspace this identity no longer belongs to (never listed), for stale-choice checks. */
    const val REMOVED_WORKSPACE = "5a000000-0000-4000-8000-0000000000ff"
    const val SCOPE_AMBULANDO = "5a000000-0000-4000-8000-0000000000a1"
    const val SCOPE_OPS = "5a000000-0000-4000-8000-0000000000a2"
    const val SCOPE_RESEARCH = "5a000000-0000-4000-8000-0000000000a3"
    const val GENERAL = "5a000000-0000-4000-8000-0000000000c1"
    const val DESIGN = "5a000000-0000-4000-8000-0000000000c2"
    const val DEPLOYS = "5a000000-0000-4000-8000-0000000000c3"
    const val NOTES = "5a000000-0000-4000-8000-0000000000c4"
    const val ACTOR = "5a000000-0000-4000-8000-0000000000e1"
    const val CANVAS_DOC = "5a000000-0000-4000-8000-0000000000d7"
    const val CANVAS_TASK = "5a000000-0000-4000-8000-0000000000e7"
    const val BOARD_FILE = "5a000000-0000-4000-8000-0000000000f7"
    const val BOARD_VERSION = "5a000000-0000-4000-8000-0000000000f8"
    const val LOCKED_FILE = "5a000000-0000-4000-8000-0000000000f9"

    /** Seeded message `n` (`5a00aaaa-…`) and the thread it starts (`5a00bbbb-…`). */
    fun message(n: Int): String = String.format("5a00aaaa-0000-4000-8000-%012d", n)
    fun thread(n: Int): String = String.format("5a00bbbb-0000-4000-8000-%012d", n)

    private fun npub(byte: Int): String = Bech32.encode("npub", ByteArray(32) { byte.toByte() })

    /** A sample member with a workspace name. */
    val ANA: String = npub(0x5A)
    /** A sample agent. */
    val ORBIT: String = npub(0x5B)
    /** A second sample launcher agent. */
    val RELAY: String = npub(0x5C)
    /** A member without a workspace name: the app names her from her (sample) public profile. */
    val BEA: String = npub(0x5D)
    /** Someone who posted but is no longer listed and has no profile: shown by npub and initials. */
    val FORMER: String = npub(0x5E)
    /** The sample workspace app identity. */
    val APP: String = npub(0x5F)
}

/** Generates the throwaway identity sample-data mode signs with. Never a user's key. */
object SampleIdentity {
    /** The PIN the sample identity is sealed with (debug sample-data mode only). */
    const val PIN = "135790"

    /** A fresh random nsec (a valid secp256k1 scalar) for this device's sample identity. */
    fun generateNsec(random: SecureRandom = SecureRandom()): String {
        while (true) {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            if (NostrKeys.isValidSecret(bytes)) return Bech32.encode("nsec", bytes).also { bytes.fill(0) }
        }
    }
}

/** A sample public profile (synthetic people only; nothing to verify). */
data class SampleProfile(val npub: String, val displayName: String?, val name: String?, val picture: String?)

object SampleContent {
    /** The member names the sample workspace directory lists (`null`: named from the public profile). */
    const val ANA_LABEL = "Ana (sample)"
    const val ORBIT_LABEL = "Orbit (sample agent)"
    const val RELAY_LABEL = "Relay (sample agent)"
    const val YOU_LABEL = "You (sample identity)"

    /** Sample profiles; `broken.png` answers bytes that are not an image (the app keeps the initials). */
    fun profiles(me: String): List<SampleProfile> = listOf(
        SampleProfile(me, "Sample Explorer", "explorer", SampleHosts.AVATARS + "you.png"),
        SampleProfile(SampleIds.ANA, "Ana Lima (sample)", "ana", SampleHosts.AVATARS + "ana.png"),
        SampleProfile(SampleIds.BEA, "Bea Ortiz (sample)", "bea", SampleHosts.AVATARS + "bea.png"),
        SampleProfile(SampleIds.ORBIT, null, "orbit", SampleHosts.AVATARS + "broken.png"),
    )

    /** A [ProfileSource] serving [profiles] (sample-data mode only). [offline] makes it fail like an unreachable relay. */
    fun profileSource(me: () -> String, offline: () -> Boolean = { false }): ProfileSource = ProfileSource { pubkeys ->
        if (offline()) throw SampleOfflineException()
        val byNpub = profiles(me()).associateBy { it.npub }
        val now = Instant.now()
        pubkeys.mapNotNull { pubkey ->
            val npub = NostrKeys.npub(pubkey) ?: return@mapNotNull null
            val p = byNpub[npub] ?: return@mapNotNull null
            ProfileRecord(
                pubkey = pubkey.lowercase(), npub = npub, displayName = p.displayName, name = p.name, picture = p.picture, eventId = Sha256Hex.of(npub),
                eventCreatedAt = 1_792_800_000, status = ProfileRecord.Status.FOUND, checkedAt = now,
            )
        }
    }

    /** Avatar bytes for a sample avatar URL (a two-tone badge per name), or null for another host. */
    fun avatar(url: String): ByteArray? {
        if (!url.startsWith(SampleHosts.AVATARS)) return null
        val name = url.removePrefix(SampleHosts.AVATARS).substringBefore('.')
        if (name == "broken") return "not an image".toByteArray()
        val (dark, light) = when (name) {
            "you" -> 0x215C85 to 0xADD1F0
            "ana" -> 0xBF4D0D to 0xFFCC9E
            "bea" -> 0x26613D to 0xA3D1B5
            else -> 0x545454 to 0xABABAB
        }
        return SamplePNG.encode(64, 64) { x, y ->
            val head = (x - 32) * (x - 32) + (y - 20) * (y - 20) < 16 * 16
            val body = (x - 32) * (x - 32) / 2 + (y - 60) * (y - 60) < 26 * 26 / 2 + 200
            if (head || body) light else dark
        }
    }

    /** An agent-style reply with the Markdown the web renders: heading, list, code, table, link and a mention. */
    val RICH_MESSAGE: String = """
        ## Build checklist
        @[Ana](mention:person:${SampleIds.ANA}) the **native** pass is _ready_:

        1. Open the channel
        2. Check `./gradlew test`
           - nested item

        ```sh
        ./gradlew :app:assembleDebug
        ```

        | Surface | State |
        | --- | --- |
        | Chat | Markdown |
        | Docs | Images |

        > Notes from [the web app](https://example.invalid/notes)
    """.trimIndent()

    /** The sample PNG (two colour blocks), as stored for files and Markdown images. */
    fun samplePNG(): ByteArray = SamplePNG.encode(96, 64) { x, _ -> if (x < 48) 0x2B1C3D else 0xBF4D0D }

    /** A small Excalidraw scene: three boxes, arrows, text and one embeddable (listed as not drawn). */
    fun sampleBoard(): ByteArray {
        fun element(id: String, type: String, x: Int, y: Int, w: Int, h: Int, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>(
            "id" to id, "type" to type, "x" to x, "y" to y, "width" to w, "height" to h, "angle" to 0, "strokeColor" to "#1e1e1e",
            "backgroundColor" to "transparent", "fillStyle" to "solid", "strokeWidth" to 2, "strokeStyle" to "solid", "roughness" to 1, "opacity" to 100,
            "version" to 1, "isDeleted" to false,
        ) + extra
        val elements = listOf(
            element("tower", "rectangle", 0, 0, 180, 80, mapOf("backgroundColor" to "#d0bfff", "roundness" to mapOf("type" to 3))),
            element("tower-text", "text", 20, 28, 140, 25, mapOf("text" to "Tower", "fontSize" to 20, "textAlign" to "center", "containerId" to "tower")),
            element("sync", "rectangle", 260, 0, 180, 80, mapOf("backgroundColor" to "#a5d8ff", "fillStyle" to "hachure")),
            element("sync-text", "text", 280, 28, 140, 25, mapOf("text" to "Sync engine", "fontSize" to 20, "textAlign" to "center", "containerId" to "sync")),
            element("store", "ellipse", 260, 160, 180, 90, mapOf("backgroundColor" to "#b2f2bb")),
            element("store-text", "text", 280, 192, 140, 25, mapOf("text" to "SQLite", "fontSize" to 20, "textAlign" to "center", "containerId" to "store")),
            element("a1", "arrow", 180, 40, 80, 0, mapOf("points" to listOf(listOf(0, 0), listOf(80, 0)), "endArrowhead" to "arrow")),
            element("a2", "arrow", 350, 80, 0, 80, mapOf("points" to listOf(listOf(0, 0), listOf(0, 80)), "endArrowhead" to "triangle", "strokeStyle" to "dashed")),
            element("note", "text", 0, 120, 220, 50, mapOf("text" to "Screens read only\nfrom SQLite", "fontSize" to 16)),
            element("embed", "embeddable", 0, 200, 200, 100, mapOf("link" to "https://example.invalid/video")),
        )
        val scene = linkedMapOf(
            "type" to "excalidraw", "version" to 2, "source" to "wingman-ambulando", "elements" to elements, "appState" to emptyMap<String, Any>(),
            "files" to emptyMap<String, Any>(),
        )
        return jsonBody(toJsonElement(scene) as JsonObject)
    }

    /**
     * A web-format document (ProseMirror authoritative) with marks, a task list, a link, a table and a node this app does
     * not know, so the reader and editor are exercised on real structure.
     */
    fun richDocument(): ByteArray {
        fun str(s: String) = JsonValue.Str(s)
        fun t(s: String, vararg marks: String): JsonValue {
            val pairs = mutableListOf<Pair<String, JsonValue>>("type" to str("text"), "text" to str(s))
            if (marks.isNotEmpty()) pairs.add("marks" to JsonValue.Arr(marks.map { JsonValue.obj("type" to str(it)) }))
            return JsonValue.Obj(pairs)
        }
        fun block(type: String, id: String, content: List<JsonValue>, attrs: List<Pair<String, JsonValue>> = emptyList()): JsonValue =
            JsonValue.obj("type" to str(type), "attrs" to JsonValue.Obj(listOf("fdBlockId" to str(id)) + attrs), "content" to JsonValue.Arr(content))
        fun para(content: List<JsonValue>): JsonValue = JsonValue.obj("type" to str("paragraph"), "content" to JsonValue.Arr(content))
        val link = JsonValue.obj(
            "type" to str("text"), "text" to str("web inbox"),
            "marks" to JsonValue.Arr(listOf(JsonValue.obj("type" to str("link"), "attrs" to JsonValue.obj("href" to str("${SampleHosts.BROWSER}/document"))))),
        )
        fun task(checked: Boolean, label: String) = JsonValue.obj(
            "type" to str("taskItem"), "attrs" to JsonValue.obj("checked" to JsonValue.Bool(checked)), "content" to JsonValue.Arr(listOf(para(listOf(t(label))))),
        )
        fun cell(s: String) = JsonValue.obj("type" to str("tableCell"), "content" to JsonValue.Arr(listOf(para(listOf(t(s))))))
        fun row(vararg cells: String) = JsonValue.obj("type" to str("tableRow"), "content" to JsonValue.Arr(cells.map { cell(it) }))
        val state = JsonValue.obj(
            "type" to str("doc"),
            "content" to JsonValue.Arr(
                listOf(
                    block("heading", "pm_plan_h1", listOf(t("Native app plan (sample)")), listOf("level" to JsonValue.int(1))),
                    block("paragraph", "pm_plan_p1", listOf(t("Every screen reads from "), t("local storage", "bold"), t(", and sync runs "),
                        t("off the main thread", "italic"), t("."))),
                    block("paragraph", "pm_plan_p2", listOf(t("Compare with the "), link, t(" before release."))),
                    block("taskList", "pm_plan_tl", listOf(task(true, "Chat and outbox"), task(false, "Tasks, docs, files and inbox"))),
                    block("table", "pm_plan_tb", listOf(row("Surface", "State"), row("Docs", "Native"))),
                    JsonValue.obj("type" to str("fdCalloutWidget"), "attrs" to JsonValue.obj("fdBlockId" to str("pm_plan_w1"), "tone" to str("info"))),
                    block("paragraph", "pm_plan_p3", listOf(t("Edit this paragraph on the phone."))),
                ),
            ),
        )
        val model = JsonValue.Obj(ProseMirrorMarkdown.contentModelFields(state))
        return JsonValue.obj("format" to str(DocumentBody.ENVELOPE_FORMAT), "content_model" to model).serialized()
    }
}

/** Minimal truecolour PNG writer for sample images (no platform graphics in core). */
internal object SamplePNG {
    fun encode(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val rgb = pixel(x, y)
                raw.write((rgb shr 16) and 0xFF); raw.write((rgb shr 8) and 0xFF); raw.write(rgb and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        d.write(typeBytes)
        d.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        d.writeInt(crc.value.toInt())
    }
}
