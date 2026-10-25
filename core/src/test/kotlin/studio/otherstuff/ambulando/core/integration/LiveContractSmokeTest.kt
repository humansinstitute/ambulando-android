package studio.otherstuff.ambulando.core.integration

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File

/** Optional read-only host snapshots; private response bodies never enter the source tree. */
class LiveContractSmokeTest {
    @Test fun parsesBrokerAuthorizedScopeChannelAndTaskResponses() {
        val directory = System.getenv("AMBULANDO_CONTRACT_DIR")
        assumeTrue("Set AMBULANDO_CONTRACT_DIR to ignored broker read-only snapshots", !directory.isNullOrBlank())
        fun read(name: String) = TowerJson.parseToJsonElement(File(directory!!, "live-$name.json").readText()).jsonObject
        val scopes = read("scopes")["scopes"]!!.jsonArray.map { ScopeRecord.parse(it.jsonObject) }
        val channels = read("channels")["channels"]!!.jsonArray.map { ChannelRecord.parse(it.jsonObject) }
        val task = TaskRecord.parse(read("task")["task"]!!.jsonObject)
        assertTrue(scopes.isNotEmpty())
        assertTrue(channels.isNotEmpty())
        assertTrue(scopes.any { it.id == task.scopeId })
        assertTrue(channels.any { it.id == task.channelId })
        assertTrue(channels.all { it.workspaceId == task.workspaceId })
    }
}
