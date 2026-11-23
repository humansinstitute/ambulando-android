package studio.otherstuff.ambulando.app.ui.nav

import java.net.URI
import java.net.URLDecoder
import studio.otherstuff.ambulando.core.surfaces.WorkspaceReport

/** Only the current configured backend (or a relative in-app link); never switch backend/actor from URL input. */
fun workspaceReportLink(url: String, connectionOrigin: String, workspace: String): WorkRoute.WorkspaceReport? = runCatching {
    val uri = URI(url)
    val origin = URI(connectionOrigin)
    if (uri.isAbsolute && (uri.scheme != "https" || uri.host != origin.host || uri.port != origin.port)) return null
    if ((!uri.isAbsolute && uri.rawAuthority != null) || uri.userInfo != null || uri.path != "/flight-deck" || uri.fragment != null) return null
    val query = uri.rawQuery?.split('&')?.map {
        val pair = it.split('=', limit = 2)
        require(pair.size == 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    } ?: return null
    if (query.map { it.first }.distinct().size != query.size) return null
    val values = query.toMap()
    if (values.keys != setOf("workspaceid", "napplet") || values["workspaceid"] != workspace || values["napplet"] != WorkspaceReport.ID) return null
    WorkRoute.WorkspaceReport(workspace)
}.getOrNull()
