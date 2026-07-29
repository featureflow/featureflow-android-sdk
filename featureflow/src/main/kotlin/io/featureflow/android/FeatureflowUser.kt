package io.featureflow.android

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The user a feature is evaluated for.
 *
 * [id] is what percentage rollouts bucket on, so it must be **stable** for the same person
 * across launches. An id that changes per session re-buckets the user on every visit, which
 * turns "10% of users" into "10% of sessions" and makes a rollout look as though it is
 * flapping. When no id is supplied the SDK generates one and persists it — see
 * [FeatureflowClient.anonymousId].
 */
data class FeatureflowUser(
    val id: String,
    val attributes: Map<String, JsonValue> = emptyMap(),
    val sessionAttributes: Map<String, JsonValue> = emptyMap()
) {

    /**
     * Only non-empty attribute maps are serialised: the user is base64'd into the request path,
     * so every byte saved is path length that does not have to survive proxies and CDN keys.
     */
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        if (attributes.isNotEmpty()) {
            put("attributes", JSONObject().also { obj ->
                attributes.forEach { (key, value) -> obj.put(key, value.toJson()) }
            })
        }
        if (sessionAttributes.isNotEmpty()) {
            put("sessionAttributes", JSONObject().also { obj ->
                sessionAttributes.forEach { (key, value) -> obj.put(key, value.toJson()) }
            })
        }
    }

    /**
     * Fluent builder, mirroring `UserBuilder` in the other Featureflow SDKs.
     *
     * ```kotlin
     * val user = FeatureflowUser.Builder("user-123")
     *     .withAttribute("tier", "gold")
     *     .withAttributes("roles", listOf("admin", "beta"))
     *     .build()
     * ```
     */
    class Builder(private val id: String) {

        private val attributes = mutableMapOf<String, JsonValue>()
        private val sessionAttributes = mutableMapOf<String, JsonValue>()

        fun withAttribute(key: String, value: String) = apply {
            attributes[key] = JsonValue.of(value)
        }

        fun withAttribute(key: String, value: Int) = apply {
            attributes[key] = JsonValue.of(value)
        }

        fun withAttribute(key: String, value: Long) = apply {
            attributes[key] = JsonValue.of(value)
        }

        fun withAttribute(key: String, value: Double) = apply {
            attributes[key] = JsonValue.of(value)
        }

        fun withAttribute(key: String, value: Boolean) = apply {
            attributes[key] = JsonValue.of(value)
        }

        fun withAttribute(key: String, value: Date) = apply {
            attributes[key] = JsonValue.of(Iso8601.format(value))
        }

        fun withAttribute(key: String, value: JsonValue) = apply {
            attributes[key] = value
        }

        fun withAttributes(key: String, values: List<String>) = apply {
            attributes[key] = JsonValue.of(values)
        }

        /** An attribute for this evaluation only, not persisted against the user. */
        fun withSessionAttribute(key: String, value: JsonValue) = apply {
            sessionAttributes[key] = value
        }

        fun build(): FeatureflowUser =
            FeatureflowUser(id, attributes.toMap(), sessionAttributes.toMap())
    }
}

/**
 * ISO-8601 with milliseconds in UTC, matching `Date.toISOString()` in the JavaScript SDK.
 *
 * `SimpleDateFormat` is not thread-safe, so each call gets its own — this runs rarely enough
 * that a `ThreadLocal` would be more machinery than the problem deserves. `java.time` would be
 * cleaner but is only available from API 26 without desugaring, and this SDK supports API 21.
 */
internal object Iso8601 {

    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    fun format(date: Date): String =
        SimpleDateFormat(PATTERN, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date)

    /**
     * Parses the forms that reach a client rule.
     *
     * `yyyy-MM-dd` matters: the dashboard's date picker emits date-only values like
     * `2026-07-03`. JavaScript's `Date.parse` accepts those and reads them as UTC midnight, so
     * the JS SDK and the server both match on them. Parsing only full timestamps made every
     * date-only rule silently fail, skipping scheduled rollouts.
     */
    fun parse(value: String): Date? {
        val patterns = listOf(
            PATTERN,
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)
            } catch (_: Exception) {
                // Try the next pattern.
            }
        }
        return null
    }
}
