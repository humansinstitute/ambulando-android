package studio.otherstuff.ambulando.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.DocRecord

/** Wiki references: how they resolve, how the `[[` picker searches, and how a chosen or created page is bound without guessing. */
class WikiResolutionTest {
    private val w1 = "00000000-0000-4000-8000-000000000001"
    private val chA = "00000000-0000-4000-8000-000000000c01"
    private val chB = "00000000-0000-4000-8000-000000000c02"
    private fun id(n: Int) = "00000000-0000-4000-8000-0000000000%02d".format(n)

    private fun doc(n: Int, title: String, channel: String = chA, archived: Boolean = false, scope: String? = null) = DocRecord(
        w1, id(n), scope, channel, "obj$n", title, null, archived, 1, 1, "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z",
    )

    private fun body(vararg paragraphs: String) = DocumentBody(Docs.rich(*paragraphs), DocumentBody.ENVELOPE_CONTENT_TYPE)

    // MARK: resolution

    @Test fun aBoundIdResolvesByIdAloneAcrossChannelsAndRenames() {
        val docs = listOf(doc(1, "Plan", chA), doc(2, "Plan (renamed)", chB))
        val moved = WikiLinks.resolve(WikiTarget(documentId = id(2), title = "Plan"), docs, sourceChannelId = chA)
        assertEquals("the id is the identity: a different channel and a new title still resolve", WikiResolution.Available(docs[1]), moved)
    }

    @Test fun anIdThatIsNotReadableIsNeverReplacedByATitleMatchOrANewPage() {
        val docs = listOf(doc(1, "Plan", chA))
        val gone = WikiLinks.resolve(WikiTarget(documentId = id(9), title = "Plan"), docs, sourceChannelId = chA)
        assertEquals(WikiResolution.Unavailable(id(9)), gone)
        val archived = WikiLinks.resolve(WikiTarget(documentId = id(3), title = "Plan"), listOf(doc(3, "Plan", chA, archived = true), docs[0]), chA)
        assertEquals("deleted or archived is unavailable, not the other page with the same title", WikiResolution.Unavailable(id(3)), archived)
    }

    @Test fun aTitleResolvesOnlyWithinTheSourceChannelAndAmbiguityIsNeverGuessed() {
        val docs = listOf(doc(1, "Roadmap", chA), doc(2, "roadmap ", chA), doc(3, "Standup", chA), doc(4, "Standup", chB))
        assertEquals(WikiResolution.Available(docs[2]), WikiLinks.resolve(WikiTarget(title = "STANDUP"), docs, chA))
        val ambiguous = WikiLinks.resolve(WikiTarget(title = "Roadmap"), docs, chA)
        assertTrue(ambiguous is WikiResolution.Ambiguous)
        assertEquals(setOf(id(1), id(2)), (ambiguous as WikiResolution.Ambiguous).matches.map { it.id }.toSet())
        assertEquals(WikiResolution.Unresolved("Missing"), WikiLinks.resolve(WikiTarget(title = "Missing"), docs, chA))
        assertEquals("a page in another channel is not matched by title", WikiResolution.Unresolved("Only elsewhere"),
            WikiLinks.resolve(WikiTarget(title = "Only elsewhere"), listOf(doc(5, "Only elsewhere", chB)), chA))
    }

    // MARK: picker search

    @Test fun searchIsLocalByDefaultListsSameTitlesSeparatelyAndNeverOffersTheSource() {
        val docs = listOf(doc(1, "Alpha plan", chA), doc(2, "Beta plan", chA), doc(3, "plan", chB), doc(4, "Plan", chA), doc(5, "Archived plan", chA, archived = true))
        val local = WikiLinks.search("plan", docs, chA, excluding = id(1), everywhere = false)
        assertEquals(listOf(id(4), id(2)), local.map { it.id })
        val everywhere = WikiLinks.search("plan", docs, chA, excluding = id(1), everywhere = true)
        assertEquals("this channel first, exact title before partial matches, other channels after", listOf(id(4), id(2), id(3)), everywhere.map { it.id })
        assertEquals(2, docs.count { WikiLinks.normalized(it.title) == "plan" })
        assertEquals("an empty query lists the channel's readable pages except the source", 2, WikiLinks.search("", docs, chA, excluding = id(1), everywhere = false).size)
        assertEquals(30, WikiLinks.search("", (1..60).map { doc(it, "Page $it") }, chA, everywhere = false).size)
    }

    // MARK: text

    @Test fun literalTitlesBecomeReferencesButCodeAndLinksStayText() {
        val md = "See [[Roadmap]] and [[ Roadmap ]] and `[[InCode]]` and [[Linked]](https://x.example) and [Bound](wiki:${id(7)}).\n\n```\n[[Fenced]]\n```\n\n    [[Indented]]\n"
        val refs = WikiLinks.references(DocumentBody(md.toByteArray(), "text/markdown"))
        assertEquals(listOf("Roadmap", "Roadmap", "Bound"), refs.map { it.title })
        assertEquals(listOf(null, null, id(7)), refs.map { it.documentId })
        assertEquals("the two same-title references are numbered in reading order", listOf(0, 1), refs.take(2).map { it.occurrence })
        assertEquals(listOf(2, 2), refs.take(2).map { it.count })
    }

    @Test fun richReferencesReadFromNodesAndTextAndNeverFromCodeBlocks() {
        val state = """{"type":"doc","content":[
            {"type":"paragraph","content":[{"type":"text","text":"a [[One]] "},{"type":"fdWikiLink","attrs":{"documentId":null,"title":"Two"}},{"type":"fdWikiLink","attrs":{"documentId":"${id(8)}","title":"Three"}}]},
            {"type":"codeBlock","content":[{"type":"text","text":"[[Four]]"}]}]}"""
        val bytes = """{"format":"document_content_v1","content_model":${JsonValue.Obj(ProseMirrorMarkdown.contentModelFields(JsonValue.parse(state))).serializedString()}}""".toByteArray()
        val refs = WikiLinks.references(DocumentBody(bytes, DocumentBody.ENVELOPE_CONTENT_TYPE))
        assertEquals(listOf("One" to null, "Two" to null, "Three" to id(8)), refs.map { it.title to it.documentId })
    }

    @Test fun retitledShowsTheCurrentNameButLeavesStoredTextAlone() {
        val blocks = DocumentRender.blocks(DocumentBody("[Old name](wiki:${id(1)})".toByteArray(), "text/markdown"))
        val shown = WikiLinks.retitled(blocks, listOf(doc(1, "New name")))
        assertEquals("New name", ((shown.single() as RenderBlock.Paragraph).runs.single()).text)
        assertEquals("Old name", ((blocks.single() as RenderBlock.Paragraph).runs.single()).text)
        val unknown = WikiLinks.retitled(blocks, emptyList())
        assertEquals("a page not saved here keeps the title it was written with", "Old name", ((unknown.single() as RenderBlock.Paragraph).runs.single()).text)
    }

    // MARK: binding

    @Test fun bindingChangesOnlyTheOpenedOccurrenceInRichText() {
        val state = body("[[Dash]] one [[Dash]] two [[Other]]").editorState!!
        val second = WikiLinks.binding("dash", id(1), state, occurrence = 1)!!
        val refs = WikiLinks.references(DocumentBody(bodyOf(second), DocumentBody.ENVELOPE_CONTENT_TYPE))
        assertEquals(listOf("Dash" to null, "Dash" to id(1), "Other" to null), refs.map { it.title to it.documentId })
        assertNull("nothing matches: nothing changes", WikiLinks.binding("Missing", id(1), state))
        val all = WikiLinks.binding("Dash", id(1), state)!!
        assertEquals(2, WikiLinks.references(DocumentBody(bodyOf(all), DocumentBody.ENVELOPE_CONTENT_TYPE)).count { it.documentId == id(1) })
    }

    private fun bodyOf(state: JsonValue): ByteArray =
        """{"format":"document_content_v1","content_model":${JsonValue.Obj(ProseMirrorMarkdown.contentModelFields(state)).serializedString()}}""".toByteArray()

    @Test fun markedTextAndCodeAreNotRewrittenWhenBinding() {
        val state = JsonValue.parse("""{"type":"doc","content":[
            {"type":"paragraph","content":[{"type":"text","text":"[[Dash]]","marks":[{"type":"code"}]},{"type":"text","text":" [[Dash]]","marks":[{"type":"bold"}]}]},
            {"type":"codeBlock","content":[{"type":"text","text":"[[Dash]]"}]}]}""")
        val bound = WikiLinks.binding("Dash", id(1), state)!!
        val s = bound.serializedString()
        assertEquals("only the bold literal is bound; the code mark and the code block keep their brackets", 1, Regex("fdWikiLink").findAll(s).count())
        assertTrue(s.contains("\"text\":\"[[Dash]]\",\"marks\":[{\"type\":\"code\"}]"))
        assertTrue(s.contains("\"codeBlock\",\"content\":[{\"type\":\"text\",\"text\":\"[[Dash]]\"}]"))
        assertTrue("marks carry onto the surviving text", s.contains("\"marks\":[{\"type\":\"bold\"}]"))
    }

    @Test fun markdownBindingProducesCanonicalLinksAndSkipsEveryCodeForm() {
        val text = "[[A]] and `[[A]]` and\n\n```\n[[A]]\n```\n\n    [[A]]\n\n~~~~\n[[A]]\n~~~~\n\n[[A]]\n"
        val bound = WikiLinks.binding("a", id(1), text)!!
        assertEquals(1 + 1, Regex("\\(wiki:").findAll(bound).count())
        assertTrue(bound.startsWith("[A](wiki:${id(1)}) and `[[A]]`"))
        assertTrue(bound.contains("```\n[[A]]\n```"))
        assertTrue(bound.contains("    [[A]]"))
        assertTrue(bound.contains("~~~~\n[[A]]\n~~~~"))
        val second = WikiLinks.binding("a", id(1), text, occurrence = 1)!!
        assertTrue("only the second prose reference is bound", second.startsWith("[[A]] and `[[A]]`") && second.endsWith("[A](wiki:${id(1)})\n"))
        assertNull(WikiLinks.binding("none", id(1), text))
    }

    @Test fun unicodeTitlesRoundTripThroughBinding() {
        val title = "Café ☕ 😀 é"
        val state = body("see [[$title]] here").editorState!!
        val bound = WikiLinks.binding(title, id(1), state)!!
        val refs = WikiLinks.references(DocumentBody(bodyOf(bound), DocumentBody.ENVELOPE_CONTENT_TYPE))
        assertEquals(title, refs.single().title)
        assertEquals(id(1), refs.single().documentId)
        val md = WikiLinks.binding(title, id(1), "see [[$title]] here")!!
        assertTrue(md.contains("(wiki:${id(1)})"))
        assertNotNull(DocumentRender.blocks(DocumentBody(md.toByteArray(), "text/markdown")))
    }

    @Test fun backlinksIgnoreCodeAndOnlyCountBoundIds() {
        val linking = DocumentBody("[x](wiki:${id(1)}) `[y](wiki:${id(2)})`".toByteArray(), "text/markdown")
        assertTrue(WikiLinks.links(linking, id(1)))
        assertFalse(WikiLinks.links(linking, id(2)))
    }
}
