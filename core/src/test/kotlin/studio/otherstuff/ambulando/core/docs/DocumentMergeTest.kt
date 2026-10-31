package studio.otherstuff.ambulando.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.DocumentReviewCodec

/** Three-way merge of exact saved versions: independent edits combine, overlaps need a choice, nothing is guessed. */
class DocumentMergeTest {
    companion object {
        fun base() = Docs.snapshot(Docs.rich("base text"))
    }

    private fun merged(base: ByteArray, local: ByteArray, latest: ByteArray, choices: Map<String, DocumentMerge.Choice> = emptyMap()) =
        DocumentMerge.merge(Docs.snapshot(base), Docs.snapshot(local, version = 1), Docs.snapshot(latest, version = 2), choices)

    @Test fun disjointRichEditsCombineAndKeepUnknownEnvelopeFields() {
        val base = Docs.rich("One", "Two", "Three")
        val result = merged(base, Docs.rich("One edited here", "Two", "Three"), Docs.rich("One", "Two", "Three edited there"))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("One edited here", "Two", "Three edited there"), Docs.paragraphs(result.bytes))
        val root = JsonValue.parse(result.bytes)
        assertEquals(JsonValue.Bool(true), root["future_field"]?.get("keep"))
        assertEquals(JsonValue.Arr(listOf(JsonValue.Str("r1"))), root["content_model"]?.get("references"))
        // Generated Markdown is regenerated from the merged rich state, not carried over from either side.
        assertEquals("One edited here\n\nTwo\n\nThree edited there", root["content_model"]?.get("content")?.stringValue)
    }

    @Test fun editsInDifferentWordsOfTheSameParagraphCombine() {
        val base = Docs.rich("alpha beta gamma")
        val result = merged(base, Docs.rich("ALPHA beta gamma"), Docs.rich("alpha beta GAMMA"))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("ALPHA beta GAMMA"), Docs.paragraphs(result.bytes))
    }

    @Test fun overlappingEditsNeedAnExplicitChoiceAndNeverSilentlyReplace() {
        val base = Docs.rich("alpha beta gamma")
        val local = Docs.rich("alpha MINE gamma")
        val latest = Docs.rich("alpha THEIRS gamma")
        val first = merged(base, local, latest)
        assertEquals(1, first.conflicts.size)
        val conflict = first.conflicts.single()
        assertEquals("Overlapping passage", conflict.label)
        assertTrue(conflict.local.contains("MINE"))
        assertTrue(conflict.latest.contains("THEIRS"))
        assertFalse("internal paths stay out of the message", conflict.local.contains("/content"))
        // Unresolved, the preview keeps the person's own text.
        assertEquals(listOf("alpha MINE gamma"), Docs.paragraphs(first.bytes))
        val useLatest = merged(base, local, latest, mapOf(conflict.id to DocumentMerge.Choice.LATEST))
        assertTrue(useLatest.conflicts.isEmpty())
        assertEquals(listOf("alpha THEIRS gamma"), Docs.paragraphs(useLatest.bytes))
        val useLocal = merged(base, local, latest, mapOf(conflict.id to DocumentMerge.Choice.LOCAL))
        assertEquals(listOf("alpha MINE gamma"), Docs.paragraphs(useLocal.bytes))
    }

    @Test fun unicodeIsNeverSplitInsideACharacter() {
        val family = "👩‍👩‍👧"            // ZWJ sequence
        val accented = "é"          // combining mark
        val flag = "🇯🇵"                  // regional indicators
        val base = Docs.rich("$family|$accented|$flag")
        val result = merged(base, Docs.rich("🧑‍🚀|$accented|$flag"), Docs.rich("$family|$accented|🇨🇦"))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("🧑‍🚀|$accented|🇨🇦"), Docs.paragraphs(result.bytes))
        // Both sides change the same combined character differently: one conflict, not a half emoji.
        val clash = merged(base, Docs.rich("🧑‍🚀|$accented|$flag"), Docs.rich("👨‍👩‍👦|$accented|$flag"))
        assertEquals(1, clash.conflicts.size)
        val text = Docs.paragraphs(clash.bytes).single()
        assertTrue(text.startsWith("🧑‍🚀"))
        assertTrue(text.codePoints().toArray().none { it in 0xD800..0xDFFF })
    }

    @Test fun deletionsCombineWithEditsAndConflictWhenBothTouchTheSameBlock() {
        val base = Docs.rich("One", "Two", "Three")
        val result = merged(base, Docs.rich("One", "Three"), Docs.rich("One changed", "Two", "Three"))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("One changed", "Three"), Docs.paragraphs(result.bytes))
        // Remote deletes the paragraph local rewrote.
        val clash = merged(base, Docs.rich("One", "Two rewritten", "Three"), Docs.rich("One", "Three"))
        assertEquals(1, clash.conflicts.size)
        // The overlap is reported over the affected passage as a whole; neither side is applied without a choice.
        assertTrue(clash.conflicts.single().local.contains("Two rewritten"))
        assertFalse(clash.conflicts.single().latest.contains("Two"))
        assertEquals(listOf("One", "Two rewritten", "Three"), Docs.paragraphs(clash.bytes))
        val dropped = merged(base, Docs.rich("One", "Two rewritten", "Three"), Docs.rich("One", "Three"), mapOf(clash.conflicts.single().id to DocumentMerge.Choice.LATEST))
        assertEquals(listOf("One", "Three"), Docs.paragraphs(dropped.bytes))
    }

    @Test fun formattingConflictsAreDescribedNotShownAsPaths() {
        val base = Docs.rich("Same text")
        val clash = merged(base, Docs.rich("Same text", marks = mapOf(0 to "bold")), Docs.rich("Same text", marks = mapOf(0 to "italic")))
        assertEquals(1, clash.conflicts.size)
        assertEquals("Text formatting", clash.conflicts.single().label)
        // Marks on one side only combine.
        val ok = merged(Docs.rich("Same text", "Other"), Docs.rich("Same text", "Other", marks = mapOf(0 to "bold")), Docs.rich("Same text", "Other changed"))
        assertTrue(ok.conflicts.isEmpty())
        assertEquals(listOf("Same text", "Other changed"), Docs.paragraphs(ok.bytes))
        assertEquals("bold", JsonValue.parse(ok.bytes)["content_model"]!!["editor_state"]!!["content"]!!.arrayValue!![0]["content"]!!.arrayValue!![0]["marks"]!!.arrayValue!![0]["type"]!!.stringValue)
    }

    @Test fun titlesMergeLikeTextAndConflictWhenBothChange() {
        val base = Docs.rich("Body")
        val onlyLocal = DocumentMerge.merge(Docs.snapshot(base), Docs.snapshot(base, "My title"), Docs.snapshot(Docs.rich("Body edited"), version = 2))
        assertEquals("My title", onlyLocal.title)
        assertTrue(onlyLocal.conflicts.isEmpty())
        val both = DocumentMerge.merge(Docs.snapshot(base), Docs.snapshot(base, "Mine"), Docs.snapshot(base, "Theirs", 2))
        assertEquals("Title", both.conflicts.single().label)
        val chosen = DocumentMerge.merge(Docs.snapshot(base), Docs.snapshot(base, "Mine"), Docs.snapshot(base, "Theirs", 2), mapOf("/title" to DocumentMerge.Choice.LATEST))
        assertEquals("Theirs", chosen.title)
    }

    @Test fun markdownMergesKeepWhitespaceAndDeletions() {
        val base = "# Plan\n\n  indented line  \nkeep\n\nlast\n"
        val local = DocumentMerge.merge(Docs.markdown(base), Docs.markdown("# Plan\n\n  indented line  \nkeep\n\nlast edited\n"), Docs.markdown("# Plan v2\n\n  indented line  \n\nlast\n", version = 2))
        assertTrue(local.conflicts.isEmpty())
        assertEquals("# Plan v2\n\n  indented line  \n\nlast edited\n", local.bytes.toString(Charsets.UTF_8))
    }

    @Test fun mismatchedBaseOrFormatIsRefused() {
        val base = Docs.rich("One")
        val tampered = Docs.snapshot(base).let { PreservedDocumentSnapshot(it.title, Docs.rich("Different"), it.contentType, it.rowVersion, it.sha256, it.storageObjectId) }
        try {
            DocumentMerge.merge(tampered, Docs.snapshot(base), Docs.snapshot(base, version = 2))
            fail("a base that no longer matches its hash must not be used")
        } catch (e: DocumentEditError) {
            assertEquals(DocumentEditError.Reason.INTEGRITY, e.reason)
        }
        try {
            DocumentMerge.merge(Docs.snapshot(base), Docs.markdown("text"), Docs.snapshot(base, version = 2))
            fail("different stored formats cannot be merged")
        } catch (e: DocumentEditError) {
            assertEquals(DocumentEditError.Reason.UNSUPPORTED, e.reason)
        }
    }

    @Test fun veryLargeOverlappingTextBecomesAnExplicitConflict() {
        val lines = (1..5000).joinToString("\n") { "line $it" }
        val local = DocumentMerge.merge(Docs.markdown(lines), Docs.markdown(lines.replace("line 10\n", "mine\n")), Docs.markdown(lines.replace("line 4000\n", "theirs\n"), version = 2))
        assertEquals("too large to align safely, so the person decides", 1, local.conflicts.size)
    }

    @Test fun reviewDiffIsLineBasedAndBounded() {
        val lines = DocumentReviewDiff.lines("a\nb\nc", "a\nB\nc\nd")
        assertEquals(listOf(" a", "−b", "+B", " c", "+d"), lines.map { it.prefix + it.text })
        assertTrue(DocumentReviewDiff.lines("x\n".repeat(6000), "y\n".repeat(6000)).isEmpty())
    }

    @Test fun journalRoundTripsExactBytesAndOnlyTrustsVerifiedBases() {
        val journal = DocumentReviewJournal()
        val binary = byteArrayOf(0, -1, 10, 13, 0x7f, -128) // not UTF-8, not JSON
        val odd = PreservedDocumentSnapshot("Odd 😀", binary, "application/octet-stream", 3, Sha256Hex.of(binary), Docs.OBJECT)
        journal.retainBase(Docs.snapshot(Docs.rich("v1"), version = 1))
        journal.retainBase(Docs.snapshot(Docs.rich("v2"), version = 2))
        journal.preserve(odd)
        journal.choices["/body/content/0"] = "latest"
        journal.previewText = "preview"; journal.previewTitle = "T"
        journal.previousPreviews.add(PreservedMergePreview("old", "older text", Docs.snapshot(Docs.rich("v2"), version = 2)))
        journal.mergeBase = journal.savedBases[0]; journal.pendingMergedSave = true
        val decoded = DocumentReviewCodec.decode(DocumentReviewCodec.encode(journal))
        assertEquals(journal.base, decoded.base)
        assertEquals(1, decoded.savedBases.size)
        assertTrue(decoded.retained.single().bytes.contentEquals(binary))
        assertEquals("Odd 😀", decoded.retained.single().title)
        assertEquals(mapOf("/body/content/0" to "latest"), decoded.choices)
        assertEquals("preview", decoded.previewText)
        assertEquals("older text", decoded.previousPreviews.single().text)
        assertTrue(decoded.pendingMergedSave)
        // A retained base is only offered when its bytes still verify against the identity.
        assertNotNull(decoded.commonBase(Docs.snapshot(Docs.rich("v1"), version = 1)))
        val corrupted = DocumentReviewJournal().apply {
            retainBase(PreservedDocumentSnapshot("t", Docs.rich("tampered"), DocumentBody.ENVELOPE_CONTENT_TYPE, 1, Sha256Hex.of(Docs.rich("original")), Docs.OBJECT))
        }
        assertNull(corrupted.commonBase(PreservedDocumentSnapshot("t", Docs.rich("original"), DocumentBody.ENVELOPE_CONTENT_TYPE, 1, Sha256Hex.of(Docs.rich("original")), Docs.OBJECT)))
    }
}
