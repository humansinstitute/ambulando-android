package studio.otherstuff.ambulando.core.identitybackup

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string

/**
 * Typed client for NStack `/api/v4/identity-backup` (tower docs/contract/hosted-identity-backup-v1.md).
 *
 * The owner routes (`PUT`, `GET …/me`) are strict NIP-98 signed directly by the identity being backed up. The public read is
 * unsigned and returns only ciphertext and revision; its body is bounded and never cached. No password, PIN or plaintext key
 * is ever part of any request here.
 */
class HostedBackupAPI(private val origin: TowerOrigin, private val transport: HttpTransport, private val signer: EventSigner?) {
    companion object {
        const val BASE = "/api/v4/identity-backup"
        /** A real answer is a few hundred bytes; anything bigger is not NStack's backup response. */
        const val MAX_BODY = 8 * 1024
    }

    private val client = TowerClient(origin, transport, signer)

    private fun fail(status: Int, body: ByteArray, retryAfter: String?): Nothing =
        throw BackupFailure.classify(status, parseJsonObjectOrNull(body.take(MAX_BODY).toByteArray())?.string("code"), retryAfter?.trim()?.toIntOrNull()?.coerceIn(1, 3600))

    private fun parse(body: ByteArray): JsonObject {
        if (body.size > MAX_BODY) throw BackupFailure(BackupFailure.Kind.MALFORMED)
        return try { parseJsonObject(body, "backup") } catch (e: Exception) { throw BackupFailure(BackupFailure.Kind.MALFORMED) }
    }

    private fun path(username: String): String {
        val normal = BackupUsername.normalize(username)
        if (normal == null || normal != username) throw BackupFailure(BackupFailure.Kind.INVALID_USERNAME)
        return "$BASE/$username"
    }

    private fun revision(o: JsonObject): String =
        o.string("revision")?.takeIf { it.length in 1..18 && it.all(Char::isDigit) && (it == "0" || !it.startsWith("0")) } ?: throw BackupFailure(BackupFailure.Kind.MALFORMED)

    /** Creates (`expectedRevision` "0") or replaces. An identical replay of the last accepted write answers 200 with the same revision. */
    suspend fun put(username: String, ncryptsec: String, expectedRevision: String): HostedBackupReceipt {
        if (!BackupCiphertext.isWellFormed(ncryptsec)) throw BackupFailure(BackupFailure.Kind.INVALID_BACKUP)
        val target = path(username)
        val body = jsonBody(JsonObject(mapOf("ncryptsec" to JsonPrimitive(ncryptsec), "expected_revision" to JsonPrimitive(expectedRevision))))
        val exchange = signedSend("PUT", target, body)
        if (exchange.status !in 200..299) fail(exchange.status, exchange.body, null)
        val o = parse(exchange.body)
        val name = o.string("username")
        if (name != username) throw BackupFailure(BackupFailure.Kind.MALFORMED)
        return HostedBackupReceipt(name, revision(o))
    }

    /** Proves a durable hosted copy exists for the signer: username and revision only, no ciphertext. Null when none exists. */
    suspend fun me(): HostedBackupReceipt? {
        val exchange = signedSend("GET", "$BASE/me", null)
        if (exchange.status == 404 && parseJsonObjectOrNull(exchange.body)?.string("code") == "backup_not_found") return null
        if (exchange.status !in 200..299) fail(exchange.status, exchange.body, null)
        val o = parse(exchange.body)
        val name = o.string("username")?.let(BackupUsername::normalize) ?: throw BackupFailure(BackupFailure.Kind.MALFORMED)
        return HostedBackupReceipt(name, revision(o))
    }

    /** Public, unauthenticated: the ciphertext a person recovers with. Shape-checked before anything decrypts it. */
    suspend fun fetch(username: String): HostedBackupCopy {
        val target = path(username)
        val request = HttpRequest("GET", origin.url(target), mapOf("Accept" to "application/json"))
        val response = try { transport.send(request) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw BackupFailure(BackupFailure.Kind.NETWORK) }
        if (response.status !in 200..299) fail(response.status, response.body, response.header("Retry-After"))
        val o = parse(response.body)
        val text = o.string("ncryptsec") ?: throw BackupFailure(BackupFailure.Kind.MALFORMED)
        if (o.string("username") != username || !BackupCiphertext.isWellFormed(text)) throw BackupFailure(BackupFailure.Kind.MALFORMED)
        return HostedBackupCopy(username, text, revision(o))
    }

    private suspend fun signedSend(method: String, path: String, body: ByteArray?) =
        try { client.exchange(method, path, null, body) }
        catch (e: CancellationException) { throw e }
        catch (e: IdentityError) {
            throw BackupFailure(if (e == IdentityError.Locked) BackupFailure.Kind.IDENTITY_LOCKED else BackupFailure.Kind.IDENTITY_MISMATCH)
        }
        catch (e: BackupFailure) { throw e }
        catch (e: TowerError) { throw BackupFailure(BackupFailure.Kind.MALFORMED) }
        catch (e: Exception) { throw BackupFailure(BackupFailure.Kind.NETWORK) }
}
