package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.*

class SampleStorageBoundaryTest {
    @Test fun storageLookupFindsTheOwningWorkspaceRatherThanStoppingAtStudio() = runBlocking {
        val h = SampleHarness()
        val bytes = "Lab synthetic upload".toByteArray()
        val objectId = h.api.store(SampleIds.LAB,h.app,bytes,"text/plain","lab.txt",null) { }
        assertArrayEquals(bytes,h.api.storageContent(objectId))
        assertEquals(bytes.size.toLong(),flexibleLong(h.api.storageObject(objectId)!!.get("size_bytes")))
    }
}
