package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.transport.*

/** In-memory launcher labels only. A cached descriptor never grants execution or server authority. */
class FileDescriptorCache<T> {
    private data class Key(val file: String, val version: String, val sha: String, val metadata: JsonObject?)
    private val entries = mutableMapOf<String,Pair<Key,T>>()
    private fun key(file: FileRecord): Key? {
        if (file.accessRemoved || file.archived) return null
        val version = file.raw.string("current_version_id") ?: file.raw.obj("current_version")?.string("id") ?: return null
        val sha = file.sha256 ?: return null
        return Key(file.id,version,sha,file.raw.obj("metadata"))
    }
    fun get(file: FileRecord?): T? {
        val record = file ?: return null
        val key = key(record) ?: return null
        return entries[record.id]?.takeIf { it.first == key }?.second
    }
    fun put(file: FileRecord, value: T) { key(file)?.let { entries[file.id] = it to value } }
    fun remove(id: String) { entries.remove(id) }
    fun retain(ids: Set<String>) { entries.keys.retainAll(ids) }
}
