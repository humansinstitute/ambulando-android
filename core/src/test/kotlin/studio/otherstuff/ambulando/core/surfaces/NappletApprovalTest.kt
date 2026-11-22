package studio.otherstuff.ambulando.core.surfaces

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NappletApprovalTest {
    private val code = "10000000-0000-4000-8000-000000000001"
    private val data = "10000000-0000-4000-8000-000000000002"
    private val workspace = "10000000-0000-4000-8000-000000000003"
    private val manifest = Json.parseToJsonElement("""{"version":1,"app_id":"daily-tracker","bindings":{"tracker":{"format":"daily-tracker-v1","operations":["read","save"],"privacy":"self"}}}""").jsonObject
    private fun metadata(id: String) = buildJsonObject {
        put("html_artifact", true); put("artefact", ArtefactType.HTML.metadata().getValue("artefact"))
        put("napplet", Json.parseToJsonElement("""{"version":1,"app_id":"daily-tracker","bindings":{"tracker":{"file_id":"$id"}}}"""))
    }
    private fun receipt(origin: String = "https://example.com", connection: String = "connection", service: String? = "service", namespace: String = "android", workspace: String = this.workspace, app: String = "app", viewer: String = "viewer", code: String = this.code, html: String = "verified code", data: String = this.data, writable: Boolean = true, declaration: JsonObject = manifest) =
        NappletApproval.verified(origin, connection, service, namespace, workspace, app, viewer, code, html, metadata(data), declaration, data, writable)
    @Test fun explicitApprovalSurvivesStoreRecreationAndRevoke() {
        val directory = Files.createTempDirectory("approval").toFile()
        try {
            val first = NappletApprovalStore(directory); val approval = receipt()
            assertFalse(first.approved(approval)); assertTrue(first.remember(approval))
            val reopened = NappletApprovalStore(directory)
            assertTrue(reopened.approved(receipt()))
            assertTrue(reopened.forget(approval)); assertFalse(first.approved(approval))
        } finally { directory.deleteRecursively() }
    }
    @Test fun everyIdentityCodeBindingAndEffectiveAccessBoundaryInvalidates() {
        val base = receipt()
        listOf(receipt(origin = "https://other.example"), receipt(connection = "other"), receipt(service = null),
            receipt(namespace = "other"), receipt(workspace = data), receipt(app = "other"), receipt(viewer = "other"),
            receipt(code = data), receipt(html = "changed code"), receipt(data = code), receipt(writable = false)
        ).forEach { assertNotEquals(base.fingerprint, it.fingerprint) }
        assertEquals(base, receipt(origin = "https://EXAMPLE.com:443/"))
        // Neither code version nor tracker data/version enters this API: identical verified code reuses approval.
        assertEquals(base, receipt())
    }
    @Test fun changedAndMalformedCapabilityCannotProduceApproval() {
        listOf("save" to "delete", "self" to "workspace", "daily-tracker-v1" to "other").forEach { (a, b) ->
            val changed = Json.parseToJsonElement(manifest.toString().replace(a, b)).jsonObject
            assertTrue(runCatching { receipt(declaration = changed) }.isFailure)
        }
    }
    @Test fun malformedUnknownAndUnavailableStorageNeverAutoApproves() {
        val directory = Files.createTempDirectory("approval").toFile()
        try {
            val store = NappletApprovalStore(directory); val approval = receipt()
            val target = java.io.File(directory, approval.slot + ".json")
            listOf("garbage", "{}", """{"schema":2,"fingerprint":"${approval.fingerprint}"}""",
                """{"schema":1,"fingerprint":"${approval.fingerprint}","extra":true}""", "x".repeat(257)).forEach {
                target.writeText(it); assertFalse(store.approved(approval))
            }
            assertTrue(store.remember(approval)); assertTrue(store.approved(approval))
            val stored = target.readText()
            assertFalse(stored.contains("viewer")); assertFalse(stored.contains("verified code")); assertFalse(stored.contains(data))
            val blocked = java.io.File(directory, "blocked").also { it.writeText("not a directory") }
            assertFalse(NappletApprovalStore(blocked).approved(approval))
            assertFalse(NappletApprovalStore(blocked).remember(approval))
        } finally { directory.deleteRecursively() }
    }
}
