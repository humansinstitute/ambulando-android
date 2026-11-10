package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.*

/** No endpoint or encryption material is retained in the response model or an error message. */
data class PushReadiness(val ready: Boolean, val reason: String?, val vapidPublicKey: String? = null, val leaseExpiresAt: String? = null)

suspend fun TowerAPI.pushReadiness(workspace: WorkspaceRecord): PushReadiness {
    val o = obj(client.request(path = basePath(workspace.id) + "/descriptor", appNpub = workspace.appNpub), "workspace")
    val n = o.obj("notifications") ?: o.obj("descriptor")?.obj("notifications")
    val u = n?.obj("transport_status")?.obj("unifiedpush")
        ?: return PushReadiness(false, "transport_not_implemented")
    return PushReadiness(u.bool("ready") == true, u.string("reason"), u.string("vapid_public_key"))
}

private fun basePath(workspaceId: String) = TowerAPI.base(workspaceId)
private fun pushPath(workspace: WorkspaceRecord, installationId: String) =
    TowerAPI.base(workspace.id) + "/notifications/installations/" + TowerAPI.uuid(installationId)

suspend fun TowerAPI.registerPush(workspace: WorkspaceRecord, installationId: String, endpoint: String, p256dh: String, auth: String, permission: Boolean, version: String): PushReadiness {
    val base = pushPath(workspace, installationId)
    client.request("PUT", base, appNpub = workspace.appNpub, jsonBody = jsonObjectOf(
        "client_app_id" to NOTIFICATION_CLIENT_APP_ID, "platform" to "android", "app_version" to version,
        "permission_state" to if (permission) "granted" else "denied",
    ).toString().toByteArray())
    val body = buildJsonObject {
        put("endpoint", endpoint)
        putJsonObject("keys") { put("p256dh", p256dh); put("auth", auth) }
        put("preview", "generic"); put("lease_seconds", 604800)
    }
    val o = obj(client.request("PUT", "$base/transports/unifiedpush", appNpub = workspace.appNpub, jsonBody = body.toString().toByteArray()), "push registration")
    val d = o.obj("delivery") ?: throw TowerError.Malformed("push readiness")
    return PushReadiness(d.bool("ready") == true, d.string("reason"), leaseExpiresAt = o.obj("device")?.string("lease_expires_at"))
}

suspend fun TowerAPI.revokePush(workspace: WorkspaceRecord, installationId: String) {
    try { client.request("DELETE", pushPath(workspace, installationId), appNpub = workspace.appNpub) }
    catch (e: TowerError.Http) { if (e.status != 404) throw e }
}
