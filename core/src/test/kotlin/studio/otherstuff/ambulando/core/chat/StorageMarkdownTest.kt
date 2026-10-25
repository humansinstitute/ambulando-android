package studio.otherstuff.ambulando.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.docs.TextRange
import studio.otherstuff.ambulando.core.transport.MessageAttachment
import java.time.Instant

/** Storage Markdown and upload placeholders, matching the web helpers. */
class StorageMarkdownTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val fileId = "00000000-0000-4000-8000-000000000002"

    @Test fun storageMarkdownAndPlaceholdersMatchTheWebHelpers() {
        assertEquals("![shot 1](storage://$id)", StorageMarkdown.image(id, " shot [1] "))
        assertEquals("![Image](storage://$id)", StorageMarkdown.image(id, "[]"))
        assertEquals("""[a\\b \[v2\].pdf](storage://$id)""", StorageMarkdown.file(id, """a\b [v2].pdf"""))
        assertEquals("[Uploaded file](storage://$id)", StorageMarkdown.file(id, " "))

        val body = "see ![Board](storage://$id) and [notes.txt](storage://$fileId) again ![x](storage://$id)"
        val refs = StorageMarkdown.references(body)
        assertEquals(listOf("image", "file"), refs.map { it.kind })
        assertEquals(listOf("Board", "notes.txt"), refs.map { it.filename })
        val picked = MessageAttachment("image", "00000000-0000-4000-8000-000000000003", "photo.png", "image/png", 3)
        assertEquals(listOf(picked.storageObjectId, id, fileId), StorageMarkdown.merged(listOf(picked), body).map { it.storageObjectId })
        assertEquals("linked objects are not shown twice", listOf(picked), StorageMarkdown.standalone(StorageMarkdown.merged(listOf(picked), body), body))
        // Escaped labels written by `file` read back unescaped.
        assertEquals("""a\b [v2].pdf""", StorageMarkdown.references(StorageMarkdown.file(fileId, """a\b [v2].pdf""")).single().filename)

        val token = UploadToken.make(UploadToken.Kind.IMAGE, "abc-1")
        assertEquals("[ Uploading image abc-1... ]", token)
        val smartToken = token.replace("...", "…")
        assertTrue(UploadToken.containsAny(smartToken))
        assertTrue(UploadToken.containsToken(token, smartToken))
        assertEquals("abc-1", UploadToken.ids(smartToken).first().id)
        assertEquals("uploaded", ComposerText.replacing(token, "uploaded", smartToken, null)?.text)
        assertTrue(UploadToken.containsAny("x $token y"))
        assertTrue("the web's numbered form", UploadToken.containsAny("[ Uploading image 3... ]"))
        assertTrue("the web's plain form", UploadToken.containsAny("[ Uploading file... ]"))
        assertFalse(UploadToken.containsAny("[ Uploading image soon ]"))
        assertEquals(listOf("abc-1", "f2"), UploadToken.ids("$token ${UploadToken.make(UploadToken.Kind.FILE, "f2")}").map { it.id })
        assertEquals(UploadToken.Kind.FILE, UploadToken.ids(UploadToken.make(UploadToken.Kind.FILE, "f2")).single().kind)

        // The caret keeps its place around a replaced placeholder.
        val text = "Hi $token there"
        val after = TextRange(text.length, 0)
        val replaced = ComposerText.replacing(token, "![a](storage://1)", text, after)
        assertEquals("Hi ![a](storage://1) there", replaced?.text)
        assertEquals(TextRange("Hi ![a](storage://1) there".length, 0), replaced?.selection)
        assertEquals(TextRange(1, 0), ComposerText.replacing(token, "", text, TextRange(1, 0))?.selection)
        assertEquals("inside the token the caret moves to the end of the replacement",
            TextRange(5, 0), ComposerText.replacing(token, "XX", text, TextRange(6, 0))?.selection)
        assertNull(ComposerText.replacing(token, "x", "no placeholder", null))
        assertEquals(TextRange(11, 0), ComposerText.remap(TextRange(6, 0), "Hi @Me", "Hi @Member "))
        assertEquals(TextRange(4, 0), ComposerText.remap(TextRange(2, 0), "Hi", "Hi @"))
        assertEquals(TextRange(1, 0), ComposerText.remap(TextRange(1, 0), "Hi @Me", "Hi @Member "))
    }

    @Test fun pastedItemNamesAndResultMarkdown() {
        assertEquals("message-image-2026-01-02T03-04-05.png", ComposerUploadText.imageName("message", Instant.parse("2026-01-02T03:04:05.678Z")))
        assertEquals("report.pdf", ComposerUploadText.fileName(" report.pdf ", "pdf"))
        assertEquals("report.pdf", ComposerUploadText.fileName("report", "pdf"))
        assertEquals("Attachment.bin", ComposerUploadText.fileName(null, "bin"))
        assertEquals("Attachment", ComposerUploadText.fileName("", null))
        assertEquals("![photo.png](storage://$id)", ComposerUploadText.markdown(UploadToken.Kind.IMAGE, id, "photo.png"))
        assertEquals("[notes \\[1\\].txt](storage://$id)", ComposerUploadText.markdown(UploadToken.Kind.FILE, id, "notes [1].txt"))
        assertEquals("Wait for pasted items to finish uploading, or remove them.", ComposerUploadException(ComposerUploadError.UNRESOLVED).message)
    }
}
