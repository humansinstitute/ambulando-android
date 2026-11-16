package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.LocalStages
import studio.otherstuff.ambulando.core.transport.LocalStages.Stage
import studio.otherstuff.ambulando.core.transport.LocalStages.Result
import studio.otherstuff.ambulando.core.transport.ConnectionIssue
import studio.otherstuff.ambulando.core.transport.SharedConnectionException

class ActiveFailureObservationTest {
    private val allowed = setOf(Stage.ACTIVE_FAILED_RESUME_ACCESS, Stage.ACTIVE_FAILED_SCOPES,
        Stage.ACTIVE_FAILED_PROFILES, Stage.ACTIVE_FAILED_ATTENTION,
        Stage.ACTIVE_FAILED_NOTIFICATIONS, Stage.ACTIVE_FAILED_PINS, Stage.ACTIVE_FAILED_OTHER)

    @Test fun everyActualWorkspaceKeyFactoryHasAFiniteCategory() {
        val factories = WorkspaceSync.Companion.javaClass.declaredMethods.filter {
            it.name.endsWith("Key") && it.returnType == String::class.java && it.parameterTypes.all { type -> type == String::class.java }
        }
        assertTrue(factories.size >= 30)
        factories.forEach { factory ->
            val key = factory.invoke(WorkspaceSync.Companion, *Array<Any>(factory.parameterCount) { "private-id" }) as String
            assertNotEquals(factory.name, Stage.ACTIVE_FAILED_OTHER, activeFailureCategory(key))
        }
        val connection = studio.otherstuff.ambulando.core.store.ConnectionRecord.create(
            studio.otherstuff.ambulando.core.transport.TowerOrigin("https://synthetic.example"), "private", null)
        assertEquals(Stage.ACTIVE_FAILED_PROFILES, activeFailureCategory(studio.otherstuff.ambulando.core.profiles.ProfileSync.jobKey("private", "private")))
        assertEquals(Stage.ACTIVE_FAILED_OUTBOX, activeFailureCategory(studio.otherstuff.ambulando.core.chat.OutboxSender.key(connection)))
        assertEquals(Stage.ACTIVE_FAILED_WRITES, activeFailureCategory(WriteSender.key(connection)))
        assertEquals(Stage.ACTIVE_FAILED_DOC_LEASE, activeFailureCategory(studio.otherstuff.ambulando.core.docs.leaseKey("private", "private", "private", "private")))
    }

    @Test fun allCoreAndAppSchedulingLiteralsAndDynamicFactoriesAreInventoried() {
        val root = java.io.File("..").takeIf { java.io.File(it, "app/src/main/kotlin").isDirectory } ?: java.io.File(".")
        val literals = linkedSetOf<String>()
        for (module in listOf("core", "app")) {
            java.io.File(root, "$module/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                file.readLines().filter { line ->
                    Regex("(?:\\.perform|\\.enqueue(?:Read)?|job)\\(").containsMatchIn(line) ||
                        (file.name == "WorkspaceSync.kt" && line.contains("fun ") && line.contains("Key("))
                }.forEach { line ->
                    Regex("\"([a-z][a-z-]*):").findAll(line).forEach { literals.add(it.groupValues[1]) }
                }
            }
        }
        // Dynamic factories/forwarding wrappers, checked against their production definitions below.
        val definitions = mapOf(
            "profiles" to "profiles/ProfileSync.kt", "writes" to "sync/WriteSender.kt", "outbox" to "chat/Outbox.kt",
            "doc-lease" to "docs/DocumentSaving.kt")
        definitions.forEach { (prefix, path) ->
            assertTrue(java.io.File(root, "core/src/main/kotlin/studio/otherstuff/ambulando/core/$path").readText().contains("\"$prefix:"))
            literals.add(prefix)
        }
        val documentRunner = java.io.File(root, "core/src/main/kotlin/studio/otherstuff/ambulando/core/docs/DocumentSaving.kt").readText()
        val documentOperations = Regex("key\\(target, [^,\\n]*, \"([^\"]+)\"\\)").findAll(documentRunner).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("save", "inspect", "latest", "versions"), documentOperations)
        literals.addAll(documentOperations.map { "doc-$it" })
        assertTrue(literals.size >= 50)
        literals.forEach { assertNotEquals(it, Stage.ACTIVE_FAILED_OTHER, activeFailureCategory("$it:private")) }
        // The public observation boundary is categorical even for adversarial suffixes and lookalikes.
        for (key in listOf("https://secret.example/path", "taskFake:secret", "resume-access:secret", "TASK:secret", "tasks")) {
            assertEquals(Stage.ACTIVE_FAILED_OTHER, activeFailureCategory(key))
        }
    }

    @Test fun actualScopedWarningSurvivesHealthyAlternateReadAndOnlyObservedWorkspaceIsClassified() = runBlocking {
        val engine = SyncEngine()
        val active = ScopedSyncScheduler(engine, "active-private", "connection-private", WorkspaceActivity(true))
        val other = ScopedSyncScheduler(engine, "other-private", "connection-private", WorkspaceActivity(true))
        val captured = mutableListOf<LocalStages.Event>()
        val previous = LocalStages.observer
        try {
            var attempts = 0
            suspend fun fail(scheduler: SyncScheduler, key: String) {
                scheduler.enqueue(key, SyncPriority.VISIBLE) { attempts++; throw SharedConnectionException(ConnectionIssue.CONSENT, "private payload") }
                scheduler.waitUntilIdle()
            }
            fail(engine, "discover:private")
            fail(other, "notifications:private")
            fail(active, "profiles:private:workspace")
            active.perform("resume-access") { attempts++; "healthy" }
            active.waitUntilIdle() // perform returns its value before scheduler completion is published.
            val observed = active.status.value
            assertEquals(setOf("profiles:private:workspace"), observed.errors.keys)
            assertEquals(ConnectionIssue.CONSENT.userMessage, observed.errors.values.single())
            LocalStages.observer = { captured.add(it) }
            engine.observeActiveFailures(engine.status.value)
            assertTrue(captured.isEmpty())
            active.observeActiveFailures(observed)
            assertEquals(listOf(Stage.ACTIVE_FAILED_PROFILES), captured.map { it.stage })
            assertEquals(listOf(Result.FAILED), captured.map { it.result })
            assertEquals(observed, active.status.value) // Observation cannot hide or resolve the warning.
            assertEquals(4, attempts) // No diagnostic retry.
            active.enqueue("profiles:private:workspace", SyncPriority.VISIBLE, force = true) { }
            active.waitUntilIdle()
            captured.clear()
            active.observeActiveFailures(active.status.value)
            assertTrue(captured.isEmpty())
            assertTrue(engine.status.value.errors.containsKey("discover:private"))
            assertTrue(other.status.value.errors.isNotEmpty())
        } finally { LocalStages.observer = previous; engine.close() }
    }

    @Test fun everyFinitePrefixSurvivesActualScopedSchedulerObservationWithoutPayloads() = runBlocking {
        val engine = SyncEngine()
        val scoped = ScopedSyncScheduler(engine, "private", "private", WorkspaceActivity(true))
        val captured = mutableListOf<LocalStages.Event>()
        val previous = LocalStages.observer
        try {
            val prefixes = "discover scopes profiles attention-types notifications pins messages reactions thread directory channels tasks task task-relationships docs doc files inbox events scope-order scope-collapsed notifications-archive notification-item notification-prefs context context-refs canvases canvas board agents agent-activity toolbox thread-record download attachment voice admin manage personal-agents writes outbox export doc-move doc-save doc-lease doc-inspect doc-latest doc-versions paste branch rename-thread naplet-html pin-order canvas-create board-versions board-read board-publish board-rename task-tree tree-create tree-view canvas-edit".split(" ")
            val payload = "https://secret.example/npub?grant=private#hash"
            for (prefix in prefixes) scoped.enqueueRead("$prefix:$payload", SyncPriority.VISIBLE) { error(payload) }
            scoped.waitUntilIdle()
            LocalStages.observer = { captured.add(it) }
            repeat(6) { scoped.observeActiveFailures(scoped.snapshot()) }
            assertEquals(prefixes.map { activeFailureCategory("$it:") }.toSet(), captured.map { it.stage }.toSet())
            assertFalse(captured.any { it.stage == Stage.ACTIVE_FAILED_OTHER })
            assertFalse(captured.toString().contains("secret"))
            assertFalse(captured.toString().contains("private"))
            assertEquals(256, LocalStages.snapshot().size)
            assertEquals(prefixes.size, scoped.snapshot().errors.size)
        } finally { LocalStages.observer = previous; engine.close() }
    }

    @Test fun allFiniteCategoriesAndAdversarialPayloadsRemainRedactedAndBounded() = runBlocking {
        val engine = SyncEngine()
        val scoped = ScopedSyncScheduler(engine, "private", "private", WorkspaceActivity(true))
        val captured = mutableListOf<LocalStages.Event>()
        val previous = LocalStages.observer
        try {
            LocalStages.observer = { captured.add(it) }
            val secret = "https://private.example/identity?grant=secret|workspace"
            val keys = listOf("resume-access", "scopes:$secret", "profiles:$secret", "attention-types:$secret",
                "notifications:$secret", "pins:$secret", secret, "resume-access:$secret", "profilesFake:$secret") +
                (1..400).map { "scopes:$secret:$it" }
            val snapshot = SyncSnapshot(errors = keys.associateWith { secret })
            repeat(60) { scoped.observeActiveFailures(snapshot) }
            assertEquals(420, captured.size) // At most seven events, even with hundreds of failed keys.
            assertEquals(allowed, captured.map { it.stage }.toSet())
            assertTrue(captured.all { it.result == Result.FAILED && it.parent == 0L })
            assertTrue(captured.chunked(7).all { group -> group.map { it.operation }.distinct().size == 1 })
            assertEquals(256, LocalStages.snapshot().size)
            assertFalse(captured.toString().contains(secret))
            assertFalse(captured.toString().contains("private"))
            assertEquals(snapshot.errors, keys.associateWith { secret })
        } finally { LocalStages.observer = previous; engine.close() }
    }
}
