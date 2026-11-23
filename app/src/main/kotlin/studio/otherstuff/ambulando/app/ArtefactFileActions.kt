package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.store.*
import kotlinx.coroutines.*

/** Reuses the checksum-verified File download, Android sharing and document-picker export paths. */
@Composable
internal fun ArtefactFileActions(model: AppModel, file: FileRecord, done: () -> Unit) {
    val runtime = model.active ?: return
    var saved by remember(runtime,file.id) { mutableStateOf<Pair<FileRecord, FileVersionRecord>?>(null) }
    var failure by remember(runtime,file.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime,file.id) {
        val w = runtime.workspace
        fun current() = model.active === runtime && !runtime.workspace.accessRemoved && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked
        try {
            val api = model.fileNappletAPI(runtime.connection,runtime.directTransport).fencedArtefactAPI(runtime.store.reader,w,file.id,::current)
            val pair = runtime.sync.genericNappletRead(file.id) {
                val fresh = api.artefactFile(w.id,w.appNpub,file.id)
                val head = api.genericNappletHead(w.id,w.appNpub,file.id)
                val version = head.versions.single()
                require(version.fileId == fresh.id && version.id == head.currentVersionId && version.sha256 != null)
                fresh.copy(storageObjectId = version.storageObjectId, sizeBytes = version.sizeBytes, sha256 = version.sha256, contentType = version.contentType) to version
            }
            ensureActive(); check(current()); saved = pair
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure = "The current saved File could not be verified. Close and reopen to retry. Downloaded history remains on this device." }
    }
    Dialog(onDismissRequest = done, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    TextButton(onClick = done, modifier = Modifier.testTag("artefacts.exportBack")) { Text("Back to artefact") }
                    Text("Saved File", Modifier.padding(12.dp))
                }
                Text("Download or export the current saved File. Unsaved editor changes are kept in the editor.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) {
                    val current = saved
                    if (current != null) NativeFiles(model, file.id, direct = false, exactFile = current.first, exactVersion = current.second)
                    else Text(failure ?: "Checking the saved File…", Modifier.padding(12.dp))
                }
            }
        }
    }
}
