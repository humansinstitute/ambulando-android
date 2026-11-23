package studio.otherstuff.ambulando.app.ui.nav

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.WorkspaceReport

class WorkspaceReportLinkTest {
    private val w = "11111111-1111-4111-8111-111111111111"
    private val origin = "https://tower.example"
    @Test fun relativeAndConfiguredOriginResolveSameViewerRoute() {
        val path = WorkspaceReport.path(w)
        assertEquals(WorkRoute.WorkspaceReport(w), workspaceReportLink(path, origin, w))
        assertEquals(WorkRoute.WorkspaceReport(w), workspaceReportLink(origin + path, origin, w))
    }
    @Test fun foreignBackendWorkspaceAmbiguousOrUnsafeLinkCannotRoute() {
        val path = WorkspaceReport.path(w)
        for (url in listOf("https://other.example" + path, "//tower.example" + path, "http://tower.example" + path,
            origin + ":444" + path, origin + path + "&napplet=workspace-report", origin + path + "#x", origin + path + "&file_id=anything",
            path.replace(w, "22222222-2222-4222-8222-222222222222"))) {
            assertNull(url, workspaceReportLink(url, origin, w))
        }
    }
}
