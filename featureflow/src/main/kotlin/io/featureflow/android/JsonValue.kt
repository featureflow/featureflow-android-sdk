package io.featureflow.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * An arbitrary JSON value.
 *
 * Used for the two things that are genuinely untyped on the wire: user attribute values, and the
 * JSON config payload a variant may carry ([Evaluation.jsonValue]).
 *
 * `org.json` is used rather than a serialization library because it is part of the Android
 * platform — a feature-flag SDK should not force a JSON dependency, or a version of one, on the
 * app that embeds it.
 */
sealed class JsonValue {

    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Arr(val values: List<JsonValue>) : JsonValue()
    data class Obj(val values: Map<String, JsonValue>) : JsonValue()
    object Null : JsonValue()

    val stringValue: String? get() = (this as? Str)?.value
    val doubleValue: Double? get() = (this as? Num)?.value
    val intValue: Int? get() = (this as? Num)?.value?.toInt()
    val booleanValue: Boolean? get() = (this as? Bool)?.value
    val arrayValue: List<JsonValue>? get() = (this as? Arr)?.values
    val objectValue: Map<String, JsonValue>? get() = (this as? Obj)?.values

    operator fun get(key: String): JsonValue? = objectValue?.get(key)

    /** Renders back to the `org.json` representation, for embedding in a request body. */
    fun toJson(): Any = when (this) {
        is Str -> value
        is Num -> if (value == value.toLong().toDouble()) value.toLong() else value
        is Bool -> value
        is Arr -> JSONArray().also { array -> values.forEach { array.put(it.toJson()) } }
        is Obj -> JSONObject().also { obj -> values.forEach { (k, v) -> obj.put(k, v.toJson()) } }
        Null -> JSONObject.NULL
    }

    companion object {

        fun of(value: String): JsonValue = Str(value)
        fun of(value: Int): JsonValue = Num(value.toDouble())
        fun of(value: Long): JsonValue = Num(value.toDouble())
        fun of(value: Double): JsonValue = Num(value)
        fun of(value: Boolean): JsonValue = Bool(value)
        fun of(values: List<String>): JsonValue = Arr(values.map { Str(it) })

        /** Parses any `org.json` value. Returns [Null] for JSON null and unsupported types. */
        fun from(value: Any?): JsonValue = when (value) {
            null, JSONObject.NULL -> Null
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Int -> Num(value.toDouble())
            is Long -> Num(value.toDouble())
            is Double -> Num(value)
            is Number -> Num(value.toDouble())
            is JSONArray -> Arr((0 until value.length()).map { from(value.get(it)) })
            is JSONObject -> Obj(
                value.keys().asSequence().associateWith { from(value.get(it)) }
            )
            else -> Null
        }
    }
}
