package io.featureflow.android

import java.util.Calendar
import java.util.Date

/**
 * Resolves a variant from the rules the server returned.
 *
 * The server has already matched the user's own attributes. Two kinds of rule arrive:
 *
 * - **Fully evaluated** — no `audience`. It matched server-side; take its variant.
 * - **Partially evaluated** — an `audience` holding `featureflow.date` /
 *   `featureflow.hourofday` conditions the server deliberately did not resolve, so that the
 *   response stays identical for every user in the same bucket and can be cached at the CDN.
 *
 * Rules are ordered and **first match wins**, exactly as they are server-side. A partial rule
 * whose time conditions fail is skipped and evaluation continues with the next rule.
 *
 * The practical consequence of deferring time: these rules resolve against **this device's
 * clock**, so a scheduled rollout fires at the user's local time and a device with a wrong clock
 * gets the wrong answer. Anything needing a hard cutover at a specific instant should flip the
 * flag server-side.
 */
internal object RuleEvaluator {

    data class Resolved(val variant: String, val value: JsonValue?)

    fun evaluate(control: EvaluatedControl, now: Date = Date()): Resolved? {
        val context = timeContext(now)
        for (rule in control.rules) {
            if (matches(rule, context)) {
                return Resolved(rule.variant, rule.value)
            }
        }
        return null
    }

    private fun matches(rule: EvalRule, context: Map<String, List<JsonValue>>): Boolean {
        val audience = rule.audience ?: return true
        for (condition in audience.conditions) {
            // A condition targeting something this SDK does not supply locally is skipped rather
            // than failed — the server already tested every attribute it could, so an unknown
            // target here means a newer server behaviour, and skipping keeps the rule matching
            // as it did before that target existed.
            val values = context[condition.target] ?: continue
            val passed = values.any { Conditions.test(condition.operator, it, condition.values) }
            if (!passed) return false
        }
        return true
    }

    /** The locally supplied targets. Deliberately only the two the server defers. */
    private fun timeContext(now: Date): Map<String, List<JsonValue>> {
        val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
        return mapOf(
            "featureflow.date" to listOf(JsonValue.of(Iso8601.format(now))),
            "featureflow.hourofday" to listOf(JsonValue.of(hour))
        )
    }
}
