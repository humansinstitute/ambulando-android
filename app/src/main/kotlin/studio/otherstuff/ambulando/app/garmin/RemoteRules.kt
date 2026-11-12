package studio.otherstuff.ambulando.app.garmin

import studio.otherstuff.ambulando.app.voice.VoicePhase

internal enum class RemoteAction { NONE, FINISH, CANCEL, RETRY }
/** Delayed commands can affect only their own live draft; sending is never cancellable remotely. */
internal fun remoteAction(state: String, op: String, ownsDraft: Boolean, phase: VoicePhase?): RemoteAction = when {
    op == "finish" && state in setOf("waiting", "recording") && ownsDraft && phase == VoicePhase.RECORDING -> RemoteAction.FINISH
    op in setOf("finish", "cancel") && state == "waiting" && (!ownsDraft || phase != VoicePhase.SENDING) -> RemoteAction.CANCEL
    op == "cancel" && ownsDraft && state in setOf("recording", "error") && phase != VoicePhase.SENDING -> RemoteAction.CANCEL
    op == "retry" && state == "error" && ownsDraft && phase == VoicePhase.REVIEW -> RemoteAction.RETRY
    else -> RemoteAction.NONE
}
