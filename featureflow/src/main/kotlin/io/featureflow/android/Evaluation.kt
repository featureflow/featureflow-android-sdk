package io.featureflow.android

import java.util.Locale

/**
 * The result of evaluating a feature.
 *
 * Mirrors `Evaluate` in every other Featureflow SDK, so the idiom reads the same across the
 * stack: [isOn], [isOff], [`is`], [value], [jsonValue].
 *
 * Variant comparison is case-insensitive, matching the JavaScript SDK.
 */
class Evaluation internal constructor(
    variant: String,
    private val payload: JsonValue? = null
) {

    private val variant: String = variant.lowercase(Locale.ROOT)

    /** The evaluated variant key — `"on"`, `"off"`, or whatever the feature defines. */
    fun value(): String = variant

    /** True when the feature evaluated to [variant]. */
    fun `is`(variant: String): Boolean = this.variant == variant.lowercase(Locale.ROOT)

    /** True when the feature evaluated to `on`. */
    fun isOn(): Boolean = variant == "on"

    /** True when the feature evaluated to `off`. */
    fun isOff(): Boolean = variant == "off"

    /** The evaluated variant's JSON config payload, or null when the variant has none. */
    fun jsonValue(): JsonValue? = payload

    override fun toString(): String = "Evaluation(variant=$variant)"
}
