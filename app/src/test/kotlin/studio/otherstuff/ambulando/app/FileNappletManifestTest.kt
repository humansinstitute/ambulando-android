package studio.otherstuff.ambulando.app

import org.junit.Assert.*
import org.junit.Test

class FileNappletManifestTest {
    private val declaration = "<script type=\"application/json\" id=\"wingman-napplet-manifest\">{\"version\":1}</script>"
    @Test fun canonicalInertDeclarationIsRecognized() { assertNotNull(nappletManifest("<html>$declaration</html>")) }
    @Test fun ambiguousMalformedAndExtendedDeclarationsStayBridgeFree() {
        listOf(declaration + declaration, declaration.replace("application/json", "text/javascript"), declaration.replace("id=", "src=\"https://example.com\" id="), declaration.replace("id=", "async id="), declaration.replace("{\"version\":1}", "not json")).forEach { assertNull(nappletManifest(it)) }
    }
    @Test fun ordinaryHtmlDoesNotGetACapability() { assertNull(nappletManifest("<h1>Hello</h1><script>console.log('test')</script>")) }
}
