package studio.otherstuff.ambulando.core.onboarding

import kotlinx.serialization.Serializable
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.WireJson
import java.io.File

/**
 * Everything the wizard must remember across relaunch for exactly one (Tower origin, signer) pair; never read for another
 * identity. The invitation code is kept only until the server has accepted the intent (so an interrupted create replays the
 * identical request) and is removed as soon as the intent id is recorded.
 */
@Serializable
data class OnboardingDraft(
    val version: Int = 1,
    val origin: String,
    val signerNpub: String,
    val workspaceName: String = "",
    val fundingCents: Int = OnboardingFunding.MINIMUM_CENTS,
    val agentOfferId: String? = null,
    val agentDailyCapCents: Int? = null,
    val invite: String? = null,
    /** Operation keys: generated once, reused by every retry and relaunch, rotated only when the operation they name changes. */
    val createKey: String,
    val createDigest: String? = null,
    val checkoutKey: String,
    val intentId: String? = null,
    val intentRevision: Int? = null,
    /** Digest of the choices confirmed on the review screen. Checkout needs it to equal the intent's own choices. */
    val reviewedDigest: String? = null,
    val checkoutOrderId: String? = null,
    /**
     * The payment method this intent is bound to. Durable BEFORE the first request of that method (checkout or quote), and kept until
     * the server itself has resolved the attempt (intent cancelled, or a server-reported expired/cancelled quote). Never changes while
     * an attempt may be payable or its answer was lost.
     */
    val method: OnboardingPaymentOption.Method? = null,
    /** False only for a draft written before [method] existed: [migrated] binds it conservatively once, when loaded. */
    val methodTracked: Boolean = false,
    /** Lightning quote replay key, bound with [quoteIntentId] and [quoteAmountCents]; durable before the quote request. */
    val quoteKey: String? = null,
    val quoteIntentId: String? = null,
    val quoteAmountCents: Int? = null,
    /** Set before the quote request is sent: from then on a reply may have been lost, so only the same key may ask again. */
    val quoteSubmitted: Boolean = false,
    /** Set only once NStack has answered the quote; the invoice is shown after this is durable. */
    val quoteId: String? = null,
) {
    /**
     * A draft from before [method] existed that is past review with an intent has been through the checkout step, whose request may have
     * been sent with its answer lost: it is bound to card rather than risk a second payable attempt. It is freed only by the server
     * (the intent is cancelled once NStack has settled the order), never by the app.
     */
    fun migrated(): OnboardingDraft =
        if (methodTracked) this
        else copy(methodTracked = true, method = method ?: if (checkoutOrderId != null || (intentId != null && reviewedDigest != null)) OnboardingPaymentOption.Method.CARD else null)

    /** Forgets every payment attempt (and rotates the checkout key) once the server has resolved them. */
    fun withoutPaymentAttempt(newCheckoutKey: String): OnboardingDraft = copy(
        checkoutOrderId = null, checkoutKey = newCheckoutKey, method = null,
        quoteKey = null, quoteIntentId = null, quoteAmountCents = null, quoteSubmitted = false, quoteId = null,
    )

    /** True when NStack's canonical attempt names a different method, order or quote than this device holds, or another amount: fail closed. */
    fun conflictsWith(a: OnboardingPaymentAttempt, fundingCents: Int): Boolean {
        if (a.amountCents != null && a.amountCents != fundingCents) return true
        val m = a.method ?: return false
        if (method != null && method != m) return true
        if (m == OnboardingPaymentOption.Method.CARD && checkoutOrderId != null && a.orderId != null && checkoutOrderId != a.orderId) return true
        if (m == OnboardingPaymentOption.Method.LIGHTNING && quoteId != null && a.quoteId != null && quoteId != a.quoteId) return true
        return false
    }

    /** A payment attempt exists that NStack has not resolved: the form's money and the method are fixed. */
    val attemptOpen: Boolean get() = method != null || checkoutOrderId != null || quoteSubmitted || quoteId != null

    val formDigest: String
        get() = digest(OnboardingName.normalized(workspaceName) ?: workspaceName, fundingCents, agentOfferId, if (agentOfferId == null) null else agentDailyCapCents)

    /** The invite is a bearer secret; the generated toString must not carry it into logs. */
    override fun toString() = "OnboardingDraft(origin=$origin, intent=${intentId ?: "none"}, invite=${if (invite == null) "none" else "inv_••••"})"

    companion object {
        fun digest(name: String, fundingCents: Int, agentOfferId: String?, agentDailyCapCents: Int?): String =
            Sha256Hex.of("onboarding-review-v1\n$name\n$fundingCents\n${agentOfferId ?: "-"}\n${agentDailyCapCents?.toString() ?: "-"}")
    }
}

interface OnboardingDraftStore {
    fun load(origin: String, signerNpub: String): OnboardingDraft?

    /** Throws when the draft is not durably written: nothing further may then be sent. */
    fun save(draft: OnboardingDraft)
    fun clear(origin: String, signerNpub: String)
}

/** One small JSON file per (origin, signer), named by hash so neither appears in a file name, written atomically and synced. */
class FileOnboardingDraftStore(private val directory: File) : OnboardingDraftStore {
    private fun file(origin: String, signer: String) = File(directory, Sha256Hex.of("onboarding-draft-v1\n$origin\n$signer") + ".json")

    override fun load(origin: String, signerNpub: String): OnboardingDraft? {
        val bytes = runCatching { file(origin, signerNpub).readBytes() }.getOrNull() ?: return null
        val draft = runCatching { WireJson.decodeFromString(OnboardingDraft.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        return draft.takeIf { it.version == 1 && it.origin == origin && it.signerNpub == signerNpub }
    }

    override fun save(draft: OnboardingDraft) {
        AtomicFiles.write(file(draft.origin, draft.signerNpub), WireJson.encodeToString(OnboardingDraft.serializer(), draft).toByteArray(Charsets.UTF_8))
    }

    override fun clear(origin: String, signerNpub: String) { file(origin, signerNpub).delete() }
}

class MemoryOnboardingDraftStore : OnboardingDraftStore {
    private val drafts = HashMap<String, OnboardingDraft>()
    private fun key(o: String, s: String) = "$o\n$s"
    @Synchronized override fun load(origin: String, signerNpub: String) = drafts[key(origin, signerNpub)]
    @Synchronized override fun save(draft: OnboardingDraft) { drafts[key(draft.origin, draft.signerNpub)] = draft }
    @Synchronized override fun clear(origin: String, signerNpub: String) { drafts.remove(key(origin, signerNpub)) }
}
