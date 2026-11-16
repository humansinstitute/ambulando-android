package studio.otherstuff.ambulando.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserRestorationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(condition:()->Boolean) {
        repeat(200){var ready=false;instrumentation.runOnMainSync{ready=condition()};if(ready)return;Thread.sleep(50)}
        error("Browser state did not settle")
    }
    @Test fun freshModelRestoresOnlyGetTabsAndSelectionWithoutPostReplayOrSigningGrants() {
        val context=instrumentation.targetContext
        val fixture=BrowserHttpsFixture()
        var posts=0
        fixture.respond={r->if(r.method=="POST"){posts++;BrowserHttpsFixture.Response("<title>POST_RESULT</title>")}else BrowserHttpsFixture.Response("<title>${r.target}</title><form id='form' method='post' action='/post'><input name='data' value='fixture'></form>")}
        lateinit var first:BrowserModel
        lateinit var restored:BrowserModel
        instrumentation.runOnMainSync {
            context.getSharedPreferences("browser-tabs-v1",0).edit().clear().commit()
            first=BrowserModel(context);first.open(fixture.base+"/one")
        }
        try {
            waitFor{first.selected?.title=="/one" && !first.selected!!.loading}
            instrumentation.runOnMainSync{first.open(fixture.base+"/two")}
            waitFor{first.selected?.title=="/two" && !first.selected!!.loading}
            val two=first.selected!!
            instrumentation.runOnMainSync{first.open(fixture.base+"/form")}
            waitFor{first.selected?.title=="/form" && !first.selected!!.loading}
            instrumentation.runOnMainSync{first.selected!!.webView.evaluateJavascript("form.submit()",null)}
            waitFor{first.selected?.title=="POST_RESULT" && !first.selected!!.loading}
            instrumentation.runOnMainSync{first.select(two);first.hide()}
            assertEquals(1,posts)
            instrumentation.runOnMainSync{restored=BrowserModel(context);restored.show()}
            waitFor{restored.tabs.size==2 && restored.tabs.all{!it.loading && it.title.isNotEmpty()}}
            instrumentation.runOnMainSync{
                assertEquals(fixture.base+"/two",restored.selected!!.url)
                assertNull(restored.prompt);assertTrue(restored.sites.isEmpty());assertTrue(restored.locked)
                assertTrue(restored.tabs.none{it.url?.endsWith("/post")==true})
            }
            assertEquals("No POST is replayed by restore",1,posts)
        } finally {
            instrumentation.runOnMainSync{first.closeAll();runCatching{restored.closeAll()}}
            fixture.close()
        }
    }
}
