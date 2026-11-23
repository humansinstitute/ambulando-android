package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex

class GenericNappletTest {
    private val v=Json.parseToJsonElement(javaClass.getResource("/napplets/generic-v2.json")!!.readText()).jsonObject
    private fun binding(manifest: JsonElement=v.getValue("manifest"),metadata: JsonElement=v.getValue("metadata"))=GenericNapplet.parse("<script id=\"wingman-napplet-manifest\">$manifest</script>",metadata.jsonObject)!!
    @Test fun portableCanonicalAndRejections() {
        v.getValue("canonical").jsonArray.forEach { row -> val o=row.jsonObject; val result=GenericJson.canonical(o.getValue("value")); assertEquals(o.getValue("canonical").jsonPrimitive.content,result); assertEquals(o.getValue("sha256").jsonPrimitive.content,Sha256Hex.of(result.toByteArray())) }
        v.getValue("invalidJson").jsonArray.forEach { assertTrue(it.toString(),runCatching { GenericJson.decode(it.jsonPrimitive.content) }.isFailure) }
        v.getValue("boundaries").jsonObject.getValue("invalidUtf8Hex").jsonArray.forEach { h -> val s=h.jsonPrimitive.content; assertTrue(runCatching { GenericJson.decode(s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }.isFailure) }
    }
    @Test fun portableReceiptsAndRequests() {
        val b=binding(); val session="0123456789abcdef0123456789abcdef"
        v.getValue("requests").jsonArray.forEach { row -> val o=row.jsonObject; assertEquals(o.getValue("label").jsonPrimitive.content,o.getValue("valid").jsonPrimitive.boolean,runCatching { GenericNapplet.request(o.getValue("value").toString(),session,b) }.isSuccess) }
        fun receipt(b: GenericNapplet.Binding,write: Boolean,expected: String) {
            val r=GenericNapplet.receipt(v.getValue("context").jsonObject,v.getValue("codeFileId").jsonPrimitive.content,v.getValue("digest").jsonPrimitive.content,b,write)
            assertEquals(v.getValue(expected).jsonObject.getValue("key").jsonPrimitive.content,r.key); assertEquals(v.getValue(expected).jsonObject.getValue("fingerprint").jsonPrimitive.content,r.fingerprint)
        }
        receipt(b,true,"receipt"); receipt(b,false,"receiptNoWrite"); val ro=binding(v.getValue("readonlyManifest")); receipt(ro,true,"receiptReadonly")
        assertTrue(runCatching { GenericNapplet.request(v.getValue("readonlySaveRejection").jsonObject.getValue("request").toString(),session,ro) }.isFailure)
        assertEquals("workspace-aggregate",binding(v.getValue("aggregateManifest"),v.getValue("aggregateMetadata")).capability)
    }
    @Test fun budgetsAndDuplicateNestedKeys() {
        fun nested(n: Int)="{\"x\":".repeat(n)+"null"+"}".repeat(n)
        GenericJson.decode(nested(31)); assertTrue(runCatching { GenericJson.decode(nested(32)) }.isFailure)
        GenericJson.decode("{\"s\":\""+"a".repeat(65536)+"\"}")
        assertTrue(runCatching { GenericJson.decode("{\"s\":\""+"a".repeat(65537)+"\"}") }.isFailure)
        assertTrue(runCatching { GenericJson.decode("{\"x\":{\"a\":1,\"a\":2}}") }.isFailure)
        val allowed=buildJsonObject { put("a",JsonArray(List(49998) { JsonNull })) }; GenericJson.validate(allowed)
        assertTrue(runCatching { GenericJson.validate(buildJsonObject { put("a",JsonArray(List(49999) { JsonNull })) }) }.isFailure)
    }
    @Test fun approvalNamespaceCodeChangesAndEffectiveGrant() {
        val b=binding(); val ctx=v.getValue("context").jsonObject
        fun r(d: String=v.getValue("digest").jsonPrimitive.content,write: Boolean=true)=GenericNapplet.receipt(ctx,v.getValue("codeFileId").jsonPrimitive.content,d,b,write)
        val dir=java.nio.file.Files.createTempDirectory("generic-receipt-test").toFile()
        try {
            val store=GenericApprovalStore(dir); val first=r()
            assertFalse(store.approved(first)); assertTrue(store.remember(first)); assertTrue(store.approved(r()))
            val changed=r("b".repeat(64)); assertEquals(first.key,changed.key); assertNotEquals(first.fingerprint,changed.fingerprint); assertFalse(store.approved(changed))
            assertFalse(store.approved(r(write=false))); assertTrue(store.forget(first)); assertFalse(store.approved(first))
        } finally { dir.deleteRecursively() }
    }

    @Test fun hardenedPortableNegativeManifests() {
        v.getValue("invalidManifests").jsonArray.forEach { row -> val o=row.jsonObject
            assertTrue(o.getValue("label").jsonPrimitive.content,runCatching { binding(o.getValue("manifest"),o.getValue("metadata")) }.isFailure)
        }
        v.getValue("invalidManifestHtml").jsonArray.forEach { row -> val o=row.jsonObject
            assertTrue(o.getValue("label").jsonPrimitive.content,runCatching { GenericNapplet.parse(o.getValue("html").jsonPrimitive.content,o.getValue("metadata").jsonObject) }.isFailure)
        }
    }

    @Test fun authoritativeWriteFlagPrecedenceAndType() {
        fun value(raw: String)=Json.parseToJsonElement(raw).jsonObject
        assertTrue(GenericNapplet.writeGrant(value("{\"file\":{\"can_write\":true}}")))
        assertFalse(GenericNapplet.writeGrant(value("{\"can_write\":false,\"file\":{\"can_write\":true}}")))
        listOf("null","0","\"true\"","[]","{}").forEach { flag -> assertTrue(runCatching { GenericNapplet.writeGrant(value("{\"can_write\":$flag,\"file\":{\"can_write\":true}}")) }.isFailure) }
        assertTrue(runCatching { GenericNapplet.writeGrant(value("{\"file\":{}}")) }.isFailure)
    }

    @Test fun duplicateDeclarationsCannotDowngradeGenericFileEntryToOrdinaryHtml() {
        val declaration="<script id=\"wingman-napplet-manifest\">${v.getValue("manifest")}</script>"
        val empty=JsonObject(emptyMap())
        assertNull(GenericNapplet.parse(declaration+declaration,empty))
        assertTrue(GenericNapplet.claimsRuntime(declaration+declaration,empty))
        assertFalse(GenericNapplet.claimsRuntime("<h1>Ordinary HTML</h1>",empty))
    }

    @Test fun exactPortableErrorEnvelopes() {
        v.getValue("results").jsonArray.filter { it.jsonObject["status"]==JsonPrimitive("error") }.forEach { value ->
            val o=value.jsonObject
            assertEquals(o,GenericNapplet.error(GenericNapplet.text(o,"session"),GenericNapplet.text(o,"requestId"),GenericNapplet.text(o,"code")))
        }
    }

    @Test fun ieeeBoundaryNumbersMatchIndependentEcmascriptReference() {
        // Expected strings generated by ECMAScript JSON.stringify, especially around powers of two.
        val rows=Json.parseToJsonElement(javaClass.getResource("/napplets/ecmascript-numbers.json")!!.readText()).jsonArray
        rows.forEach { row -> val o=row.jsonObject
            val number=Double.fromBits(java.lang.Long.parseUnsignedLong(GenericNapplet.text(o,"bits"),16))
            assertEquals(GenericNapplet.text(o,"bits"),GenericNapplet.text(o,"canonical"),GenericJson.canonical(JsonPrimitive(number)))
        }
    }

    @Test fun scriptAttributeWhitespaceAndEscapedTitleMatchHtmlContract() {
        val metadata=v.getValue("metadata").jsonObject
        val manifest=JsonObject(v.getValue("manifest").jsonObject+("title" to JsonPrimitive("Literal </script> title")))
        val encoded=manifest.toString().replace("<","\\u003c")
        val html="<script id=\"wingman-napplet-manifest\">$encoded</script>"
        assertEquals("Literal </script> title",GenericNapplet.parse(html,metadata)!!.title)
        assertTrue(runCatching { GenericNapplet.parse(html.replace("script id","script\u000bid"),metadata) }.isFailure)
    }

}
