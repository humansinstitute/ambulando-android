package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.core.surfaces.*

@RunWith(RobolectricTestRunner::class)
class HtmlArtefactCommentsSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun displaysOriginVersionDetachedTargetAndPermissionsAndSubmitsComment() {
        val v1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
        val v2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
        val detachedRoot = FileCommentRecord(
            id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1",
            workspaceId = "11111111-1111-1111-1111-111111111111",
            fileId = "22222222-2222-2222-2222-222222222222",
            fileVersionId = v1,
            fileVersionNumber = 1,
            parentCommentId = null,
            body = "Old banner was misaligned",
            commentStatus = "open",
            anchor = HtmlCommentAnchor(kind = "html_element", elementId = "old-banner", selector = "#old-banner", tagName = "div", textQuote = "Banner v1", fileVersionId = v1, fileVersionNumber = 1),
            createdByActorId = "33333333-3333-3333-3333-333333333333",
            createdByActorNpub = "npub1alice00000000000000000000000000000000000000000000000000000",
            updatedByActorId = "33333333-3333-3333-3333-333333333333",
            updatedByActorNpub = "npub1alice00000000000000000000000000000000000000000000000000000",
            rowVersion = 1,
            createdAt = "2026-11-25T00:00:00Z",
            updatedAt = "2026-11-25T00:00:00Z",
            canEdit = true,
            canResolve = true,
            canDelete = true,
        )
        val threads = listOf(
            FileCommentThread(
                root = detachedRoot,
                replies = emptyList(),
                originVersionId = v1,
                originVersionNumber = 1,
                fromEarlierVersion = true,
            )
        )
        var postedBody: String? = null
        var jumpedVersion: String? = null
        val pending = HtmlCommentAnchor(kind = "html_element", elementId = "hero", selector = "#hero", tagName = "h1", textQuote = "Welcome v2", fileVersionId = v2, fileVersionNumber = 2)

        compose.setContent {
            HtmlArtefactCommentsSheetContent(
                viewedVersionId = v2,
                viewedVersionNumber = 2,
                isViewingLatest = true,
                threads = threads,
                permissions = FileCommentPermissions(canRead = true, canComment = true, canResolveAny = false, canEditSource = false),
                anchorMatches = mapOf(detachedRoot.id to false),
                pendingTarget = pending,
                focusedThreadId = null,
                showEarlierUnresolved = true,
                onToggleShowEarlier = {},
                drafts = emptyMap(),
                busy = false,
                error = null,
                authorLabel = { "Alice" },
                onClearPendingTarget = {},
                onDraftChange = { _, _, _, _ -> },
                onSubmitRoot = { _, body, _ -> postedBody = body },
                onSubmitReply = { _, _, _ -> },
                onToggleResolve = {},
                onEditComment = { _, _ -> },
                onDeleteComment = {},
                onJumpToVersion = { jumpedVersion = it },
                onFocusTarget = {},
                onRefresh = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("artefacts.comments.title").assertTextContains("Comments · v2", substring = true)
        compose.onNodeWithTag("artefacts.comments.permissions").assertTextContains("Comment access", substring = true)
        compose.onNodeWithTag("artefacts.comments.rootInput").performTextInput("Looks sharper in v2")
        compose.onNodeWithTag("artefacts.comments.postRoot").performClick()
        assertEquals("Looks sharper in v2", postedBody)
        compose.onNodeWithTag("artefacts.thread.detached.${detachedRoot.id}").assertExists()
        compose.onNodeWithTag("artefacts.thread.origin.${detachedRoot.id}").performScrollTo().performClick()
        assertEquals(v1, jumpedVersion)
    }
}
