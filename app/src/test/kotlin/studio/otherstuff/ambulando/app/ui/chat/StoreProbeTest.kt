package studio.otherstuff.ambulando.app.ui.chat

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.core.store.LocalStore
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class StoreProbeTest {
    @Test fun opens() = runBlocking {
        LocalStore(Files.createTempDirectory("probe").toFile()).use { println("schema " + it.writer.schemaVersion()) }
    }
}
