package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sync.WritesNotReady
import studio.otherstuff.ambulando.core.support.tempDir
import java.io.File

class ReportDiagnosticsTest {
    @Test fun hostileExceptionFieldsCannotEnterExportAndStagesSurviveRestart() = runBlocking {
        val file = File(tempDir(), "diagnostics.json")
        val log = DiagnosticsLog(file = file)
        val diagnostics = ReportDiagnostics(log)
        val hostile = "https://private.invalid/npub1private?token=secret\nraw body participant"
        val cases = listOf(
            ReportStage.SCOPES to TowerError.Http(403, hostile, hostile),
            ReportStage.CHANNELS to TowerError.Malformed(hostile),
            ReportStage.FILES to TowerError.ListLimitReached(hostile),
            ReportStage.METADATA to IllegalStateException(hostile),
            ReportStage.METADATA_VALIDATION to Exception(hostile),
            ReportStage.IDENTITY to WritesNotReady(),
        )
        for ((stage, error) in cases) {
            assertSame(error, runCatching { diagnostics.at(stage) { throw error } }.exceptionOrNull())
        }
        val exported = DiagnosticsLog(file = file).export("test", 1, 36)
        for (secret in listOf("private.invalid", "npub1private", "token=secret", "secret", "raw body", "participant", "https://")) assertFalse(secret, exported.contains(secret))
        assertTrue(exported.contains("report=SCOPES | cause=HTTP"))
        assertTrue(exported.contains("status=403"))
        for (cause in listOf("MALFORMED", "LIST_LIMIT", "INVALID_RECORD", "OTHER", "WRITES_NOT_READY")) assertTrue(cause, exported.contains("cause=$cause"))
    }

    @Test fun cancellationAndStaleSuccessOrFailureDoNotPublishDiagnostics() = runBlocking {
        val log = DiagnosticsLog()
        var current = true
        val diagnostics = ReportDiagnostics(log) { current }
        val cancellation = CancellationException("sensitive")
        assertSame(cancellation, runCatching { diagnostics.at(ReportStage.FILES) { throw cancellation } }.exceptionOrNull())
        diagnostics.at(ReportStage.METADATA) { current = false; 1 }
        runCatching { diagnostics.at(ReportStage.METADATA) { throw Exception("sensitive") } }
        assertFalse(log.export("test", 1, 36).contains("report="))
    }

    @Test fun persistedStageAndCauseAreStrictlyAllowlisted() {
        val file = File(tempDir(), "diagnostics.json")
        file.writeText("""[{"at":"2026-01-01T00:00:00Z","reportStage":"https://secret.invalid","reportCause":"OTHER"},{"at":"2026-01-01T00:00:00Z","reportStage":"FILES","reportCause":"secret"}]""")
        val exported = DiagnosticsLog(file = file).export("test", 1, 36)
        assertFalse(exported.contains("secret")); assertFalse(exported.contains("report="))
    }
}
