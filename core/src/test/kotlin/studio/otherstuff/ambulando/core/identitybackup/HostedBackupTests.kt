package studio.otherstuff.ambulando.core.identitybackup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.crypto.Nip49
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.onboarding.FakeOnboardingTower
import studio.otherstuff.ambulando.core.support.MemorySecretStore
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File
import java.io.IOException

private const val PASSWORD = "correct horse battery staple 42"

class FlakyBackupStore(val inner: HostedBackupStore = MemoryHostedBackupStore()) : HostedBackupStore {
    var failWhen: (HostedBackupRecord) -> Boolean = { false }
    override fun load(origin: String, npub: String) = inner.load(origin, npub)
    override fun save(record: HostedBackupRecord) { if (failWhen(record)) throw IOException("disk full"); inner.save(record) }
    override fun clear(origin: String, npub: String) = inner.clear(origin, npub)
}

private class Rig(secretByte: Int = 0x31, val tower: FakeOnboardingTower = FakeOnboardingTower(), val store: FlakyBackupStore = FlakyBackupStore()) {
    val secret = ByteArray(32) { secretByte.toByte() }
    val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!
    var active = npub
    val origin = TowerOrigin(tower.origin)
    var encryptions = 0
    fun controller(store: HostedBackupStore = this.store) = HostedBackupController(
        origin, npub, { NostrKeys.sign(it, secret) }, { expected -> if (active != expected) throw IdentityError.SignerMismatch },
        { password, logN -> encryptions++; Nip49.encrypt(secret, password, logN) }, tower, store,
    )
    fun puts() = tower.log.filter { it.method == "PUT" }
}

class HostedBackupTests {
    // MARK: local validation

    @Test fun usernamesAreNormalisedLikeTheServerAndUnsafeOnesRefused() {
        assertEquals("alice_1", BackupUsername.normalize("  Alice_1 "))
        assertEquals("abc", BackupUsername.normalize("ＡＢＣ"))                 // NFKC full-width letters
        listOf("ab", "a".repeat(33), "-abc", "abc-", "ab c", "ab/c", "admin", "ROOT", "café", "ab​c", "").forEach { assertNull(it, BackupUsername.normalize(it)) }
    }

    @Test fun passwordPolicyRefusesShortSimpleAndUsernameDerivedAndMismatch() {
        assertNull(BackupPassword.problem(PASSWORD, PASSWORD, "alice"))
        assertEquals(PasswordProblem.MISMATCH, BackupPassword.problem(PASSWORD, PASSWORD + "x", null))
        assertEquals(PasswordProblem.TOO_SHORT, BackupPassword.problem("short one", "short one", null))
        assertEquals("15 characters is still short", PasswordProblem.TOO_SHORT, BackupPassword.problem("abcdefghij12345", "abcdefghij12345", null))
        assertNull("16 distinct characters pass", BackupPassword.problem("abcdefghij123456", "abcdefghij123456", null))
        assertEquals(PasswordProblem.TOO_SIMPLE, BackupPassword.problem("1".repeat(20), "1".repeat(20), null))
        assertEquals(PasswordProblem.TOO_SIMPLE, BackupPassword.problem("a".repeat(30), "a".repeat(30), null))
        assertEquals(PasswordProblem.CONTAINS_USERNAME, BackupPassword.problem("my-alice-secret-phrase", "my-alice-secret-phrase", "alice"))
    }

    @Test fun ciphertextShapeIsCheckedBeforeBech32AndBoundsLogN() {
        val key = ByteArray(32) { 0x31 }
        assertTrue(BackupCiphertext.isWellFormed(Nip49.encrypt(key, PASSWORD, 16)))
        assertTrue(BackupCiphertext.isWellFormed(Nip49.encrypt(key, PASSWORD, 17)))
        assertFalse("below the server floor", BackupCiphertext.isWellFormed(Nip49.encrypt(key, PASSWORD, 12)))
        assertFalse(BackupCiphertext.isWellFormed("ncryptsec1" + "q".repeat(5000)))
        assertFalse(BackupCiphertext.isWellFormed(Nip49.encrypt(key, PASSWORD, 16).uppercase()))
        val good = Nip49.encrypt(key, PASSWORD, 16)
        assertFalse("a changed final character breaks the checksum", BackupCiphertext.isWellFormed(good.dropLast(1) + (if (good.last() == 'q') 'p' else 'q')))
    }

    // MARK: save

    @Test fun savesEncryptedCopyConfirmsItByReadBackAndSendsNoPasswordOrKey() = runBlocking {
        val r = Rig(); val c = r.controller()
        val s = c.save("Alice", PASSWORD, PASSWORD)
        assertEquals(HostedBackupStage.CONFIRMED, s.stage); assertEquals("alice", s.username); assertEquals("1", s.revision); assertNull(s.failure)
        val hosted = r.tower.backups.getValue("alice")
        assertEquals(NostrKeys.npub(NostrKeys.publicKeyHex(r.secret)), hosted.owner)
        val decrypted = Nip49.decrypt(hosted.ncryptsec, PASSWORD)
        assertEquals(r.secret.toList(), decrypted.secret.toList())
        // Owner-signed write and read-back; the public read is unsigned; nothing sensitive in any URL, body or header.
        val put = r.puts().single()
        assertEquals(NostrKeys.publicKeyHex(r.secret), put.signer)
        assertTrue(put.body.contains("\"expected_revision\":\"0\""))
        assertEquals(1, r.tower.log.count { it.path == "/api/v4/identity-backup/alice" && it.method == "GET" && it.signer == null })
        assertTrue(r.tower.log.none { it.url.contains(PASSWORD) || it.body.contains(PASSWORD) || (it.authorization ?: "").contains(PASSWORD) })
        val hex = r.secret.joinToString("") { "%02x".format(it) }
        assertTrue(r.tower.log.none { it.body.contains(hex) || it.url.contains(hex) })
        assertEquals(true, r.store.inner.load(r.origin.url, r.npub)?.confirmed)
        assertTrue(c.confirmedOnServer())
    }

    @Test fun nothingIsSentWhenTheCiphertextCannotBeSavedFirst() = runBlocking {
        val r = Rig(); r.store.failWhen = { true }
        val s = r.controller().save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.PERSISTENCE, s.failure!!.kind)
        assertEquals(HostedBackupStage.NONE, s.stage)
        assertTrue(r.tower.backups.isEmpty())
        assertEquals(0, r.puts().size)
    }

    @Test fun invalidInputsSendNothing() = runBlocking {
        val r = Rig(); val c = r.controller()
        assertEquals(BackupFailure.Kind.INVALID_USERNAME, c.save("a", PASSWORD, PASSWORD).failure!!.kind)
        assertEquals(BackupFailure.Kind.WEAK_PASSWORD, c.save("alice", "short", "short").failure!!.kind)
        assertEquals(PasswordProblem.MISMATCH, c.save("alice", PASSWORD, PASSWORD + "!").failure!!.problem)
        assertEquals(0, r.tower.log.size); assertEquals(0, r.encryptions)
    }

    @Test fun aLostWriteAnswerIsReconciledWithoutASecondWriteAndConfirmedOnlyAfterReadBack() = runBlocking {
        val r = Rig(); val c = r.controller()
        r.tower.loseAnswer("/identity-backup/alice")
        val first = c.save("alice", PASSWORD, PASSWORD)
        assertEquals("answer lost: the copy must not be reported done", HostedBackupStage.PENDING, first.stage)
        assertEquals(BackupFailure.Kind.NETWORK, first.failure!!.kind)
        assertEquals(false, r.store.inner.load(r.origin.url, r.npub)!!.confirmed)
        // The write did land on the server; retry asks first, finds it, verifies it, and sends no second write.
        val second = c.save("alice", PASSWORD, PASSWORD)
        assertEquals(HostedBackupStage.CONFIRMED, second.stage)
        assertEquals(1, r.puts().size)
    }

    @Test fun aWriteThatNeverArrivedIsReplayedWithTheIdenticalCiphertext() = runBlocking {
        val r = Rig(); val c = r.controller()
        r.tower.failBefore("/identity-backup/alice")
        assertEquals(BackupFailure.Kind.NETWORK, c.save("alice", PASSWORD, PASSWORD).failure!!.kind)
        val pending = r.store.inner.load(r.origin.url, r.npub)!!
        assertTrue(r.tower.backups.isEmpty())
        // Relaunch: a new controller reads the durable record and resumes, reusing the same ciphertext.
        val relaunched = r.controller()
        assertEquals(HostedBackupStage.PENDING, relaunched.snapshot().stage)
        assertEquals(HostedBackupStage.CONFIRMED, relaunched.save("alice", PASSWORD, PASSWORD).stage)
        assertEquals(pending.ncryptsec, r.tower.backups.getValue("alice").ncryptsec)
        assertEquals(1, r.encryptions)
    }

    @Test fun aFailedConfirmationWriteLeavesTheCopyUncountedAndRecoversOnRetry() = runBlocking {
        val r = Rig(); val c = r.controller()
        r.store.failWhen = { it.confirmed }
        val s = c.save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.PERSISTENCE, s.failure!!.kind)
        assertEquals(HostedBackupStage.PENDING, s.stage)            // memory follows disk, never ahead of it
        assertFalse(c.confirmedOnServer())                          // no durable confirmation => the gate stays shut
        r.store.failWhen = { false }
        assertEquals(HostedBackupStage.CONFIRMED, c.save("alice", PASSWORD, PASSWORD).stage)
        assertEquals(1, r.puts().size)
        assertTrue(c.confirmedOnServer())
    }

    @Test fun aHostedCopyThatDoesNotOpenWithThePasswordIsNeverCounted() = runBlocking {
        val r = Rig(); val c = r.controller()
        // The server hands back a different (valid-looking) ciphertext than the one uploaded.
        val other = Nip49.encrypt(ByteArray(32) { 0x55 }, PASSWORD, 16)
        r.tower.beforeBackupRead = { r.tower.backups["alice"]?.ncryptsec = other }
        val s = c.save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.VERIFY_FAILED, s.failure!!.kind)
        assertEquals(HostedBackupStage.PENDING, s.stage)
        assertFalse(c.confirmedOnServer())
    }

    @Test fun aUsernameOwnedByAnotherKeyIsRefusedAndNeverCountedOrTakenOver() = runBlocking {
        val tower = FakeOnboardingTower()
        val a = Rig(0x31, tower); val b = Rig(0x32, tower)
        assertEquals(HostedBackupStage.CONFIRMED, a.controller().save("alice", PASSWORD, PASSWORD).stage)
        val s = b.controller().save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.USERNAME_UNAVAILABLE, s.failure!!.kind)
        assertEquals(HostedBackupStage.PENDING, s.stage)
        assertEquals(a.npub, tower.backups.getValue("alice").owner)
        // Choosing another name works with the same pending state.
        assertEquals(HostedBackupStage.CONFIRMED, b.controller().save("bob-b", PASSWORD, PASSWORD).stage)
    }

    @Test fun anIdentityAlreadyBackedUpUnderAnotherNameIsReportedNotDuplicated() = runBlocking {
        val r = Rig(); val c = r.controller()
        c.save("alice", PASSWORD, PASSWORD)
        val s = r.controller(MemoryHostedBackupStore()).save("alice2", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.ALREADY_BACKED_UP, s.failure!!.kind)
        assertEquals(1, r.tower.backups.size)
    }

    @Test fun anIdentitySwapBeforeSigningSendsNothing() = runBlocking {
        val r = Rig(); r.active = TestKeys.syntheticNpub(0x77)
        val s = r.controller().save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.IDENTITY_MISMATCH, s.failure!!.kind)
        assertEquals(0, r.tower.log.size)
    }

    @Test fun throttlingAndMissingBackendAreFriendlyAndUnconfirmed() = runBlocking {
        val r = Rig(); r.tower.backupThrottled = true
        val s = r.controller().save("alice", PASSWORD, PASSWORD)
        assertTrue(s.stage != HostedBackupStage.CONFIRMED)
        assertTrue(s.failure != null)
        val gone = Rig(tower = FakeOnboardingTower().also { it.routerMissing = true })
        assertEquals(BackupFailure.Kind.BACKEND_MISSING, gone.controller().save("alice", PASSWORD, PASSWORD).failure!!.kind)
    }

    @Test fun theGateAsksTheServerAndNeverTrustsALocalFlagAlone() = runBlocking {
        val r = Rig(); val c = r.controller()
        c.save("alice", PASSWORD, PASSWORD)
        assertTrue(c.confirmedOnServer())
        r.tower.backups.clear()
        assertFalse(c.confirmedOnServer())
        r.tower.routerMissing = true
        assertFalse(c.confirmedOnServer())
    }

    @Test fun theGateAlsoRequiresTheHostedCiphertextToStillMatchTheProvenOne() = runBlocking {
        val r = Rig(); val c = r.controller()
        c.save("alice", PASSWORD, PASSWORD)
        val proven = r.tower.backups.getValue("alice").ncryptsec
        r.tower.backups.getValue("alice").ncryptsec = Nip49.encrypt(ByteArray(32) { 0x55 }, PASSWORD, 16)   // /me still answers, ciphertext differs
        assertFalse(c.confirmedOnServer())
        r.tower.backups.getValue("alice").ncryptsec = proven
        assertTrue(c.confirmedOnServer())
    }

    @Test fun aConfirmationIsBoundToTheOriginAndIdentityItWasMadeFor() = runBlocking {
        val r = Rig(); r.controller().save("alice", PASSWORD, PASSWORD)
        // The same record store, another NStack origin or another identity: nothing is confirmed there.
        val other = HostedBackupController(TowerOrigin("https://other.example.com"), r.npub, { NostrKeys.sign(it, r.secret) }, {}, { p, n -> Nip49.encrypt(r.secret, p, n) }, r.tower, r.store)
        assertEquals(HostedBackupStage.NONE, other.snapshot().stage)
        assertFalse(other.confirmedOnServer())
        val stranger = Rig(0x61, r.tower, r.store)
        assertEquals(HostedBackupStage.NONE, stranger.controller().snapshot().stage)
    }

    @Test fun anIdentitySwapDuringTheReadBackNeverConfirms() = runBlocking {
        val r = Rig()
        r.tower.beforeBackupRead = { r.active = TestKeys.syntheticNpub(0x77) }      // after the final signed /me, before the decrypt proof is accepted
        val s = r.controller().save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.IDENTITY_MISMATCH, s.failure!!.kind)
        assertTrue(s.stage != HostedBackupStage.CONFIRMED)
        assertEquals(false, r.store.inner.load(r.origin.url, r.npub)?.confirmed ?: false)
    }

    @Test fun anIdentitySwapDuringEncryptionStagesNothingAndSendsNothing() = runBlocking {
        val r = Rig()
        val c = HostedBackupController(r.origin, r.npub, { NostrKeys.sign(it, r.secret) }, { expected -> if (r.active != expected) throw IdentityError.SignerMismatch },
            { p, n -> Nip49.encrypt(r.secret, p, n).also { r.active = TestKeys.syntheticNpub(0x77) } }, r.tower, r.store)
        val s = c.save("alice", PASSWORD, PASSWORD)
        assertEquals(BackupFailure.Kind.IDENTITY_MISMATCH, s.failure!!.kind)
        assertNull("nothing was staged for the wrong identity", r.store.inner.load(r.origin.url, r.npub))
        assertEquals("only the read-only /me before encrypting", 0, r.puts().size)
    }

    @Test fun switchingOriginsNeverReusesAnotherOriginsConfirmationOrCiphertext() = runBlocking {
        val a = Rig(); a.controller().save("alice", PASSWORD, PASSWORD)
        val tower2 = FakeOnboardingTower(origin = "https://other.example.com")
        val b = HostedBackupController(TowerOrigin(tower2.origin), a.npub, { NostrKeys.sign(it, a.secret) }, {}, { p, n -> Nip49.encrypt(a.secret, p, n) }, tower2, a.store)
        assertEquals(HostedBackupStage.NONE, b.snapshot().stage)
        assertFalse(b.gate(IdentityOrigin.NEW).locallyConfirmed())
        assertFalse(b.confirmedOnServer())
        assertEquals(0, tower2.log.size)
        assertEquals("a fresh ciphertext is made for the second origin", HostedBackupStage.CONFIRMED, b.save("alice", PASSWORD, PASSWORD).stage)
        assertTrue(a.store.inner.load(a.origin.url, a.npub)!!.ncryptsec != a.store.inner.load("https://other.example.com", a.npub)!!.ncryptsec)
    }

    @Test fun recoveryAcceptsAnOlderShortPasswordButCreationRefusesIt() = runBlocking {
        val tower = FakeOnboardingTower()
        val secret = ByteArray(32) { 0x31 }
        val short = "tiny pw"
        tower.backups["alice"] = FakeOnboardingTower.Backup("alice", "someone", Nip49.encrypt(secret, short, 16), 1)
        val fresh = service()
        val state = HostedBackupRecovery(fresh, tower, MemoryHostedBackupStore()).recover(TowerOrigin(tower.origin), "alice", short, "135790")
        assertTrue(state is IdentityState.Unlocked)
        assertEquals(PasswordProblem.TOO_SHORT, BackupPassword.problem(short, short, null))
    }

    // MARK: vault integration and recovery

    private fun service(dir: File = tempDir()) = IdentityService(File(dir, "vault.json"), MemorySecretStore())

    @Test fun theVaultEncryptsAndProvesTheBackupWithoutExposingTheKey() = runBlocking {
        val vault = service(); vault.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val text = vault.encryptedBackup(PASSWORD, 16, TestKeys.NPUB)
        assertTrue(BackupCiphertext.isWellFormed(text))
        val back = Nip49.decrypt(text, PASSWORD)
        assertEquals(TestKeys.PUBKEY_HEX, NostrKeys.publicKeyHex(back.secret))
        try { vault.encryptedBackup(PASSWORD, 16, TestKeys.syntheticNpub(0x44)); fail() } catch (e: IdentityError) { assertEquals(IdentityError.SignerMismatch, e) }
        vault.lock()
        try { vault.encryptedBackup(PASSWORD, 16, TestKeys.NPUB); fail() } catch (e: IdentityError) { assertEquals(IdentityError.Locked, e) }
    }

    @Test fun recoveryRestoresTheSameIdentityFromUsernameAndPasswordAlone() = runBlocking {
        val tower = FakeOnboardingTower()
        val owner = service(); owner.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val origin = TowerOrigin(tower.origin)
        HostedBackupController(origin, TestKeys.NPUB, owner, owner::requireSigner, { p, n -> owner.encryptedBackup(p, n, TestKeys.NPUB) }, tower, MemoryHostedBackupStore())
            .save("alice", PASSWORD, PASSWORD)
        tower.log.clear()

        val fresh = service(); val store = MemoryHostedBackupStore()
        val state = HostedBackupRecovery(fresh, tower, store).recover(origin, "Alice", PASSWORD, "135790")
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), state)
        assertTrue("only unsigned reads, nothing about the password or key", tower.log.all { it.method == "GET" && it.signer == null && !it.url.contains(PASSWORD) })
        assertEquals(true, store.load(origin.url, TestKeys.NPUB)?.confirmed)
        assertEquals(TestKeys.PUBKEY_HEX, fresh.sign(studio.otherstuff.ambulando.core.nostr.NostrEventTemplate(1, emptyList(), "x")).pubkey)
    }

    @Test fun recoveryWithTheWrongPasswordOrAnExistingIdentityChangesNothing() = runBlocking {
        val tower = FakeOnboardingTower()
        val owner = service(); owner.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val origin = TowerOrigin(tower.origin)
        HostedBackupController(origin, TestKeys.NPUB, owner, owner::requireSigner, { p, n -> owner.encryptedBackup(p, n, TestKeys.NPUB) }, tower, MemoryHostedBackupStore())
            .save("alice", PASSWORD, PASSWORD)

        val fresh = service()
        try { HostedBackupRecovery(fresh, tower, MemoryHostedBackupStore()).recover(origin, "alice", PASSWORD + "x", "135790"); fail() }
        catch (e: BackupFailure) { assertEquals(BackupFailure.Kind.WRONG_PASSWORD, e.kind) }
        assertEquals(IdentityState.None, fresh.state())
        try { HostedBackupRecovery(fresh, tower, MemoryHostedBackupStore()).recover(origin, "nobody", PASSWORD, "135790"); fail() }
        catch (e: BackupFailure) { assertEquals(BackupFailure.Kind.NOT_FOUND, e.kind) }

        // A device that already has an identity is never replaced.
        try { HostedBackupRecovery(owner, tower, MemoryHostedBackupStore()).recover(origin, "alice", PASSWORD, "135790"); fail() }
        catch (e: IdentityError) { assertEquals(IdentityError.Exists, e) }
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), owner.state())
    }

    @Test fun recoveryRefusesATamperedOrOverCostlyBackup() = runBlocking {
        val tower = FakeOnboardingTower()
        val origin = TowerOrigin(tower.origin)
        val good = Nip49.encrypt(ByteArray(32) { 0x31 }, PASSWORD, 16)
        val flipped = good.substring(0, 40) + (if (good[40] == 'q') 'p' else 'q') + good.substring(41)
        tower.backups["alice"] = FakeOnboardingTower.Backup("alice", "someone", flipped, 1)
        val fresh = service()
        try { HostedBackupRecovery(fresh, tower, MemoryHostedBackupStore()).recover(origin, "alice", PASSWORD, "135790"); fail() }
        catch (e: BackupFailure) { assertTrue(e.kind == BackupFailure.Kind.WRONG_PASSWORD || e.kind == BackupFailure.Kind.MALFORMED) }
        assertEquals(IdentityState.None, fresh.state())
        // Cost above the production ceiling is refused by shape before any scrypt runs.
        val payload = studio.otherstuff.ambulando.core.nostr.Bech32.decode(good)!!.data.also { it[1] = 19 }
        tower.backups["alice"]!!.ncryptsec = studio.otherstuff.ambulando.core.nostr.Bech32.encode("ncryptsec", payload)
        try { HostedBackupRecovery(fresh, tower, MemoryHostedBackupStore()).recover(origin, "alice", PASSWORD, "135790"); fail() }
        catch (e: BackupFailure) { assertEquals(BackupFailure.Kind.MALFORMED, e.kind) }
        assertEquals(IdentityState.None, fresh.state())
    }

    // MARK: identity origin ledger

    @Test fun theOriginMarkSurvivesACrashBetweenCreationAndBookkeeping() {
        val file = File(tempDir(), "origin.json")
        val ledger = FileIdentityOriginStore(file)
        assertEquals(IdentityOrigin.EXISTING, ledger.originOf(TestKeys.NPUB, TestKeys.NPUB))
        ledger.beginNew()                                               // crash here: key created, completeNew never ran
        assertEquals(IdentityOrigin.EXISTING, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, null))
        assertEquals(IdentityOrigin.NEW, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, TestKeys.NPUB))
        assertEquals(IdentityOrigin.NEW, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, null))   // now settled on disk
        assertEquals(IdentityOrigin.EXISTING, FileIdentityOriginStore(file).originOf(TestKeys.syntheticNpub(0x44), TestKeys.syntheticNpub(0x44)))
        ledger.abandon()
        assertEquals(IdentityOrigin.EXISTING, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, TestKeys.NPUB))
        assertNotNull(ledger)
    }

    @Test fun anUnreadableOriginMarkFailsClosedInsteadOfBecomingExisting() {
        val file = File(tempDir(), "origin.json")
        assertEquals("absent means a legacy or imported identity", IdentityOrigin.EXISTING, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, TestKeys.NPUB))
        file.writeText("{ not json")
        assertEquals(IdentityOrigin.UNKNOWN, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, TestKeys.NPUB))
        file.writeText("""{"version":9,"phase":"new"}""")
        assertEquals("unknown version", IdentityOrigin.UNKNOWN, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, TestKeys.NPUB))
        file.delete(); file.mkdirs()                                       // a directory where the file should be: reads are refused
        assertEquals(IdentityOrigin.UNKNOWN, FileIdentityOriginStore(file).originOf(TestKeys.NPUB, TestKeys.NPUB))
        assertTrue(IdentityOrigin.UNKNOWN.needsBackup && IdentityOrigin.NEW.needsBackup && !IdentityOrigin.EXISTING.needsBackup)
    }
}
