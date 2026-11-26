package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.transport.*
import java.io.File

class FileCommentsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun comment(
        id: String,
        versionId: String,
        versionNumber: Int,
        parentId: String? = null,
        status: String = "open",
        body: String = "Note",
    ) = FileCommentRecord(
        id = id,
        workspaceId = "11111111-1111-1111-1111-111111111111",
        fileId = "22222222-2222-2222-2222-222222222222",
        fileVersionId = versionId,
        fileVersionNumber = versionNumber,
        parentCommentId = parentId,
        body = body,
        commentStatus = status,
        anchor = HtmlCommentAnchor(
            kind = "html_element",
            elementId = "hero",
            selector = "#hero",
            domPath = listOf(0),
            tagName = "h1",
            textQuote = "Headline",
            fileVersionId = versionId,
            fileVersionNumber = versionNumber,
        ),
        createdByActorId = "33333333-3333-3333-3333-333333333333",
        createdByActorNpub = "npub1reviewer000000000000000000000000000000000000000000000000000",
        updatedByActorId = "33333333-3333-3333-3333-333333333333",
        updatedByActorNpub = "npub1reviewer000000000000000000000000000000000000000000000000000",
        rowVersion = 1,
        createdAt = "2026-11-25T00:00:00Z",
        updatedAt = "2026-11-25T00:00:00Z",
        canEdit = true,
        canResolve = true,
        canDelete = true,
    )

    @Test
    fun groupsVersionScopedAndUnresolvedEarlierThreadsOnLatest() {
        val v1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
        val v2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
        val r1Open = comment("c1", v1, 1, status = "open", body = "Fix hero")
        val r1Reply = comment("c1r", v1, 1, parentId = "c1", body = "On it")
        val r1Resolved = comment("c2", v1, 1, status = "resolved", body = "Old typo")
        val r2Open = comment("c3", v2, 2, status = "open", body = "Check padding")
        val all = listOf(r1Open, r1Reply, r1Resolved, r2Open)

        // Viewing v1 shows v1 threads (both open and resolved) with fromEarlierVersion=false.
        val onV1 = groupFileCommentThreads(all, viewedVersionId = v1, isViewingLatest = false)
        assertEquals(listOf("c1", "c2"), onV1.map { it.root.id })
        assertEquals(1, onV1.first().replies.size)
        assertFalse(onV1.first().fromEarlierVersion)

        // Viewing latest (v2) includes v2 threads PLUS unresolved v1 threads with origin label; excludes resolved v1 threads.
        val onV2 = groupFileCommentThreads(all, viewedVersionId = v2, isViewingLatest = true, includeEarlierUnresolved = true)
        assertEquals(listOf("c1", "c3"), onV2.map { it.root.id })
        assertTrue(onV2.first().fromEarlierVersion)
        assertEquals(1, onV2.first().originVersionNumber)
        assertEquals(v1, onV2.first().originVersionId)
        assertFalse(onV2.last().fromEarlierVersion)

        // Toggling off earlier unresolved hides v1 threads on v2.
        val onV2Only = groupFileCommentThreads(all, viewedVersionId = v2, isViewingLatest = true, includeEarlierUnresolved = false)
        assertEquals(listOf("c3"), onV2Only.map { it.root.id })
    }

    @Test
    fun draftStorePreservesAndClearsDraftsAtomically() = runBlocking {
        val store = FileCommentDraftStore(File(temp.root, "file-comments-drafts.json"))
        val anchor = HtmlCommentAnchor(kind = "html_element", elementId = "cta", selector = "#cta", tagName = "button", textQuote = "Start")
        store.save(FileCommentDraftStore.Draft(key = "root:#cta", body = "Make button higher contrast", clientRequestId = "req-1", fileVersionId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", anchor = anchor))
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("Make button higher contrast", loaded["root:#cta"]?.body)
        assertEquals("req-1", loaded["root:#cta"]?.clientRequestId)
        assertEquals("#cta", loaded["root:#cta"]?.anchor?.selector)

        store.remove("root:#cta")
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun draftKeysAreIsolatedByVersionAndTarget() {
        val anchor = HtmlCommentAnchor(kind = "html_element", elementId = "hero", selector = "#hero")
        val k1 = anchor.draftKey("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1")
        val k2 = anchor.draftKey("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2")
        assertNotEquals(k1, k2)
        assertTrue(k1.contains("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"))
        assertTrue(k2.contains("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"))
    }

    @Test
    fun groupFileCommentThreadsHandlesOver200RecordsAcrossVersions() {
        val v1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
        val v2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
        val rows = buildList {
            for (i in 1..210) {
                val rootId = String.format("00000000-0000-0000-0000-%012d", i)
                add(comment(rootId, if (i <= 5) v1 else v2, if (i <= 5) 1 else 2, status = "open", body = "Root $i"))
            }
            // Late reply after 210th record referencing root #1 on v1.
            add(comment("00000000-0000-0000-0000-000000009999", v1, 1, parentId = "00000000-0000-0000-0000-000000000001", body = "Late reply"))
        }
        val threads = groupFileCommentThreads(rows, viewedVersionId = v2, isViewingLatest = true, includeEarlierUnresolved = true)
        assertEquals(210, threads.size)
        val first = threads.first { it.root.id == "00000000-0000-0000-0000-000000000001" }
        assertEquals(1, first.replies.size)
        assertEquals("Late reply", first.replies.first().body)
        assertTrue(first.fromEarlierVersion)
    }

    @Test
    fun allFileCommentsPaginatesBeyond200AndRejectsBindingMismatch() = runBlocking {
        val ws = "11111111-1111-1111-1111-111111111111"
        val fileId = "22222222-2222-2222-2222-222222222222"
        val v1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
        val actor = "33333333-3333-3333-3333-333333333333"
        fun rowJson(i: Int, wOverride: String = ws) = buildJsonObject {
            put("id", String.format("00000000-0000-0000-0000-%012d", i))
            put("workspace_id", wOverride)
            put("file_id", fileId)
            put("file_version_id", v1)
            put("file_version_number", 1)
            put("body", "Comment $i")
            put("comment_status", "open")
            put("created_by_actor_id", actor)
            put("updated_by_actor_id", actor)
            put("row_version", 1)
            put("created_at", "2026-11-25T00:00:00Z")
            put("updated_at", "2026-11-25T00:00:00Z")
        }
        var calls = 0
        val pagedTransport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                calls++
                val hasCursor = request.url.contains("cursor=page2")
                val range = if (!hasCursor) 1..200 else 201..225
                val payload = buildJsonObject {
                    put("file_id", fileId)
                    put("current_version_id", v1)
                    put("permissions", buildJsonObject { put("can_read", true); put("can_comment", true) })
                    put("comments", buildJsonArray { range.forEach { add(rowJson(it)) } })
                    if (!hasCursor) { put("next_cursor", "page2"); put("has_more", true) } else { put("has_more", false) }
                }
                return HttpResponse(200, payload.toString().toByteArray(Charsets.UTF_8))
            }
        }
        val testSigner = EventSigner { t ->
            NostrEvent("0".repeat(64), "1".repeat(64), t.createdAt, t.kind, t.tags, t.content, "2".repeat(128))
        }
        val api = TowerAPI(TowerClient(TowerOrigin("https://tower.example"), pagedTransport, signer = testSigner))
        val all = api.allFileComments(ws, "npub1app", fileId)
        assertEquals(2, calls)
        assertEquals(225, all.comments.size)
        assertFalse(all.hasMore)

        // Binding mismatch on workspace_id must fail closed with TowerError.Malformed.
        val mismatchTransport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val payload = buildJsonObject {
                    put("file_id", fileId)
                    put("permissions", buildJsonObject { put("can_read", true) })
                    put("comments", buildJsonArray { add(rowJson(1, wOverride = "99999999-9999-9999-9999-999999999999")) })
                    put("has_more", false)
                }
                return HttpResponse(200, payload.toString().toByteArray(Charsets.UTF_8))
            }
        }
        val badApi = TowerAPI(TowerClient(TowerOrigin("https://tower.example"), mismatchTransport, signer = testSigner))
        assertTrue(runCatching { badApi.allFileComments(ws, "npub1app", fileId) }.exceptionOrNull() is TowerError.Malformed)
    }
}
