package studio.otherstuff.ambulando.app

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.chat.EncodedMentions
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.transport.MessageRecord

/** Same canonical update as before: original identity/version and mentions; existing attachments are retained. */
internal fun messageEditIntent(message: MessageRecord, encoded: EncodedMentions) = WriteIntent(WriteKind.MESSAGE_EDIT, message.id, buildJsonObject {
    put("channel_id", message.channelId)
    message.threadId?.let { put("thread_id", it) }
    put("body", encoded.body)
    put("row_version", message.rowVersion)
    put("mentions", JsonArray(encoded.mentions.map { m -> buildJsonObject { m.requestObject.forEach { (k, v) -> put(k, v) } } }))
})
