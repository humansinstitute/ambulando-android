package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.sync.WritesNotReady

/** No free text enters bootstrap diagnostics, including exception messages and server error codes. */
enum class ReportStage { IDENTITY, SCOPES, CHANNELS, FILES, METADATA, METADATA_VALIDATION }
enum class ReportCause { OK, WRITES_NOT_READY, IDENTITY, CONNECTION, HTTP, MALFORMED, LIST_LIMIT, INVALID_RECORD, OTHER }

class ReportDiagnostics(private val log: DiagnosticsLog, private val stillCurrent: () -> Boolean = { true }) {
    suspend fun <T> at(stage: ReportStage, work: suspend () -> T): T {
        try {
            return work().also { currentCoroutineContext().ensureActive(); if (stillCurrent()) log.report(stage, ReportCause.OK) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val cause = when (error) {
                is WritesNotReady -> ReportCause.WRITES_NOT_READY
                is IdentityError -> ReportCause.IDENTITY
                is SafeConnectionIssue -> ReportCause.CONNECTION
                is TowerError.Http -> ReportCause.HTTP
                is TowerError.ListLimitReached -> ReportCause.LIST_LIMIT
                is TowerError.Malformed, is SerializationException -> ReportCause.MALFORMED
                is IllegalArgumentException, is IllegalStateException -> ReportCause.INVALID_RECORD
                else -> ReportCause.OTHER
            }
            if (stillCurrent()) log.report(stage, cause, (error as? TowerError.Http)?.status)
            throw error
        }
    }
}
