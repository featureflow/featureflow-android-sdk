package io.featureflow.android


/**
 * The result of evaluating a feature.
 *
 * Mirrors `Evaluate` in every other Featureflow SDK, so the idiom reads the same across the
 * stack: [isOn], [isOff], [`is`], [value], [jsonValue].
 *
 * Variant comparison is **exact**, and [value] returns the key exactly as configured. Keys are
 * lowercase by convention, so case-folding bought nothing while making [value] misreport a key
 * defined as `Wizard`. See CONTRACT.md in featureflow-client-sdk-testbed.
 */
class Evaluation internal constructor(
    private val variant: String,
    private val payload: JsonValue? = null
) {

    /** The evaluated variant key — `"on"`, `"off"`, or whatever the feature defines. */
    fun value(): String = variant

    /** True when the feature evaluated to [variant]. */
    fun `is`(variant: String): Boolean = this.variant == variant

    /** True when the feature evaluated to `on`. */
    fun isOn(): Boolean = variant == "on"

    /** True when the feature evaluated to `off`. */
    fun isOff(): Boolean = variant == "off"

    /** The evaluated variant's JSON config payload, or null when the variant has none. */
    fun jsonValue(): JsonValue? = payload

    override fun toString(): String = "Evaluation(variant=$variant)"
}
