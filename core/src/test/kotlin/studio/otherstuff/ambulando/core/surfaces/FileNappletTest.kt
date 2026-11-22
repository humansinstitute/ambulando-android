package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FileNappletTest {
    private val a = "10000000-0000-4000-8000-000000000001"
    private val b = "10000000-0000-4000-8000-000000000002"
    private fun model(date: String = "2024-02-29", answers: String = "\"$a\":false,\"$b\":0") = Json.parseToJsonElement("""{"format":"daily-tracker-v1","metrics":[{"id":"$a","label":"Ate well","type":"boolean","unit":"","archived":false},{"id":"$b","label":"Number","type":"number","unit":"kg","archived":false}],"days":{"$date":{$answers}}}""").jsonObject
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun falseZeroAndUnansweredSurviveSerializationAndReload() {
        val value = FileNapplet.model(model())
        assertEquals(value, FileNapplet.model(Json.parseToJsonElement(value.toString()).jsonObject))
        val day = value.getValue("days").jsonObject.getValue("2024-02-29").jsonObject
        assertEquals(JsonPrimitive(false), day[a]); assertEquals(JsonPrimitive(0), day[b])
        assertFalse(FileNapplet.model(model(answers = "")).getValue("days").jsonObject.values.first().jsonObject.containsKey(a))
    }
    @Test fun validatesLocalDatesWithoutUtcAndRejectsWrongAnswerTypes() {
        listOf("2023-02-29", "2024-13-01", "2024-2-01", "2024-02-30", "2024-01-01T00:00:00Z").forEach { rejected { FileNapplet.model(model(it)) } }
        listOf("2024-12-31", "2025-01-01", "2024-03-10").forEach { FileNapplet.model(model(it)) }
        listOf("\"$a\":0", "\"$b\":false", "\"$a\":null", "\"$b\":\"0\"", "\"10000000-0000-4000-8000-000000000003\":true").forEach { rejected { FileNapplet.model(model(answers = it)) } }
    }
    @Test fun historyCanBeEditedAndUsedMetricTypeCannotChange() {
        FileNapplet.model(model(answers = "\"$a\":true,\"$b\":62.5"), model())
        rejected { FileNapplet.model(Json.parseToJsonElement(model().toString().replace("\"type\":\"number\"", "\"type\":\"boolean\"").replace("\"$b\":0", "\"$b\":false")).jsonObject, model()) }
        FileNapplet.model(Json.parseToJsonElement(model().toString().replace("\"archived\":false", "\"archived\":true")).jsonObject, model())
    }
    @Test fun labelsUnitsAndLimitsMatchTheSharedJavascriptModel() {
        listOf(" ", "\u0000", "\u001f", "\u007f", "a".repeat(121)).forEach { bad ->
            val metric = model().getValue("metrics").jsonArray[0].jsonObject
            val changed = JsonObject(metric + ("label" to JsonPrimitive(bad)))
            rejected { FileNapplet.model(JsonObject(model() + ("metrics" to JsonArray(listOf(changed, model().getValue("metrics").jsonArray[1]))))) }
        }
        rejected { FileNapplet.model(model("0000-01-01")) }
        val extra = JsonObject(model() + ("unexpected" to JsonPrimitive(true)))
        rejected { FileNapplet.model(extra) }
    }
    @Test fun rejectsForeignNonceExtraKeysSignerAndPathRequests() {
        val valid = """{"type":"wingman-napplet-data","version":1,"session":"nonce","requestId":"read_1","operation":"read","binding":"tracker"}"""
        assertEquals("read_1", FileNapplet.request(valid, "nonce")["requestId"]?.jsonPrimitive?.content)
        rejected { FileNapplet.request(valid, "other") }
        rejected { FileNapplet.request(valid.replace("\"read\"", "\"sign\""), "nonce") }
        rejected { FileNapplet.request(valid.dropLast(1) + ",\"path\":\"/foo\"}", "nonce") }
        rejected { FileNapplet.request(valid.replace("read_1", "bad id"), "nonce") }
    }
    @Test fun manifestCapabilityRequiresExactMetadataAndFixedBinding() {
        val manifest = Json.parseToJsonElement("""{"version":1,"app_id":"daily-tracker","bindings":{"tracker":{"format":"daily-tracker-v1","operations":["read","save"],"privacy":"self"}}}""").jsonObject
        val metadata = buildJsonObject { put("html_artifact", true); put("artefact", ArtefactType.HTML.metadata().getValue("artefact")); put("napplet", Json.parseToJsonElement("""{"version":1,"app_id":"daily-tracker","bindings":{"tracker":{"file_id":"$a"}}}""")) }
        assertEquals(a, FileNapplet.binding(metadata, manifest))
        rejected { FileNapplet.binding(JsonObject(metadata - "napplet"), manifest) }
        rejected { FileNapplet.binding(metadata, JsonObject(manifest + ("url" to JsonPrimitive("https://example.com")))) }
    }
}
