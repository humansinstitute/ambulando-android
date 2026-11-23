package studio.otherstuff.ambulando.core.surfaces

import java.io.File
import kotlinx.serialization.json.*

/** Separate v2 namespace and exact receipt shape; no interpretation of v1 receipts. */
class GenericApprovalStore(private val directory: File) {
    private fun file(r: GenericNapplet.Receipt): File {
        require(r.key.matches(Regex("napplet-approval-v2:[a-f0-9]{64}")))
        return File(directory,r.key+".json")
    }
    @Synchronized fun approved(r: GenericNapplet.Receipt): Boolean=runCatching {
        val f=file(r); require(f.length() in 1..256)
        GenericJson.decode(f.readBytes())==buildJsonObject { put("version",2); put("fingerprint",r.fingerprint) }
    }.getOrDefault(false)
    @Synchronized fun remember(r: GenericNapplet.Receipt): Boolean=runCatching {
        check(directory.isDirectory || directory.mkdirs()); val f=file(r); val pending=File(directory,f.name+".pending")
        try { pending.writeText(GenericJson.canonical(buildJsonObject { put("version",2); put("fingerprint",r.fingerprint) })); check(pending.renameTo(f)); check(approved(r)) } finally { pending.delete() }
    }.isSuccess
    @Synchronized fun forget(r: GenericNapplet.Receipt): Boolean=runCatching { val f=file(r); check(!f.exists() || f.delete()); check(!f.exists()) }.isSuccess
}
