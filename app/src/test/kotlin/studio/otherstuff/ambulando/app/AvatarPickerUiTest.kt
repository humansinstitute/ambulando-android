package studio.otherstuff.ambulando.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.management.WorkspaceAdministrator
import studio.otherstuff.ambulando.core.media.PngChunks
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.sample.SampleViewer
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.toJsonElement
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32

/**
 * The workspace-avatar file picker, driven through the real Profile screen with a stand-in system picker (a synthetic image file
 * chosen from the activity-result registry) and the synthetic administration Tower: consent first, metadata stripped, bounds,
 * cancel at either step, and the address landing in the form without saving the workspace. Nothing here is evidence of a live Tower.
 */
@RunWith(RobolectricTestRunner::class)
class AvatarPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private class Memory : VaultSecretStore {
        private var value: ByteArray? = null
        @Synchronized override fun read() = value?.copyOf()
        @Synchronized override fun write(data: ByteArray) { value = data.copyOf() }
    }

    private val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = SyncEngine(maxConcurrent = 4, maxPerGroup = 3)
    private lateinit var dir: File
    private lateinit var tower: SampleTower
    private lateinit var session: AdministrationSession
    private val sent = java.util.Collections.synchronizedList(ArrayList<HttpRequest>())
    /** What the stand-in system picker returns next (null: the person backed out). */
    @Volatile private var picked: Uri? = null
    private var launches = 0

    private fun storageRequests() = sent.filter { it.url.contains("/api/v4/storage") || it.url.contains("/bucket/") }

    private fun setUp() {
        dir = Files.createTempDirectory("avatar-ui").toFile()
        tower = SampleTower(File(dir, "tower"), originUrl = "https://one.sample.invalid").also { it.prepare(npub); it.administration.viewer = SampleViewer.ADMIN }
        val identity = runBlocking { IdentityService(File(dir, "vault.json"), Memory()).also { it.importIdentity("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5", "246810") } }
        val connection = ConnectionRecord.create(TowerOrigin("https://one.sample.invalid"), npub, null)
        val workspace = WorkspaceRecord.parse(toJsonElement(mapOf(
            "identity" to mapOf("workspace_id" to SampleIds.STUDIO, "app_npub" to SampleIds.APP, "workspace_owner_npub" to SampleIds.ANA),
            "label" to "Sample Studio", "slug" to "sample-studio", "tower_base_url" to "https://one.sample.invalid", "membership" to mapOf("role" to "member"),
        )) as kotlinx.serialization.json.JsonObject)
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse { sent += request; return tower.send(request) }
        }
        val boundary = WorkspaceBoundary.of(connection, workspace)
        val admin = WorkspaceAdministrator(boundary, connection, workspace, LocalStore(File(dir, "store")), identity, http, http, engine)
        session = AdministrationSession(admin, workspace.label, { boundary.id }, scope)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launches++
                dispatchResult(requestCode, picked)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry: ActivityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AmbulandoTheme {
                    AdministrationHost(
                        session, WorkspaceRecord(SampleIds.STUDIO, SampleIds.APP, "Sample Studio", "sample-studio", null, "member", SampleIds.ANA, kotlinx.serialization.json.JsonObject(emptyMap())),
                        emptyList(), emptyList(), { _, _, _ -> Text("scopes stub") }, done = {}, initial = AdminRoute.Profile, pollMillis = 0,
                    )
                }
            }
        }
        compose.waitUntil(20_000) { session.canAdminister }
        compose.waitUntil(20_000) { runCatching { tag("admin.avatar.choose").assertIsEnabled() }.isSuccess }
    }

    @After fun tearDown() { scope.cancel(); engine.close() }

    private fun tag(tag: String) = compose.onNodeWithTag(tag)
    private fun click(tag: String) { val node = tag(tag); runCatching { node.performScrollTo() }; node.performClick() }
    private fun waitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun goneTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    /** A real PNG with a text chunk that stands in for camera, GPS and time metadata. */
    private fun imageWithMetadata(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF2266AA.toInt()) }
        val plain = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val text = "Comment\u0000GPS 51.5007N 0.1246W".toByteArray(Charsets.ISO_8859_1)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().also { it.update(type); it.update(text) }.value
        fun int(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val chunk = int(text.size.toLong()) + type + text + int(crc)
        val afterHeader = 8 + 12 + 13
        return plain.copyOfRange(0, afterHeader) + chunk + plain.copyOfRange(afterHeader, plain.size)
    }

    private fun file(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(dir, name).also { it.writeBytes(bytes) })

    private fun avatarField(): String = tag("admin.field.avatar").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun aChosenImageNeedsPublicConsentIsMetadataStrippedAndOnlyFillsTheFormUntilSaved() {
        setUp()
        val original = imageWithMetadata()
        assertTrue("the fixture carries metadata", "tEXt" in PngChunks.chunkTypes(original))
        picked = file("photo.png", original)
        click("admin.avatar.choose")
        waitTag("admin.avatar.cancel")
        assertEquals("nothing is prepared or uploaded before consent", 0, storageRequests().size)
        click("admin.avatar.cancel")
        assertTrue(goneTag("admin.avatar.confirm"))
        assertEquals("backing out sends nothing", 0, storageRequests().size)
        assertEquals("", avatarField())
        click("admin.avatar.choose")
        waitTag("admin.avatar.confirm"); click("admin.avatar.confirm")
        compose.waitUntil(20_000) { avatarField().startsWith("storage://") }
        val id = avatarField().removePrefix("storage://")
        val stored = assertNotNull(tower.work.completedObjectBytes(id)).let { tower.work.completedObjectBytes(id)!! }
        val types = PngChunks.chunkTypes(stored)
        assertFalse("metadata is gone: $types", "tEXt" in types)
        assertTrue("pixels remain: $types", "IDAT" in types)
        assertTrue("prepared as a public object", storageRequests().any { it.url.endsWith("/api/v4/storage/prepare") && it.method == "POST" })
        assertTrue("the workspace is not saved by an upload", tower.administration.writes().none { it.path.isEmpty() && it.method == "PATCH" })
    }

    @Test fun anImageOverTheLimitIsRefusedBeforeAnyConsentOrRequest() {
        setUp()
        picked = file("big.bin", ByteArray(WorkspaceAdministrator.MAX_AVATAR_BYTES + 1))
        click("admin.avatar.choose")
        waitTag("admin.avatar.problem")
        tag("admin.avatar.problem").assertTextContains("smaller than 8 MB", substring = true)
        assertTrue(goneTag("admin.avatar.confirm"))
        assertEquals(0, storageRequests().size)
    }

    @Test fun backingOutOfThePickerChangesNothing() {
        setUp()
        picked = null
        click("admin.avatar.choose")
        compose.waitUntil(10_000) { launches == 1 }
        assertTrue(goneTag("admin.avatar.confirm")); assertTrue(goneTag("admin.avatar.problem"))
        assertEquals(0, storageRequests().size); assertEquals("", avatarField())
    }
}
