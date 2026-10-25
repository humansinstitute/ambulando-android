package studio.otherstuff.ambulando.core.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.j
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import java.net.URI
import java.util.UUID

// The in-app browser's address rules and the native side of `window.nostr`: per-origin permission, one approval per
// signature, document binding, cancellation and lock. Synthetic key only; no network, no web view.

class BrowserURLPolicyTest {
    private fun decide(s: String, main: Boolean = true) = BrowserURLPolicy.decide(URI(s), main)

    @Test fun pagesAreHttpsOnlyAndOtherAppsNeedAHandOff() {
        assertEquals(BrowserURLPolicy.Decision.Load, decide("https://example.com/a?b=1"))
        assertEquals("window.open() starts here", BrowserURLPolicy.Decision.Load, decide("about:blank"))
        for (refused in listOf("http://example.com", "https://user:pw@example.com", "https://node.fips/", "javascript:alert(1)",
            "file:///etc/hosts", "data:text/html,hi")) {
            assertTrue(refused, decide(refused) is BrowserURLPolicy.Decision.Refuse)
        }
        assertEquals("frames may use data:", BrowserURLPolicy.Decision.Load, decide("data:text/html,hi", main = false))
        assertEquals(BrowserURLPolicy.Decision.Load, decide("blob:https://example.com/1", main = false))
        assertEquals(BrowserURLPolicy.Decision.External(URI("mailto:a@example.com")), decide("mailto:a@example.com"))
        assertEquals(BrowserURLPolicy.Decision.External(URI("lightning:lnbc1")), decide("lightning:lnbc1"))
        assertEquals(BrowserURLPolicy.Decision.External(URI("nostr:npub1abc")), decide("nostr:npub1abc"))
    }

    @Test fun originsAndTypedAddresses() {
        assertEquals("https://example.com", BrowserURLPolicy.origin(URI("https://Example.com/x")))
        assertEquals("https://example.com:8443", BrowserURLPolicy.origin(URI("https://example.com:8443/x")))
        assertEquals("https://example.com", BrowserURLPolicy.origin(URI("https://example.com:443/x")))
        assertNull(BrowserURLPolicy.origin(URI("http://example.com")))
        assertEquals("https://example.com", BrowserURLPolicy.origin("https", "Example.com", 0))
        assertEquals("https://example.com/path", BrowserURLPolicy.typedAddress("example.com/path").toString())
        assertEquals("https://example.com", BrowserURLPolicy.typedAddress(" https://example.com ").toString())
        assertEquals("https://example.com:8443/x", BrowserURLPolicy.typedAddress("example.com:8443/x").toString())
        assertNull(BrowserURLPolicy.typedAddress("http://example.com"))
        assertNull(BrowserURLPolicy.typedAddress("javascript:alert(1)"))
        assertNull(BrowserURLPolicy.typedAddress("not an address"))
        assertNull(BrowserURLPolicy.typedAddress("localhost"))
    }

    @Test fun webContentNavigationStaysOnItsOrigin() {
        val origin = "https://artifacts.example"
        assertEquals(WebContentPolicy.Decision.Allow, WebContentPolicy.decide(URI("https://artifacts.example/next"), origin, false))
        assertEquals("a same-origin new-window link loads in place", WebContentPolicy.Decision.Allow,
            WebContentPolicy.decide(URI("https://artifacts.example/popup"), origin, true))
        assertEquals(WebContentPolicy.Decision.Refuse(URI("https://login.example/redirected")),
            WebContentPolicy.decide(URI("https://login.example/redirected"), origin, false))
        assertEquals(WebContentPolicy.Decision.Refuse(null), WebContentPolicy.decide(URI("http://artifacts.example/"), origin, false))
        assertEquals(WebContentPolicy.Decision.Refuse(null), WebContentPolicy.decide(URI("javascript:alert(1)"), origin, true))
        assertEquals("embedded credentials are never offered", WebContentPolicy.Decision.Refuse(null),
            WebContentPolicy.decide(URI("https://evil.example@artifacts.example.attacker.example/"), origin, false))
        assertEquals(WebContentPolicy.Decision.Refuse(null), WebContentPolicy.decide(null, origin, true))
    }

    @Test fun launchUrlsAreHttpsWithoutCredentialsOrFips() {
        assertEquals("https://time.example", WebContentPolicy.launchURL("https://time.example").toString())
        assertNull(WebContentPolicy.launchURL("https://user:pw@x.example/"))
        assertNull(WebContentPolicy.launchURL("https://npub1abc.fips/"))
        assertNull(WebContentPolicy.launchURL("javascript:alert(1)"))
        assertNull(WebContentPolicy.launchURL("http://insecure.example"))
        val origin = WebContentPolicy.origin(URI("https://Artifacts.example/app/"))!!
        assertEquals("https://artifacts.example", origin)
        assertTrue(WebContentPolicy.allows(URI("https://artifacts.example/other"), origin))
        assertFalse(WebContentPolicy.allows(URI("https://evil.example/"), origin))
        assertFalse("no scheme downgrade", WebContentPolicy.allows(URI("http://artifacts.example/"), origin))
    }
}

class NIP07RequestTest {
    private val now = 1_800_000_000L

    @Test fun eventsAreParsedAndBounded() {
        val parsed = NIP07Method.parse("signEvent", j(mapOf("kind" to 1, "content" to "hi", "tags" to listOf(listOf("t", "x")), "created_at" to 1_799_999_000)), now)
        val sign = (parsed as NIP07Result.Success).value as NIP07Method.SignEvent
        assertEquals(NostrEventTemplate(1, listOf(listOf("t", "x")), "hi", 1_799_999_000), sign.template)
        assertNull(sign.requestedPubkey)
        val d = ((NIP07Method.parse("signEvent", j(mapOf("kind" to 27235)), now) as NIP07Result.Success).value as NIP07Method.SignEvent).template
        assertEquals("created_at defaults to now", now, d.createdAt)
        assertEquals("", d.content)
        val bad: List<Map<String, Any?>> = listOf(
            emptyMap(), mapOf("kind" to -1), mapOf("kind" to 70000), mapOf("kind" to true), mapOf("kind" to 1.5), mapOf("kind" to "1"),
            mapOf("kind" to 1, "content" to 5), mapOf("kind" to 1, "tags" to "x"), mapOf("kind" to 1, "tags" to listOf(listOf(1))),
            mapOf("kind" to 1, "content" to "a".repeat(NIP07Method.MAX_CONTENT_BYTES + 1)),
            mapOf("kind" to 1, "tags" to List(NIP07Method.MAX_TAGS + 1) { listOf("t") }),
            mapOf("kind" to 1, "created_at" to 9_999_999_999L), mapOf("kind" to 1, "pubkey" to 3),
        )
        for (params in bad) assertTrue("accepted ${params.keys}", NIP07Method.parse("signEvent", j(params), now) is NIP07Result.Failure)
        assertTrue(NIP07Method.parse("signEvent", j("x"), now) is NIP07Result.Failure)
        assertEquals(NIP07Result.Success(NIP07Method.Unsupported("nip44.encrypt")), NIP07Method.parse("nip44.encrypt", null, now))
        assertEquals(NIP07Result.Success(NIP07Method.GetRelays), NIP07Method.parse("getRelays", null, now))
    }

    @Test fun sitesRoundTripAsStoredState() {
        val sites = mapOf("https://a.example" to NIP07SitePermission.ALLOWED, "https://b.example" to NIP07SitePermission.DENIED)
        val text = NIP07Sites.encode(sites)
        assertEquals("""{"https://a.example":"allowed","https://b.example":"denied"}""", text)
        assertEquals(sites, NIP07Sites.decode(text))
        assertEquals(mapOf("https://a.example" to NIP07SitePermission.ALLOWED), NIP07Sites.decode("""{"https://a.example":"allowed","https://c.example":"maybe"}"""))
        assertEquals(emptyMap<String, NIP07SitePermission>(), NIP07Sites.decode("not json"))
        assertEquals("browser.sites.v1", NIP07Sites.STATE_KEY)
    }
}

class NIP07BrokerTest {
    private val secret = ByteArray(32) { 0x31 }
    private val pubkey = NostrKeys.publicKeyHex(secret)
    private var stored: Map<String, NIP07SitePermission> = emptyMap()
    private val origin = "https://app.example.com"
    private val tab: UUID = UUID.randomUUID()
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun makeBroker(sites: Map<String, NIP07SitePermission> = emptyMap()) = NIP07Broker(
        locked = false, sites = sites,
        publicKey = { pubkey },
        signer = { NostrKeys.sign(it, secret) },
        persist = { stored = it },
    )

    private fun ctx(document: Long = 1, origin: String = this.origin) = NIP07Context(tab, document, origin)

    /** Starts a request and waits until it either finished or shows a prompt. */
    private suspend fun start(broker: NIP07Broker, method: String, params: JsonElement? = null, document: Long = 1, origin: String = this.origin,
                              current: () -> Boolean = { true }): Deferred<NIP07Result<NIP07Reply>> {
        val before = broker.prompts.size
        val task = scope.async { broker.handle(method, params, ctx(document, origin), current) }
        repeat(400) { if (broker.prompts.size == before && !task.isCompleted) delay(2) }
        return task
    }

    @Test fun publicKeyNeedsTheSitesPermissionAndRememberingIsPerOrigin() = runBlocking {
        val broker = makeBroker()
        val first = start(broker, "getPublicKey")
        val prompt = broker.current!!
        assertEquals(NIP07Prompt.Kind.Connect, prompt.kind)
        assertEquals(origin, prompt.context.origin)
        assertNotNull(prompt.npub)
        broker.resolve(prompt.id, NIP07Decision.Allow(remember = true))
        assertEquals(NIP07Result.Success(NIP07Reply.PublicKey(pubkey)), first.await())
        assertEquals(NIP07SitePermission.ALLOWED, stored[origin])

        // Remembered: no prompt for the same origin; another origin asks again.
        assertEquals(NIP07Result.Success(NIP07Reply.PublicKey(pubkey)), broker.handle("getPublicKey", null, ctx()) { true })
        val other = start(broker, "getPublicKey", origin = "https://other.example.com")
        broker.resolve(broker.current!!.id, NIP07Decision.Deny(remember = true))
        assertEquals(NIP07Result.Failure(NIP07Error.Denied), other.await())
        assertEquals(NIP07SitePermission.DENIED, stored["https://other.example.com"])
        // A denied site cannot ask to sign either, and forgetting it lets it ask again.
        assertEquals(NIP07Result.Failure(NIP07Error.Denied), broker.handle("signEvent", j(mapOf("kind" to 1)), ctx(origin = "https://other.example.com")) { true })
        broker.forget("https://other.example.com")
        assertNull(stored["https://other.example.com"])
    }

    @Test fun everySignatureIsApprovedInASheetNamingTheSite() = runBlocking {
        val broker = makeBroker(mapOf(origin to NIP07SitePermission.ALLOWED))
        val params = j(mapOf("kind" to 1, "content" to "hello", "tags" to listOf(listOf("t", "x")), "created_at" to 1_800_000_000))
        repeat(2) {
            val task = start(broker, "signEvent", params)
            val prompt = broker.current!!
            assertEquals(origin, prompt.context.origin)
            assertFalse("ordinary kinds are never allowed in advance", prompt.offersSessionGrant)
            assertEquals("hello", (prompt.kind as NIP07Prompt.Kind.Sign).event.content)
            broker.resolve(prompt.id, NIP07Decision.Allow(remember = true))
            val event = ((task.await() as NIP07Result.Success).value as NIP07Reply.Event).event
            assertEquals(pubkey, event.pubkey)
            assertEquals(1, event.kind)
            assertEquals(listOf(listOf("t", "x")), event.tags)
            assertTrue(SignatureVerifier.verify(event))
            val js = NIP07Reply.Event(event).javaScriptValue.jsonObject
            assertEquals(event.sig, js["sig"]!!.jsonPrimitive.content)
            assertEquals(event.createdAt.toString(), js["created_at"]!!.jsonPrimitive.content)
        }
        // Rejected: the page's promise fails.
        val rejected = start(broker, "signEvent", params)
        broker.resolve(broker.current!!.id, NIP07Decision.Deny(remember = false))
        assertEquals(NIP07Result.Failure(NIP07Error.Denied), rejected.await())
        // An event claiming another author is refused without a prompt.
        assertEquals(NIP07Result.Failure(NIP07Error.Invalid("the event's pubkey is not this identity")),
            broker.handle("signEvent", j(mapOf("kind" to 1, "pubkey" to "ab".repeat(32))), ctx()) { true })
        assertNull(broker.current)
    }

    @Test fun authenticationKindsMayBeAllowedUntilLock() = runBlocking {
        val broker = makeBroker()
        val auth = j(mapOf("kind" to 27235, "tags" to listOf(listOf("u", "https://app.example.com/api"), listOf("method", "GET"))))
        val first = start(broker, "signEvent", auth)
        val prompt = broker.current!!
        assertTrue(prompt.offersSessionGrant)
        broker.resolve(prompt.id, NIP07Decision.Allow(remember = true))
        assertTrue((first.await() as NIP07Result.Success).value is NIP07Reply.Event)
        // Same site and kind: signed without asking. Another site still asks.
        assertTrue((broker.handle("signEvent", auth, ctx()) { true } as NIP07Result.Success).value is NIP07Reply.Event)
        val other = start(broker, "signEvent", auth, origin = "https://other.example.com")
        assertNotNull(broker.current)
        broker.resolve(broker.current!!.id, NIP07Decision.Deny(remember = false))
        other.await()
        // Locking forgets the grant.
        broker.setLocked(true)
        broker.setLocked(false)
        val again = start(broker, "signEvent", auth)
        assertNotNull("asks again after a lock", broker.current)
        broker.resolve(broker.current!!.id, NIP07Decision.Deny(remember = false))
        again.await()
        Unit
    }

    @Test fun lockCancelsWaitingRequestsAndRefusesNewOnes() = runBlocking {
        val broker = makeBroker(mapOf(origin to NIP07SitePermission.ALLOWED))
        val waiting = start(broker, "signEvent", j(mapOf("kind" to 1)))
        assertNotNull(broker.current)
        broker.setLocked(true)
        assertNull("the sheet goes away", broker.current)
        assertEquals(NIP07Result.Failure(NIP07Error.Locked), waiting.await())
        assertEquals(NIP07Result.Failure(NIP07Error.Locked), broker.handle("getPublicKey", null, ctx()) { true })
        assertEquals(NIP07Result.Failure(NIP07Error.Locked), broker.handle("signEvent", j(mapOf("kind" to 1)), ctx()) { true })
    }

    @Test fun navigationCancelsTheOldDocumentsRequestsAndApprovalIsRecheckedBeforeSigning() = runBlocking {
        val broker = makeBroker(mapOf(origin to NIP07SitePermission.ALLOWED))
        // The tab moves on to document 2: what document 1 asked is cancelled.
        val currentDocument = java.util.concurrent.atomic.AtomicLong(1)
        val old = start(broker, "signEvent", j(mapOf("kind" to 1)), document = 1) { currentDocument.get() == 1L }
        assertNotNull(broker.current)
        currentDocument.set(2)
        broker.documentChanged(tab, 2)
        assertNull(broker.current)
        assertEquals(NIP07Result.Failure(NIP07Error.PageChanged), old.await())

        // The page changes while the sheet is open and the user still taps Sign: nothing is signed.
        val stillHere = java.util.concurrent.atomic.AtomicBoolean(true)
        val raced = start(broker, "signEvent", j(mapOf("kind" to 1)), document = 2) { stillHere.get() }
        stillHere.set(false)
        broker.resolve(broker.current!!.id, NIP07Decision.Allow(remember = false))
        assertEquals(NIP07Result.Failure(NIP07Error.PageChanged), raced.await())

        // A request from a document that is no longer shown is refused outright; closing the tab cancels its prompts.
        assertEquals(NIP07Result.Failure(NIP07Error.PageChanged), broker.handle("getPublicKey", null, ctx()) { false })
        val closing = start(broker, "signEvent", j(mapOf("kind" to 1)), document = 2)
        broker.tabClosed(tab)
        assertEquals(NIP07Result.Failure(NIP07Error.Closed), closing.await())
    }

    @Test fun waitingRequestsPerTabAreBoundedAndUnsupportedMethodsReject() = runBlocking {
        val broker = makeBroker(mapOf(origin to NIP07SitePermission.ALLOWED))
        val tasks = (0 until NIP07Broker.MAX_PROMPTS_PER_TAB).map { start(broker, "signEvent", j(mapOf("kind" to 1))) }
        assertEquals(NIP07Broker.MAX_PROMPTS_PER_TAB, broker.prompts.size)
        assertEquals(NIP07Result.Failure(NIP07Error.Busy), broker.handle("signEvent", j(mapOf("kind" to 1)), ctx()) { true })
        broker.tabClosed(tab)
        tasks.forEach { it.await() }
        assertEquals(NIP07Result.Failure(NIP07Error.Unsupported("nip04.encrypt")), broker.handle("nip04.encrypt", null, ctx()) { true })
        assertEquals(NIP07Result.Failure(NIP07Error.Unsupported("nip44.decrypt")), broker.handle("nip44.decrypt", null, ctx()) { true })
        val relays = broker.handle("getRelays", null, ctx()) { true }
        assertEquals(NIP07Result.Success(NIP07Reply.Relays), relays)
        assertEquals(JsonObject(emptyMap()), NIP07Reply.Relays.javaScriptValue)
        broker.reset()
        assertEquals(emptyMap<String, NIP07SitePermission>(), stored)
    }
}
