package studio.otherstuff.ambulando.core.profiles

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

// Public Nostr profiles: parsing, name/avatar precedence, verification of relay events, freshness and batching, the
// relay query and the avatar cache rules. Synthetic keys and data only; no public network.

/** A synthetic person whose secret is `byte` repeated (a valid secp256k1 scalar for small bytes). */
private class Person(byte: Int) {
    val secret = ByteArray(32) { byte.toByte() }
    val pubkey = NostrKeys.publicKeyHex(secret)
    val npub = NostrKeys.npub(pubkey)!!

    fun profile(content: Map<String, Any?>, at: Long, kind: Int = 0): NostrEvent =
        NostrKeys.sign(NostrEventTemplate(kind, emptyList(), JsonFields.encode(studio.otherstuff.ambulando.core.j(content)), at), secret)
}

class ProfileContentTest {
    @Test fun displayNameBeforeNameAndUsernameFallback() {
        val both = NostrProfileContent.parse("""{"display_name":"  Ana Lima ","name":"ana"}""")
        assertEquals("Ana Lima", both.displayName)
        assertEquals("ana", both.name)
        assertEquals("Ana Lima", ProfileRecord("", "", both.displayName, both.name, status = ProfileRecord.Status.FOUND, checkedAt = Instant.now()).preferredName)
        val camel = NostrProfileContent.parse("""{"displayName":"Bea","username":"bea_k"}""")
        assertEquals("Bea", camel.displayName)
        assertEquals("bea_k", camel.name)
        assertEquals("cal", ProfileRecord("", "", name = "cal", status = ProfileRecord.Status.FOUND, checkedAt = Instant.now()).preferredName)
    }

    @Test fun brokenAndHostileContentIsDropped() {
        assertEquals(NostrProfileContent.Fields(), NostrProfileContent.parse("not json"))
        assertEquals(NostrProfileContent.Fields(), NostrProfileContent.parse("""["array"]"""))
        assertEquals(NostrProfileContent.Fields(), NostrProfileContent.parse("""{"name":42,"display_name":"   ","picture":7}"""))
        assertEquals("Ana Lima", NostrProfileContent.parse("{\"name\":\"Ana\\nLima\\u0007\"}").name)
        assertEquals(120, NostrProfileContent.parse("{\"name\":\"${"x".repeat(500)}\"}").name?.length)
        val huge = "{\"name\":\"a\",\"about\":\"${"y".repeat(NostrProfileContent.MAX_CONTENT_BYTES)}\"}"
        assertNull(NostrProfileContent.parse(huge).name)
    }

    @Test fun pictureKeysAndUrlPolicy() {
        assertEquals("https://img.example.com/a.png", NostrProfileContent.parse("""{"image":"https://img.example.com/a.png"}""").picture.toString())
        assertEquals("cdn.example.com", NostrProfileContent.parse("""{"picture":"http://img.example.com/a.png","image":"https://cdn.example.com/b.png"}""").picture?.host)
        for (refused in listOf("http://img.example.com/a.png", "https://user:pw@img.example.com/a.png", "https://192.168.1.10/a.png", "https://[::1]/a.png",
            "https://localhost/a.png", "https://printer.local/a.png", "https://nas.home.arpa/a.png", "https://10.1/a.png",
            "https://2130706433/a.png", "https://0x7f.0x1/a.png", "file:///etc/passwd", "data:image/png;base64,AAAA", "javascript:alert(1)",
            "https://" + "a".repeat(2100) + ".com/a.png")) {
            assertNull(refused, AvatarURLPolicy.validate(refused))
        }
        assertNotNull(AvatarURLPolicy.validate("https://nostr.build/i/abc.jpg"))
        assertNotNull(AvatarURLPolicy.validate("https://avatars.sample.invalid/ana.png"))
    }

    @Test fun initialsFollowTheWebHelper() {
        assertEquals("AL", PersonInitials.of("Ana Lima"))
        assertEquals("CA", PersonInitials.of("cal"))
        assertEquals("?", PersonInitials.of("  "))
        assertEquals("YS", PersonInitials.of("You (sample identity)"))
        assertEquals("((", PersonInitials.of("(()) !!"))
        assertEquals("NP", PersonInitials.of("npub1abc…wxyz"))
        assertEquals("ÉØ", PersonInitials.of("élodie Ø"))
    }
}

class PeopleDirectoryTest {
    private val ana = NostrKeys.npub("a1".repeat(32))!!
    private val agent = NostrKeys.npub("b2".repeat(32))!!
    private val bea = NostrKeys.npub("c3".repeat(32))!!
    private val stranger = NostrKeys.npub("d4".repeat(32))!!
    private val cal = NostrKeys.npub("e5".repeat(32))!!

    private fun profile(npub: String, display: String? = null, name: String? = null, picture: String? = null, at: Long = 1) =
        ProfileRecord(NostrKeys.publicKeyHex(npub)!!, npub, display, name, picture, eventId = "0".repeat(64), eventCreatedAt = at,
            status = ProfileRecord.Status.FOUND, checkedAt = Instant.now())

    /** Public profile, then Tower's member or agent name, then the message label, then the short npub. */
    @Test fun profileThenWorkspaceThenAgentThenMessageLabelThenNpub() {
        val members = listOf(DirectoryMember(ana, "Ana (Studio)"), DirectoryMember(bea, null), DirectoryMember(cal, "Cal Tower"))
        val directory = PeopleDirectory(members, mapOf(agent to PeopleDirectory.AgentInfo("Helper", "https://agents.example.com/helper.png")),
            listOf(profile(ana, display = "Ana Nostr", picture = "https://img.example.com/ana.png"),
                profile(agent, display = "Helper Nostr", picture = "https://img.example.com/helper-public.png"),
                profile(bea, name = "bea")))
        assertEquals("Ana Nostr", directory.person(ana).name)
        assertEquals(PersonDisplay.NameSource.NOSTR, directory.person(ana).nameSource)
        assertEquals("https://img.example.com/ana.png", directory.person(ana).avatarURL.toString())
        assertEquals("a message label never hides the public name", "Ana Nostr", directory.person(ana, "Old label").name)
        assertEquals("Helper Nostr", directory.person(agent).name)
        assertEquals("Tower agent avatar before the profile", "https://agents.example.com/helper.png", directory.person(agent).avatarURL.toString())
        assertEquals("Tower has no name: the public profile names the member", "bea", directory.person(bea).name)
        assertEquals("bea", directory.names[bea])
        assertEquals("Cal Tower", directory.person(cal).name)
        assertEquals(PersonDisplay.NameSource.WORKSPACE, directory.person(cal).nameSource)
        assertEquals("Stranger K", directory.person(stranger, "Stranger K").name)
        assertEquals(shortNpubLabel(stranger), directory.person(stranger).name)
        assertEquals("NP", directory.person(stranger).initials)
        assertNull(directory.person(stranger).avatarURL)
        assertEquals("System", directory.person(null, "System").name)
    }

    @Test fun placeholderAndKeyNamesAreSkipped() {
        val members = listOf(DirectoryMember(ana, "Workspace Creator"), DirectoryMember(bea, shortNpubLabel(bea)), DirectoryMember(cal, cal))
        val directory = PeopleDirectory(members, emptyMap(), listOf(profile(bea, display = "Bea")))
        assertEquals(shortNpubLabel(ana), directory.person(ana).name)
        assertEquals(PersonDisplay.NameSource.NPUB, directory.person(ana).nameSource)
        assertEquals("Bea", directory.person(bea).name)
        assertEquals(shortNpubLabel(cal), directory.person(cal).name)
        assertEquals(shortNpubLabel(cal), directory.person(cal, "ab".repeat(32)).name)
        assertEquals("Cal", directory.person(cal, "Cal").name)
        assertNull(PersonNamePolicy.usable("Flight Deck PG Creator"))
        assertNull(PersonNamePolicy.usable("npub1abcd…wxyz"))
        assertEquals("npub fan club", PersonNamePolicy.usable("npub fan club"))
    }

    @Test fun profilesNeverAddMembersAndBrokenPicturesFallBack() {
        val directory = PeopleDirectory(listOf(DirectoryMember(ana, "Ana")), emptyMap(),
            listOf(profile(stranger, display = "Mallory", picture = "http://insecure.example.com/x.png"), profile(ana, picture = "https://10.0.0.1/a.png")))
        assertEquals(setOf(ana), directory.memberNpubs)
        assertEquals("Mallory", directory.person(stranger).name)
        assertNull(directory.person(stranger).avatarURL)
        assertNull(directory.person(ana).avatarURL)
    }
}

class ProfileEventTest {
    @Test fun onlyVerifiedKindZeroEventsOfRequestedKeysAreAccepted() {
        val ana = Person(0x21); val bea = Person(0x22)
        val good = ana.profile(mapOf("display_name" to "Ana", "picture" to "https://img.example.com/a.png"), 1_800_000_100)
        val older = ana.profile(mapOf("display_name" to "Ana (old)"), 1_800_000_050)
        val other = bea.profile(mapOf("name" to "Bea"), 1_800_000_100)
        val note = ana.profile(mapOf("name" to "Not a profile"), 1_800_000_200, kind = 1)
        val tampered = good.copy(content = """{"display_name":"Mallory"}""")
        val forged = other.copy(pubkey = ana.pubkey)
        val badSig = good.copy(sig = "0".repeat(128))
        val accepted = ProfileEventFilter.accept(listOf(older, tampered, forged, badSig, note, good, other), setOf(ana.pubkey), Instant.ofEpochSecond(1_800_001_000))
        assertEquals(1, accepted.size)
        assertEquals("Ana", accepted.first().displayName)
        assertEquals(1_800_000_100L, accepted.first().eventCreatedAt)
        assertEquals(ana.npub, accepted.first().npub)
        assertEquals("https://img.example.com/a.png", accepted.first().picture)
    }

    @Test fun futureAndPreNostrDatesAreRefused() {
        val ana = Person(0x24)
        val now = Instant.ofEpochSecond(1_900_000_000)
        val future = ana.profile(mapOf("name" to "Pinned forever"), 1_900_000_000 + 3600)
        val ancient = ana.profile(mapOf("name" to "1970"), 1000)
        val skewed = ana.profile(mapOf("name" to "Slightly ahead"), 1_900_000_000 + 600)
        assertEquals(listOf("Slightly ahead"), ProfileEventFilter.accept(listOf(future, ancient, skewed), setOf(ana.pubkey), now).map { it.name })
    }

    @Test fun newestWinsAndATieGoesToTheLowerId() {
        val a = ProfileRecord("p", "n", eventId = "b".repeat(64), eventCreatedAt = 10, status = ProfileRecord.Status.FOUND, checkedAt = Instant.EPOCH)
        val b = a.copy(eventId = "a".repeat(64))
        assertTrue(b.isNewer(a))
        assertFalse(a.isNewer(b))
        assertTrue(a.copy(eventCreatedAt = 11).isNewer(b))
        assertTrue("a found profile beats a row without an event", a.isNewer(a.copy(eventId = null, eventCreatedAt = null, status = ProfileRecord.Status.MISSING)))
    }

    @Test fun relayFramesAreParsedStrictly() {
        val event = Person(0x23).profile(mapOf("name" to "Ana"), 10)
        val frame = JsonFields.encode(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive("p1"), event.toJsonObject())))
        assertEquals(event, (RelayFrame.parse(frame) as RelayFrame.Event).event)
        assertNotNull(RelayFrame.parse("""["EOSE","p1"]"""))
        assertNotNull(RelayFrame.parse("""["CLOSED","p1","error: rate-limited"]"""))
        assertNull(RelayFrame.parse("""["NOTICE","hello"]"""))
        assertNull(RelayFrame.parse("""["EVENT","p1",{"id":1}]"""))
        assertNull(RelayFrame.parse("garbage"))
        assertNull(RelayFrame.parse("[\"EVENT\",\"p1\",\"${"x".repeat(RelayProfileSource.MAX_FRAME_BYTES)}\"]"))
    }

    @Test fun relayListIsTheWebClientsPublicList() {
        assertEquals(listOf("wss://relay.primal.net", "wss://nos.lol", "wss://purplepag.es"), RelayProfileSource.RELAYS)
        assertTrue(RelayProfileSource(listOf("ws://plain.example.com", "https://x.example.com", "wss://user:pw@x.example.com")).relays.isEmpty())
    }
}

/** The relay query against a local WebSocket server standing in for a public relay. */
class RelayQueryTest {
    private class Relay(val answer: (subscription: String, filter: kotlinx.serialization.json.JsonObject, socket: WebSocket) -> Unit) : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) {}
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            val frame = JsonFields.parse(text)!!.jsonArray
            if (frame[0].jsonPrimitive.content == "REQ") answer(frame[1].jsonPrimitive.content, frame[2].jsonObject, webSocket)
        }
    }

    private fun serve(relay: Relay, block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relay).build())
        server.start()
        try { block(server) } finally { server.close() }
    }

    private fun wsUrl(server: MockWebServer) = server.url("/").toString().replaceFirst("http", "ws")

    @Test fun oneReqForKindZeroUntilEose() = runBlocking {
        val ana = Person(0x31); val bea = Person(0x32)
        val events = listOf(ana.profile(mapOf("name" to "Ana"), 1_800_000_000), bea.profile(mapOf("name" to "Bea"), 1_800_000_000))
        val relay = Relay { sub, filter, socket ->
            assertEquals(listOf(0), filter["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
            assertEquals(4, filter["limit"]!!.jsonPrimitive.content.toInt())
            events.forEach { socket.send(JsonFields.encode(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(sub), it.toJsonObject())))) }
            socket.send("""["EVENT","other-sub",{}]""")
            socket.send("""["EOSE","$sub"]""")
        }
        serve(relay) { server ->
            val source = RelayProfileSource(timeout = 5.seconds)
            val got = runBlocking { source.query(wsUrl(server), listOf(ana.pubkey, bea.pubkey)) }
            assertEquals(events, got)
            val request = server.takeRequest()
            assertNull("no cookies are sent", request.headers["Cookie"])
            assertNull("no credentials are sent", request.headers["Authorization"])
            Thread.sleep(100)
            assertTrue("the subscription is closed after EOSE", relay.received.any { it.startsWith("[\"CLOSE\"") })
        }
    }

    @Test fun oversizedFramesEndTheRelaysAnswer() = runBlocking {
        val relay = Relay { sub, _, socket -> socket.send("[\"EVENT\",\"$sub\",\"${"x".repeat(RelayProfileSource.MAX_FRAME_BYTES)}\"]") }
        serve(relay) { server ->
            assertNull(runBlocking { RelayProfileSource(timeout = 5.seconds).query(wsUrl(server), listOf("a".repeat(64))) })
        }
    }

    @Test fun aSilentRelayTimesOut() = runBlocking {
        val relay = Relay { _, _, _ -> }
        serve(relay) { server ->
            val started = System.nanoTime()
            assertNull(runBlocking { RelayProfileSource(timeout = 1.seconds).query(wsUrl(server), listOf("a".repeat(64))) })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4000)
        }
    }

    @Test fun noReachableRelayIsOfflineNotMissing() = runBlocking {
        val source = RelayProfileSource(listOf("wss://127.0.0.1:1"), timeout = 2.seconds)
        val error = runCatching { source.profiles(listOf("a".repeat(64))) }.exceptionOrNull()
        assertTrue(error is ProfileRelaysUnreachableException)
        assertEquals(emptyList<ProfileRecord>(), source.profiles(emptyList()))
    }
}

class ProfileSyncTest {
    @Test fun freshnessWindows() {
        val now = Instant.ofEpochSecond(2_000_000_000)
        fun record(status: ProfileRecord.Status, checked: Instant) = ProfileRecord("", "", status = status, checkedAt = checked)
        val found = record(ProfileRecord.Status.FOUND, now.minus(ProfileSync.FOUND_TTL).plusSeconds(10))
        val staleFound = record(ProfileRecord.Status.FOUND, now.minus(ProfileSync.FOUND_TTL).minusSeconds(1))
        val missing = record(ProfileRecord.Status.MISSING, now.minus(ProfileSync.MISSING_TTL).plusSeconds(10))
        val staleMissing = record(ProfileRecord.Status.MISSING, now.minus(ProfileSync.MISSING_TTL).minusSeconds(1))
        val future = record(ProfileRecord.Status.FOUND, now.plusSeconds(3600))
        assertFalse(ProfileSync.isDue(found, now, false))
        assertTrue(ProfileSync.isDue(staleFound, now, false))
        assertFalse(ProfileSync.isDue(missing, now, false))
        assertTrue(ProfileSync.isDue(staleMissing, now, false))
        assertTrue("a clock that moved back does not freeze the cache", ProfileSync.isDue(future, now, false))
        assertTrue(ProfileSync.isDue(found, now, true))
        assertTrue(ProfileSync.isDue(null, now, false))
    }

    @Test fun onlyDueKeysAreAskedInBoundedBatches() {
        val now = Instant.ofEpochSecond(2_000_000_000)
        val npubs = (1..120).map { NostrKeys.npub("%064x".format(it))!! }
        val fresh = ProfileRecord(NostrKeys.publicKeyHex(npubs[0])!!, npubs[0], status = ProfileRecord.Status.FOUND, checkedAt = now)
        val batches = ProfileSync.batches(npubs + "not-an-npub", mapOf(npubs[0] to fresh), now, force = false)
        assertTrue(batches.all { it.pubkeys.size <= ProfileSync.BATCH_SIZE })
        assertEquals(119, batches.sumOf { it.pubkeys.size })
        assertEquals(batches.flatMap { it.pubkeys }.sorted(), batches.flatMap { it.pubkeys })
        assertEquals(npubs[1], batches.flatMap { it.checked.entries }.first { it.key == NostrKeys.publicKeyHex(npubs[1]) }.value)
        assertEquals("a forced refresh asks everyone", 120, ProfileSync.batches(npubs, mapOf(npubs[0] to fresh), now, force = true).sumOf { it.pubkeys.size })
        assertTrue(ProfileSync.jobKey("c1", "w1").startsWith(ProfileSync.jobPrefix("c1")))
        assertFalse(ProfileSync.isEnabled("off"))
        assertTrue(ProfileSync.isEnabled(null))
    }
}

class AvatarCachePolicyTest {
    @Test fun namesRedirectsAndRetries() {
        val url = URI("https://img.example.com/a.png")
        assertEquals(64 + 4, AvatarCachePolicy.fileName(url).length)
        assertTrue(AvatarCachePolicy.fileName(url).endsWith(".png"))
        assertEquals(listOf("v0", "tmp"), AvatarCachePolicy.staleEntries(listOf("v0", AvatarCachePolicy.CACHE_VERSION, "tmp")))
        assertEquals(URI("https://cdn.example.com/x.png"), AvatarCachePolicy.followRedirect("https://cdn.example.com/x.png", 3))
        assertNull("a redirect to a local address ends the request", AvatarCachePolicy.followRedirect("https://192.168.0.2/x.png", 1))
        assertNull("at most three redirects", AvatarCachePolicy.followRedirect("https://cdn.example.com/x.png", 4))
        val now = Instant.ofEpochSecond(1000)
        assertEquals(now.plus(AvatarCachePolicy.PERMANENT_FAILURE_BACKOFF), AvatarCachePolicy.retryAfter(AvatarError.NotAnImage, now))
        assertEquals(now.plus(AvatarCachePolicy.TRANSIENT_FAILURE_BACKOFF), AvatarCachePolicy.retryAfter(AvatarError.HttpStatus(503), now))
        assertEquals(now.plus(AvatarCachePolicy.TRANSIENT_FAILURE_BACKOFF), AvatarCachePolicy.retryAfter(java.io.IOException("offline"), now))
        assertTrue(AvatarCachePolicy.acceptsContentType("image/x-icon"))
        assertFalse(AvatarCachePolicy.acceptsContentType("text/html"))
    }

    @Test fun diskCacheIsBoundedOldestFirst() {
        val files = (0 until AvatarCachePolicy.MAX_FILES + 5).map { AvatarCachePolicy.CachedFile("f$it.png", Instant.ofEpochSecond(it.toLong()), 10) }
        assertEquals((0 until 5).map { "f$it.png" }, AvatarCachePolicy.filesToPrune(files.shuffled()))
        val big = listOf(AvatarCachePolicy.CachedFile("old", Instant.ofEpochSecond(1), 10_000_000), AvatarCachePolicy.CachedFile("new", Instant.ofEpochSecond(2), 10_000_000))
        assertEquals(listOf("old"), AvatarCachePolicy.filesToPrune(big))
        assertTrue(AvatarCachePolicy.filesToPrune(files.take(3)).isEmpty())
    }
}
