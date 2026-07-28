package io.featureflow.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire types for the client evaluation endpoint.
 *
 * `GET /api/js/v1/evaluate/{apiKey}/user/{base64url(userJSON)}` returns
 * `{featureKey: EvaluatedControl}`. The server has already done rule matching against the user's
 * attributes; what arrives is a short list of candidate rules, each carrying the variant the user
 * would get. Rules that depended on `featureflow.date` or `featureflow.hourofday` arrive
 * *partially* evaluated — their remaining time conditions are attached for this device to
 * resolve, which is what keeps the response CDN-cacheable.
 *
 * See [RuleEvaluator] for the client half of that contract.
 */
internal data class EvaluatedControl(val rules: List<EvalRule>) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("rules", JSONArray().also { array -> rules.forEach { array.put(it.toJson()) } })
    }

    companion object {
        fun from(json: JSONObject): EvaluatedControl {
            val array = json.optJSONArray("rules") ?: JSONArray()
            return EvaluatedControl(
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { EvalRule.from(it) }
                }
            )
        }
    }
}

internal data class EvalRule(
    /** The variant this rule resolves to. Pre-computed server-side from the user's bucket. */
    val variant: String,
    /** The variant's JSON config payload, if it has one. */
    val value: JsonValue? = null,
    /**
     * Present only on partially evaluated rules. Its conditions are time-based and must be
     * tested against this device's clock.
     */
    val audience: EvalAudience? = null
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("variant", variant)
        value?.let { put("value", it.toJson()) }
        audience?.let { put("audience", it.toJson()) }
    }

    companion object {
        fun from(json: JSONObject): EvalRule = EvalRule(
            variant = json.optString("variant", "off"),
            value = if (json.has("value") && !json.isNull("value")) {
                JsonValue.from(json.get("value"))
            } else {
                null
            },
            audience = json.optJSONObject("audience")?.let { EvalAudience.from(it) }
        )
    }
}

internal data class EvalAudience(val conditions: List<EvalCondition>) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("conditions", JSONArray().also { array -> conditions.forEach { array.put(it.toJson()) } })
    }

    companion object {
        fun from(json: JSONObject): EvalAudience {
            val array = json.optJSONArray("conditions") ?: JSONArray()
            return EvalAudience(
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { EvalCondition.from(it) }
                }
            )
        }
    }
}

internal data class EvalCondition(
    val target: String,
    val operator: String,
    val values: List<JsonValue>
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("target", target)
        put("operator", operator)
        put("values", JSONArray().also { array -> values.forEach { array.put(it.toJson()) } })
    }

    companion object {
        fun from(json: JSONObject): EvalCondition {
            val array = json.optJSONArray("values") ?: JSONArray()
            return EvalCondition(
                target = json.optString("target"),
                operator = json.optString("operator"),
                values = (0 until array.length()).map { JsonValue.from(array.get(it)) }
            )
        }
    }
}

/**
 * One entry in an event batch posted to `POST /api/js/v1/event/{apiKey}`.
 *
 * Shaped to match `SdkEventDto` on the server. Evaluations are summarised into impression counts
 * per (feature, variant) before sending; goals are sent raw, one event each.
 */
internal sealed class SdkEvent {

    abstract fun toJson(): JSONObject

    data class Evaluation(
        val featureKey: String,
        val variant: String,
        val impressions: Int,
        val user: FeatureflowUser,
        val timestamp: String
    ) : SdkEvent() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "evaluate")
            put("featureKey", featureKey)
            put("evaluatedVariant", variant)
            put("impressions", impressions)
            put("timestamp", timestamp)
            put("user", user.toJson())
        }
    }

    data class Goal(
        val goalKey: String,
        val user: FeatureflowUser,
        val value: Double?,
        val data: Map<String, JsonValue>?,
        val timestamp: String
    ) : SdkEvent() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "goal")
            // The server keys every event by featureKey; a goal carries the goal key in both
            // places, matching the JavaScript SDK.
            put("featureKey", goalKey)
            put("goalKey", goalKey)
            put("timestamp", timestamp)
            put("user", user.toJson())
            value?.let { put("value", it) }
            data?.takeIf { it.isNotEmpty() }?.let { fields ->
                put("data", JSONObject().also { obj ->
                    fields.forEach { (key, field) -> obj.put(key, field.toJson()) }
                })
            }
        }
    }
}
