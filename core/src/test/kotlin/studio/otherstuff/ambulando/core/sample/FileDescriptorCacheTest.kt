package studio.otherstuff.ambulando.core.sample

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.FileDescriptorCache
import studio.otherstuff.ambulando.core.transport.FileRecord

class FileDescriptorCacheTest {
    private fun file(version: String="v1",sha: String?="hash",binding: String="one") = FileRecord(
        SampleIds.STUDIO,SampleIds.BOARD_FILE,null,SampleIds.GENERAL,null,null,"App.html",contentType="text/html",sizeBytes=1,
        sha256=sha,createdAt="",updatedAt="",raw=buildJsonObject {
            put("current_version_id",version); put("metadata",buildJsonObject { put("binding",binding) })
        })
    @Test fun unchangedPinsReuseLabelsButVersionHashBindingAndRevocationInvalidateThem() {
        val cache=FileDescriptorCache<String>()
        val file=file()
        cache.put(file,"verified label")
        assertEquals("verified label",cache.get(file))
        assertNull(cache.get(file("v2")))
        assertNull(cache.get(file(sha="changed")))
        assertNull(cache.get(file(binding="different access")))
        assertNull(cache.get(file.copy(accessRemoved=true)))
        assertNull(cache.get(file.copy(archived=true)))
        assertNull(cache.get(file(sha=null)))
        assertNull(cache.get(null))
        cache.retain(emptySet())
        assertNull(cache.get(file))
    }
}
