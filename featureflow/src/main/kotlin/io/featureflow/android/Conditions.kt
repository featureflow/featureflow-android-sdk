package io.featureflow.android

/**
 * Condition operators, matching `conditions.ts` in sdk-server and `Conditions.ts` in the
 * JavaScript SDK. The set is fixed by the server's `Operator` enum — an unrecognised operator
 * must evaluate to `false` rather than throw, so a rule using an operator this SDK version
 * predates fails closed instead of crashing the host app.
 */
internal object Conditions {

    fun test(operator: String, userValue: JsonValue, conditionValues: List<JsonValue>): Boolean =
        when (operator) {
            "in" -> conditionValues.contains(userValue)
            "notIn" -> !conditionValues.contains(userValue)
            else -> {
                // Every other operator compares against the first condition value only.
                val expected = conditionValues.firstOrNull()
                if (expected == null) false else testSingle(operator, userValue, expected)
            }
        }

    private fun testSingle(operator: String, a: JsonValue, b: JsonValue): Boolean =
        when (operator) {
            "equals" -> a == b
            "contains" -> bothStrings(a, b) { x, y -> x.contains(y) }
            "startsWith" -> bothStrings(a, b) { x, y -> x.startsWith(y) }
            "endsWith" -> bothStrings(a, b) { x, y -> x.endsWith(y) }
            "matches" -> bothStrings(a, b) { x, y ->
                try {
                    Regex(y).containsMatchIn(x)
                } catch (_: Exception) {
                    // An invalid pattern authored in the dashboard must not crash the app.
                    false
                }
            }
            "before" -> compareDates(a, b) { x, y -> x < y }
            "after" -> compareDates(a, b) { x, y -> x > y }
            "greaterThan" -> compareNumbers(a, b) { x, y -> x > y }
            "greaterThanOrEqual" -> compareNumbers(a, b) { x, y -> x >= y }
            "lessThan" -> compareNumbers(a, b) { x, y -> x < y }
            "lessThanOrEqual" -> compareNumbers(a, b) { x, y -> x <= y }
            else -> false
        }

    private inline fun bothStrings(
        a: JsonValue,
        b: JsonValue,
        body: (String, String) -> Boolean
    ): Boolean {
        val x = a.stringValue ?: return false
        val y = b.stringValue ?: return false
        return body(x, y)
    }

    private inline fun compareNumbers(
        a: JsonValue,
        b: JsonValue,
        body: (Double, Double) -> Boolean
    ): Boolean {
        val x = a.doubleValue ?: return false
        val y = b.doubleValue ?: return false
        return body(x, y)
    }

    private inline fun compareDates(
        a: JsonValue,
        b: JsonValue,
        body: (Long, Long) -> Boolean
    ): Boolean {
        val x = millis(a) ?: return false
        val y = millis(b) ?: return false
        return body(x, y)
    }

    /** An ISO-8601 string, or a number already expressed as milliseconds since the epoch. */
    private fun millis(value: JsonValue): Long? = when (value) {
        is JsonValue.Str -> Iso8601.parse(value.value)?.time
        is JsonValue.Num -> value.value.toLong()
        else -> null
    }
}
