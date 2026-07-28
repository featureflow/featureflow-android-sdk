package io.featureflow.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets
import java.util.Date

/**
 * The client SDKs, sdk-server and featureflow-edge-proxy all have to agree byte for byte.
 * These tests pin the parts of that contract this SDK controls.
 */
@RunWith(RobolectricTestRunner::class)
class WireFormatTest {

    @Test
    fun userEncodesToBase64UrlWithoutPadding() {
        // Chosen so standard base64 produces both '+' and '/', which are unsafe in a path.
        val user = FeatureflowUser(
            id = "user~123?&/x",
            attributes = mapOf("a" to JsonValue.of(listOf("b/c+d")))
        )
        val encoded = RestClient.base64UrlEncode(user)

        assertFalse("base64url must not emit '+'", encoded.contains("+"))
        assertFalse("'/' would split the URL path segment", encoded.contains("/"))
        assertFalse("padding must be stripped", encoded.contains("="))
    }

    /** The hand-rolled encoder must agree with a known-good implementation at every length. */
    @Test
    fun base64UrlMatchesReferenceEncoderAtEveryPadding() {
        for (length in 0..32) {
            val bytes = ByteArray(length) { (it * 37 + 11).toByte() }
            val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertEquals("length $length", expected, RestClient.base64UrlEncode(bytes))
        }
    }

    @Test
    fun userBase64RoundTrips() {
        val user = FeatureflowUser(
            id = "user-123",
            attributes = mapOf(
                "tier" to JsonValue.of("gold"),
                "roles" to JsonValue.of(listOf("admin"))
            )
        )
        val encoded = RestClient.base64UrlEncode(user)
        val decoded = String(
            java.util.Base64.getUrlDecoder().decode(encoded),
            StandardCharsets.UTF_8
        )
        val json = JSONObject(decoded)

        assertEquals("user-123", json.getString("id"))
        assertEquals("gold", json.getJSONObject("attributes").getString("tier"))
    }

    /** The user is in the URL, so an empty attribute map must not add bytes to every request. */
    @Test
    fun emptyAttributesAreOmitted() {
        val json = FeatureflowUser("user-123").toJson().toString()
        assertFalse(json.contains("attributes"))
        assertFalse(json.contains("sessionAttributes"))
    }

    @Test
    fun decodesServerEvaluateResponse() {
        val json = JSONObject(
            """
            {
              "new-checkout": {
                "rules": [
                  { "variant": "on", "value": { "colour": "#0066cc" } }
                ]
              },
              "scheduled-banner": {
                "rules": [
                  {
                    "variant": "on",
                    "audience": {
                      "conditions": [
                        { "target": "featureflow.hourofday", "operator": "greaterThan", "values": [9] },
                        { "target": "featureflow.hourofday", "operator": "lessThan", "values": [17] }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val checkout = EvaluatedControl.from(json.getJSONObject("new-checkout"))
        assertEquals("on", checkout.rules.first().variant)
        assertEquals("#0066cc", checkout.rules.first().value?.get("colour")?.stringValue)

        val banner = EvaluatedControl.from(json.getJSONObject("scheduled-banner"))
        assertEquals(2, banner.rules.first().audience?.conditions?.size)
    }

    /** A control with no rules decodes — it means "no rule matched", not a parse error. */
    @Test
    fun decodesEmptyRules() {
        val control = EvaluatedControl.from(JSONObject("""{"rules":[]}"""))
        assertEquals(0, control.rules.size)
    }

    /** A malformed control must not throw into the host app. */
    @Test
    fun decodesControlWithMissingRules() {
        val control = EvaluatedControl.from(JSONObject("{}"))
        assertEquals(0, control.rules.size)
    }

    @Test
    fun evaluationEventShape() {
        val event = SdkEvent.Evaluation(
            featureKey = "new-checkout",
            variant = "on",
            impressions = 7,
            user = FeatureflowUser("user-123"),
            timestamp = Iso8601.format(Date(0))
        )
        val json = event.toJson()

        assertEquals("evaluate", json.getString("type"))
        assertEquals("new-checkout", json.getString("featureKey"))
        assertEquals("on", json.getString("evaluatedVariant"))
        assertEquals(7, json.getInt("impressions"))
        assertFalse(json.has("goalKey"))
    }

    @Test
    fun goalEventShape() {
        val event = SdkEvent.Goal(
            goalKey = "purchase",
            user = FeatureflowUser("user-123"),
            value = 49.95,
            data = mapOf("plan" to JsonValue.of("pro")),
            timestamp = Iso8601.format(Date(0))
        )
        val json = event.toJson()

        assertEquals("goal", json.getString("type"))
        assertEquals("purchase", json.getString("goalKey"))
        // The server keys every event by featureKey, goals included.
        assertEquals("purchase", json.getString("featureKey"))
        assertEquals(49.95, json.getDouble("value"), 0.001)
        assertEquals("pro", json.getJSONObject("data").getString("plan"))
    }

    @Test
    fun goalWithoutValueOmitsIt() {
        val event = SdkEvent.Goal(
            goalKey = "signup",
            user = FeatureflowUser("user-123"),
            value = null,
            data = null,
            timestamp = Iso8601.format(Date(0))
        )
        val json = event.toJson()
        assertFalse(json.has("value"))
        assertFalse(json.has("data"))
    }

    @Test
    fun iso8601RoundTrips() {
        val formatted = Iso8601.format(Date(1_700_000_000_000))
        assertTrue(formatted.endsWith("Z"))
        assertEquals(1_700_000_000_000, Iso8601.parse(formatted)?.time)
    }

    @Test
    fun iso8601ParseRejectsGarbage() {
        assertNull(Iso8601.parse("not a date"))
    }

    @Test
    fun clientHeaderIdentifiesSdk() {
        assertTrue(RestClient.CLIENT_HEADER.startsWith("AndroidClient/"))
    }
}
