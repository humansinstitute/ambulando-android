package studio.otherstuff.ambulando.app

import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.identitybackup.FileHostedBackupStore
import studio.otherstuff.ambulando.core.identitybackup.FileIdentityOriginStore
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupController
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupRecovery
import studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin
import studio.otherstuff.ambulando.core.onboarding.FileOnboardingDraftStore
import studio.otherstuff.ambulando.core.onboarding.FileTopupDraftStore
import studio.otherstuff.ambulando.core.onboarding.OnboardingController
import studio.otherstuff.ambulando.core.onboarding.TopupController
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File

/**
 * Builds the invited-onboarding and hosted-backup controllers for one (Tower origin, unlocked identity). One controller per pair
 * is kept for the life of the process so a recomposition or rotation joins the operation already in flight instead of starting a
 * second one; the durable draft and the server's own record make a new process resume the same setup.
 *
 * Everything lives in no-backup app storage next to the identity and is removed with it. Card checkout uses a return address
 * from the NStack's own authenticated config (its operator allowlist); with none, card payments are shown as unavailable.
 */
class OnboardingHost(
    private val identity: IdentityService,
    private val transport: HttpTransport,
    private val directory: File,
) {
    private val controllers = HashMap<String, OnboardingController>()
    private val backups = HashMap<String, HostedBackupController>()
    private val topups = HashMap<String, TopupController>()
    private val topupStore by lazy { FileTopupDraftStore(File(directory, "topups")) }
    private val store by lazy { FileOnboardingDraftStore(directory) }
    private val backupStore by lazy { FileHostedBackupStore(File(directory, "backups")) }
    private val originStore by lazy { FileIdentityOriginStore(File(directory, "identity-origin.json")) }

    /** Where the identity on this device came from; unrecorded means existing, which is never gated. */
    fun identityOrigin(npub: String): IdentityOrigin = originStore.originOf(npub, npub)

    /** Written BEFORE a key is generated; throws when the device cannot record it, and then no key is made. */
    fun beginNewIdentity() = originStore.beginNew()
    fun completeNewIdentity(state: IdentityState) { (state as? IdentityState.Unlocked)?.npub?.let { runCatching { originStore.completeNew(it) } } }
    fun abandonNewIdentity() = originStore.abandon()

    @Synchronized fun backup(origin: TowerOrigin, npub: String): HostedBackupController =
        backups.getOrPut(origin.url + "\n" + npub) {
            HostedBackupController(origin, npub, identity, identity::requireSigner, { password, logN -> identity.encryptedBackup(password, logN, npub) }, transport, backupStore)
        }

    @Synchronized fun topup(origin: TowerOrigin, npub: String): TopupController =
        topups.getOrPut(origin.url + "\n" + npub) { TopupController(origin, npub, identity, identity::requireSigner, transport, topupStore) }

    fun recovery() = HostedBackupRecovery(identity, transport, backupStore)

    @Synchronized fun controller(origin: TowerOrigin, npub: String): OnboardingController =
        controllers.getOrPut(origin.url + "\n" + npub) {
            OnboardingController(
                origin, npub, identity, identity::requireSigner, transport, store,
                identityGate = backup(origin, npub).gate(identityOrigin(npub)),
            )
        }

    /** Removing the identity removes pending setups, backup records and the origin mark too (a pending invitation code is a bearer secret). */
    @Synchronized fun wipe() { controllers.clear(); backups.clear(); topups.clear(); directory.deleteRecursively() }
}
