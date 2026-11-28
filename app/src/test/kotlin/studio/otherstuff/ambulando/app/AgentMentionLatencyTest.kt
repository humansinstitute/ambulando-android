package studio.otherstuff.ambulando.app

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.localContentOwner
import androidx.compose.runtime.mutableStateOf
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import studio.otherstuff.ambulando.app.ui.chat.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentMentionLatencyTest {
    @get:Rule val compose = createComposeRule()
    private class Memory : VaultSecretStore {
        private var data: ByteArray? = null
        override fun read() = data?.copyOf()
        override fun write(bytes: ByteArray) { data = bytes.copyOf() }
    }
    @Test fun unprefixedAgentAppearsWhileFileDiscoveryIsBlocked() = scenario(false)
    @Test fun upgrade108CachePreventsPersonalSqlDowngradeBeforeFreshStatus() = scenario(true)
    private fun scenario(upgrade: Boolean) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root=Files.createTempDirectory("agent-mention-latency").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getCacheDir()=File(root,"cache").also{it.mkdirs()}
        }
        val identity=IdentityService(File(root,"vault"),Memory())
        val npub=runBlocking{identity.importIdentity(Bech32.encode("nsec",ByteArray(32){29}),"123456").npub!!}
        val tower=SampleTower(File(root,"tower")).also{it.prepare(npub)}
        val release=CompletableDeferred<Unit>(); var blocked=false; var started=false; var nullAuthority=false
        val transport=object:HttpTransport {
            override suspend fun send(request:HttpRequest):HttpResponse {
                if(nullAuthority && request.url.endsWith("/status"))return HttpResponse(200,jsonObjectOf("version" to 1,"authority" to null).toString().toByteArray())
                if(blocked && request.url.substringBefore('?').endsWith("/files")) { started=true; release.await() }
                return tower.send(request)
            }
        }
        lateinit var model:AppModel
        compose.runOnUiThread { model=AppModel(AppEnvironment.Sample(context),context,AppSessionDependencies(identity,transport,tower.events),restoreInteractiveIdentity=false) }
        try {
            compose.waitUntil(30000){model.active?.validated==true}
            compose.runOnIdle{model.selectWorkspace(model.workspaces.first{it.id==SampleIds.STUDIO})}
            compose.waitUntil(10000){model.workspace?.id==SampleIds.STUDIO && model.active?.validated==true}
            runBlocking{model.sharedEngine.waitUntilIdle()}
            if(upgrade) {
                val c=model.connection!!;val w=model.workspace!!;val runtime=model.active!!
                val controller=model.goalsController()!!
                runBlocking {
                    controller.refresh();controller.setUp(null,emptyList())
                    val doc=controller.create(Goal.create("Retained SQL goal","Upgrade guard"))
                    val local=GoalsLocalContent(runtime.store,w.id,c.signerNpub,localContentOwner(c,w))
                    local.save(controller.state.value.copy(goals=listOf(doc.copy(versionId="sql:7"))))
                    val oldKey="goals.relational.observed.v1:${c.signerNpub}:${w.id}:${localContentOwner(c,w)}"
                    runtime.store.writer.setState(oldKey,"observed")
                    Assert.assertNull(runtime.store.reader.state("$oldKey:channel:${doc.file.channelId}"))
                    nullAuthority=true
                    // A fresh repository has no in-memory channel observations: only108 persisted state protects it.
                    Assert.assertThrows(GoalSaveFailed::class.java){runBlocking{model.fileNappletAPI(c).goalFiles(w.id,w.appNpub,doc.file.channelId)}}
                    Assert.assertEquals("sql:7",local.load()!!.goals.single().versionId)
                    // Even an empty SQL home snapshot must not permit a storage downgrade.
                    local.save(controller.state.value.copy(goals=emptyList()))
                    Assert.assertThrows(GoalSaveFailed::class.java){runBlocking{model.fileNappletAPI(c).goalFiles(w.id,w.appNpub,doc.file.channelId)}}
                }
                return
            }
            // The real workspace agent directory is already local before typing.
            val agent=runBlocking{model.mentionCandidates("agent:")}.first()
            blocked=true
            val query=mutableStateOf<String?>(null)
            var start=0L
            compose.setContent {
                val results=rememberMentionLookup(query.value,model.activeBoundaryId, model::localMentionCandidates) { model.mentionCandidates(it) }
                AmbulandoTheme { MentionLookahead(results.candidates,{}) }
            }
            compose.runOnIdle{start=System.nanoTime();query.value=agent.mention.label}
            compose.waitUntil(750){compose.onAllNodesWithText(agent.mention.label).fetchSemanticsNodes().isNotEmpty()}
            val firstRowMs=(System.nanoTime()-start)/1_000_000
            compose.waitUntil(5000){started}
            compose.onNodeWithText(agent.mention.label).assertIsDisplayed()
            println("AGENT_MENTION elapsedMs="+firstRowMs+" discoveryBlocked="+started)
            Assert.assertFalse(release.isCompleted)
        } finally {
            blocked=false;release.complete(Unit)
            compose.runOnUiThread{(AppModel::class.java.getDeclaredField("viewModelScope").apply{isAccessible=true}.get(model) as CoroutineScope).cancel()}
            runBlocking{model.runtimes.close()};model.sharedEngine.close();model.catalog.close()
            Dispatchers.resetMain();root.deleteRecursively()
        }
    }
}
