package studio.otherstuff.ambulando.app.ui.nav

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.DailyMetricsApp

class DailyMetricsLinkTest {
    private val w = "11111111-1111-4111-8111-111111111111"
    private val origin = "https://tower.example"
    @Test fun relativeAndConfiguredOriginResolveSameViewerRoute() {
        val path = DailyMetricsApp.path(w)
        assertEquals(WorkRoute.DailyMetrics(w), dailyMetricsLink(path, origin, w))
        assertEquals(WorkRoute.DailyMetrics(w), dailyMetricsLink(origin + path, origin, w))
    }
    @Test fun foreignBackendWorkspaceAmbiguousOrUnsafeLinkCannotRoute() {
        val path = DailyMetricsApp.path(w)
        for (url in listOf("https://other.example" + path, "//tower.example" + path, "http://tower.example" + path,
            origin + ":444" + path, origin + path + "&napplet=daily-tracker", origin + path + "#x", origin + path + "&file_id=anything",
            path.replace(w, "22222222-2222-4222-8222-222222222222"))) {
            assertNull(url, dailyMetricsLink(url, origin, w))
        }
    }
}
