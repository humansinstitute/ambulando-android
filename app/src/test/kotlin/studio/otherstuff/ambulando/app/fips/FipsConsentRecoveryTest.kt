package studio.otherstuff.ambulando.app.fips

import android.app.PendingIntent
import android.content.*
import android.content.pm.*
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.transport.*

/** Synthetic provider and public fixture signature only; production pins/resources remain untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [31])
class FipsConsentRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val pkg = "com.wingmanbefree.wingman_app"
    private val uri = Uri.parse("content://$pkg.fips.shared")
    private val signature = Signature(byteArrayOf(1, 2, 3))
    private val npub = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
    private val origin = "http://$npub.fips:4443"
    private lateinit var context: Context
    private lateinit var native: Native
    private lateinit var consent: PendingIntent
    private var requestCount = 0
    private var checkCount = 0
    private var status = "pending"
    private var requestStatus = "consent_required"
    private var checkIntent = false
    private var replyId = "request-1"
    private var replyEndpoint: String? = null
    private var beforeReply: (() -> Unit)? = null

    inner class Native : ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            if (method == "release") return Bundle().apply { putInt("version", 1) }
            if (method == "request") requestCount++ else checkCount++
            beforeReply?.invoke()
            return Bundle().apply {
                putInt("version", 1); putString("requestId", replyId)
                putString("status", if (method == "request") requestStatus else status)
                if (method == "request" || checkIntent) putParcelable("consentIntent", consent)
                replyEndpoint?.let { putString("endpoint", it) }
            }
        }
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, sort: String?): Cursor? = null
        override fun getType(u: Uri): String? = null
        override fun insert(u: Uri, v: ContentValues?): Uri? = null
        override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
    @Before fun setup() {
        val signing = SigningInfo().also { Shadows.shadowOf(it).setSignatures(arrayOf(signature)) }
        val ai = ApplicationInfo().apply { packageName = pkg; uid = 13579 }
        val info = ProviderInfo().apply { packageName = pkg; name = "Native"; authority = uri.authority; exported = true; applicationInfo = ai }
        Shadows.shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; applicationInfo = ai; signingInfo = signing; providers = arrayOf(info)
        })
        val resources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getStringArray(id: Int): Array<String> = if (id == studio.otherstuff.ambulando.app.R.array.fips_shared_providers)
                arrayOf("$pkg|${Sha256Hex.of(signature.toByteArray())}") else app.resources.getStringArray(id)
        }
        context = object : ContextWrapper(app) { override fun getResources() = resources }
        consent = PendingIntent.getActivity(app, 50, Intent("fixture.consent"), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        Shadows.shadowOf(consent).setCreatorPackage(pkg); Shadows.shadowOf(consent).setCreatorUid(ai.uid)
        native = Native(); native.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal(uri.authority, native)
    }
    private suspend fun needsConsent(t: SharedFipsTransport): FipsConsentRequired {
        try { t.send(HttpRequest("POST", "$origin/api/v4/fixture")); fail("No request may pass consent gate") }
        catch (e: FipsConsentRequired) { return e }
        error("Expected consent")
    }
    private suspend fun rejected(t: SharedFipsTransport) {
        assertTrue(runCatching { t.send(HttpRequest("POST", "$origin/api/v4/fixture")) }.isFailure)
    }
    @Test @Config(sdk = [30]) fun android11UsesEnrolledProviderImmutableConstructionAndCreatorBinding() = runBlocking {
        assertTrue(Shadows.shadowOf(consent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(Shadows.shadowOf(consent).flags and PendingIntent.FLAG_ONE_SHOT != 0)
        SharedFipsTransport(context).use { t ->
            needsConsent(t)
            assertSame(consent, needsConsent(t).takeConsent())
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
        }
    }
    @Test @Config(sdk = [30]) fun android11StillRejectsWrongCreatorUid() = runBlocking {
        Shadows.shadowOf(consent).setCreatorUid(24680)
        SharedFipsTransport(context).use { t ->
            try { needsConsent(t); fail("Creator binding is mandatory on Android11") } catch (_: SharedConnectionException) { }
        }
    }
    @Test fun delayedReplyAfterOnStopRetainsUnclaimedIntentUntilNextExplicitResumedTap() = runBlocking {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle get() = registry
        }
        owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        var launches = 0
        SharedFipsTransport(context).use { t ->
            beforeReply = { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED } // ON_PAUSE + ON_STOP
            val delayed = needsConsent(t)
            assertFalse(studio.otherstuff.ambulando.app.ui.onboarding.launchConsentIfResumed(owner.lifecycle, delayed) { launches++ })
            assertEquals(0, launches)
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            assertEquals("Resume itself never launches", 0, launches)
            beforeReply = null
            val explicit = needsConsent(t)
            assertTrue(studio.otherstuff.ambulando.app.ui.onboarding.launchConsentIfResumed(owner.lifecycle, explicit) {
                assertSame(consent, it); launches++
            })
            assertEquals(1, launches); assertEquals(1, requestCount); assertEquals(1, checkCount)
        }
    }
    @Test fun stoppedAtFinalTakeGateDoesNotConsumeTheCapability() = runBlocking {
        SharedFipsTransport(context).use { t ->
            val cached = needsConsent(t)
            assertNull(cached.takeConsent { false })
            assertSame(consent, needsConsent(t).takeConsent { true })
        }
    }
    @Test fun backgroundFirstThenPendingCheckHandsOriginalToExplicitUiWithoutReplay() = runBlocking {
        SharedFipsTransport(context).use { t ->
            needsConsent(t) // background catches; no UI launch
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
            assertSame(consent, needsConsent(t).takeConsent())
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
            assertEquals(1, requestCount); assertEquals(1, checkCount)
            // One-shot already used/back: no relaunch. A fresh explicit attempt requests fresh consent.
            val used = needsConsent(t)
            assertTrue(runCatching { used.takeConsent() }.isFailure)
            replyId = "request-2"
            needsConsent(t)
            assertEquals(2, requestCount)
        }
    }
    @Test fun initiallyPendingCanReceiveItsFirstTrustedIntentOnCheck() = runBlocking {
        SharedFipsTransport(context).use { t ->
            requestStatus = "pending"
            try { needsConsent(t); fail("No capability yet") } catch (_: SharedConnectionException) { }
            status = "consent_required"; checkIntent = true
            assertSame(consent, needsConsent(t).takeConsent())
            assertEquals(1, requestCount); assertEquals(1, checkCount)
        }
    }
    @Test fun consentRequiredCheckWithoutIntentAlsoRecoversOriginal() = runBlocking {
        SharedFipsTransport(context).use { t ->
            needsConsent(t); status = "consent_required"
            assertSame(consent, needsConsent(t).takeConsent())
            assertEquals(1, requestCount)
        }
    }
    @Test fun wrongCreatorUidOrPackageAndMutableIntentNeverReachUi() = runBlocking {
        for (change in listOf("uid", "package", "mutable")) {
            setup()
            when (change) {
                "uid" -> Shadows.shadowOf(consent).setCreatorUid(24680)
                "package" -> Shadows.shadowOf(consent).setCreatorPackage("other")
                else -> {
                    consent = PendingIntent.getActivity(app, 51, Intent("mutable.fixture"), PendingIntent.FLAG_MUTABLE)
                    Shadows.shadowOf(consent).setCreatorPackage(pkg); Shadows.shadowOf(consent).setCreatorUid(13579)
                }
            }
            SharedFipsTransport(context).use { t ->
                try { needsConsent(t); fail("Untrusted capability must not be exposed") } catch (_: SharedConnectionException) { }
                assertNull(Shadows.shadowOf(app).nextStartedActivity)
            }
        }
    }
    @Test fun lateInitialReplyAfterAccountReleaseCannotCacheOrHandoff() = runBlocking {
        SharedFipsTransport(context).use { t ->
            beforeReply = { t.releaseAll() }
            try { needsConsent(t); fail("Late reply must be rejected") } catch (_: SharedConnectionException) { }
            beforeReply = null
            assertSame(consent, needsConsent(t).takeConsent())
            assertEquals(2, requestCount)
        }
    }
    @Test fun readyAndDeniedClearCachedConsent() = runBlocking {
        for (terminal in listOf("ready", "denied", "revoked")) {
            SharedFipsTransport(context).use { t ->
                val stale = needsConsent(t); status = terminal
                rejected(t) // synthetic ready intentionally lacks a valid lifecycle; it must still discard consent
                assertTrue(runCatching { stale.takeConsent() }.isFailure)
                val before = requestCount; needsConsent(t); assertEquals(before + 1, requestCount)
            }
            status = "pending"
        }
    }
    @Test fun releaseAccountCloseProviderAndRevocationInvalidatePreviouslyThrownCapability() = runBlocking {
        for (boundary in listOf("origin", "endpoint", "account", "close", "provider", "revoked")) {
            SharedFipsTransport(context).use { t ->
                val stale = needsConsent(t)
                when (boundary) {
                    "origin" -> t.releaseOrigin(origin)
                    "endpoint" -> t.selectConsentOrigin("https://other.invalid")
                    "account" -> t.releaseAll()
                    "close" -> t.close()
                    "provider" -> runCatching { t.bindProvider(Uri.parse("content://other.provider")) }
                    else -> {
                        val callback = t.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(t) as Messenger
                        callback.send(Message.obtain().apply { what = 1; data = Bundle().apply {
                            putInt("version", 1); putString("status", "revoked"); putString("requestId", "request-1")
                        } })
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                    }
                }
                assertTrue(boundary, runCatching { stale.takeConsent() }.isFailure)
            }
        }
    }
    @Test fun changedReplyBindingAndLateAccountReleaseCannotStoreConsent() = runBlocking {
        for (change in listOf("id", "endpoint", "late")) SharedFipsTransport(context).use { t ->
            val stale = needsConsent(t)
            when (change) {
                "id" -> replyId = "changed"
                "endpoint" -> replyEndpoint = "http://other.invalid"
                else -> beforeReply = { t.releaseAll() }
            }
            rejected(t)
            assertTrue(runCatching { stale.takeConsent() }.isFailure)
            replyId = "request-1"; replyEndpoint = null; beforeReply = null
        }
    }
    @Test fun changedCurrentCertificateIsRejectedBeforeHandoff() = runBlocking {
        SharedFipsTransport(context).use { t ->
            val stale = needsConsent(t)
            Shadows.shadowOf(app.packageManager).getInternalMutablePackageInfo(pkg).signingInfo = SigningInfo().also {
                Shadows.shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(9, 9, 9))))
            }
            val failure = runCatching { stale.takeConsent() }.exceptionOrNull()
            assertEquals(ConnectionIssue.PROVIDER, (failure as SafeConnectionIssue).connectionIssue)
        }
    }
}
