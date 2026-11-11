package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.tempDir
import java.util.UUID

class ProducerOutboxTest {
    @Test fun replayAfterHandoffAndRetirementCannotDuplicateOrClearTypingDraft() = runBlocking {
        val directory = tempDir()
        val id = UUID.randomUUID().toString()
        val original = ChatTarget("connection", "workspace", "original-channel", "original-thread")
        LocalStore(directory).use { store ->
            store.writer.saveConnection(ConnectionRecord("connection", "https://tower.example", "signer", "tower", ""))
            store.writer.saveDraft(original, Draft("Keep my typed draft", emptyList()))
            store.writer.enqueueSend(original, "https://tower.example", "signer", "app", "Voice message", emptyList(), id = id, preserveDraft = true, once = true)
            store.writer.enqueueSend(original, "https://tower.example", "signer", "app", "Voice message", emptyList(), id = id, preserveDraft = true, once = true)
            assertEquals(1, store.reader.outbox(original).size)
            val row = store.reader.outbox(original).single()
            assertEquals("original-channel", row.channelId)
            assertEquals("original-thread", row.threadId)
            assertEquals("Keep my typed draft", store.reader.draft(original)!!.text)
            store.writer.discardOutbox(id, false)
        }
        LocalStore(directory).use { store ->
            store.writer.enqueueSend(original, "https://tower.example", "signer", "app", "Voice message", emptyList(), id = id, preserveDraft = true, once = true)
            assertTrue(store.reader.outbox(original).isEmpty())
            assertEquals("Keep my typed draft", store.reader.draft(original)!!.text)
        }
    }
}
