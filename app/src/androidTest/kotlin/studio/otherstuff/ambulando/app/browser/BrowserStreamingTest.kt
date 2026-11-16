package studio.otherstuff.ambulando.app.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BrowserStreamingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun authenticatedRedirectStreamsSeventeenMegabytesAndChecksSavedBytes() {
        val fixture=BrowserHttpsFixture()
        val payload="archive-fixture-".repeat(1150000) // >16 MB, deterministic and no account data
        val destinations=mutableListOf<String>()
        fixture.respond={ r -> when(r.target) {
            "/start" -> BrowserHttpsFixture.Response(status=302,headers=mapOf("Location" to "/archive"))
            "/archive" -> if(r.headers["cookie"]=="archive=allowed") BrowserHttpsFixture.Response(payload) else BrowserHttpsFixture.Response(status=403)
            else -> BrowserHttpsFixture.Response(status=404)
        } }
        lateinit var browser:BrowserModel
        compose.runOnUiThread { browser=BrowserModel(context); browser.open(null) }
        val output=File.createTempFile("saved-archive", ".bin",context.cacheDir)
        try {
            val transfer=BrowserFileTransfer(BrowserDownload(browser.selected!!,0,fixture.base+"/start",null,"archive.bin","application/octet-stream"))
            output.outputStream().use { BrowserFilePolicy.fetchTo(fixture.base+"/start",transfer,{destinations+=it; if(it.endsWith("/archive"))"archive=allowed" else null},it) }
            assertTrue(output.length()>16*1024*1024)
            assertEquals(payload.toByteArray().size.toLong(),output.length())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()),MessageDigest.getInstance("SHA-256").digest(output.readBytes()))
            assertEquals(listOf(fixture.base+"/start",fixture.base+"/archive"),destinations)
            transfer.cancel()
            assertTrue(runCatching { transfer.check() }.isFailure)
        } finally { output.delete();fixture.close();compose.runOnUiThread { browser.closeAll() } }
    }
    @Test fun pagePostBearerResponseUsesItsOwnContextAndProgrammaticBlobExports() {
        val fixture=BrowserHttpsFixture()
        val payload="private-response-".repeat(4096)
        fixture.respond={ r -> when(r.target) {
            "/" -> BrowserHttpsFixture.Response("""<meta name="viewport" content="width=device-width,initial-scale=1"><title>READY</title><h1>Page download</h1>""")
            "/export" -> if(r.method=="POST" && r.body=="fixture-body" && r.headers["authorization"]=="Bearer disposable-fixture") BrowserHttpsFixture.Response(payload,headers=mapOf("Content-Type" to "application/octet-stream")) else BrowserHttpsFixture.Response(status=403)
            else -> BrowserHttpsFixture.Response(status=404)
        } }
        lateinit var browser:BrowserModel
        compose.runOnUiThread { browser=BrowserModel(context);browser.open(fixture.base+"/") }
        compose.setContent { AmbulandoTheme { BrowserScreen(browser) } }
        try {
            compose.waitUntil(15000){browser.selected?.title=="READY"}
            compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript("fetch('/export',{method:'POST',headers:{Authorization:'Bearer disposable-fixture'},body:'fixture-body'}).then(r=>window.ambulandoDownload(r,'post-export.bin')).catch(e=>document.title='ERR '+e.message);true",null) }
            compose.waitUntil(20000){browser.download!=null || browser.selected!!.title.startsWith("ERR")}
            assertEquals("READY",browser.selected!!.title)
            val saved=browser.download!!.file!!
            assertArrayEquals(payload.toByteArray(),saved.readBytes())
            compose.runOnUiThread { browser.cancelFileRequests();browser.selected!!.refusal=null }
            assertFalse(saved.exists())
            compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript("const a=document.createElement('a');a.href=URL.createObjectURL(new Blob(['programmatic-bytes']));a.download='programmatic.txt';a.click();true",null) }
            compose.waitUntil(10000){browser.download!=null}
            assertEquals("programmatic-bytes",browser.download!!.file!!.readText())
            val spool=browser.download!!.file!!
            compose.runOnUiThread { browser.hide() }
            assertFalse(spool.exists())
        } finally { compose.runOnUiThread { browser.closeAll() };fixture.close() }
    }
}
