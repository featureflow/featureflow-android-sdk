package io.featureflow.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConditionsTest {

    @Test
    fun equals() {
        assertTrue(Conditions.test("equals", JsonValue.of("gold"), listOf(JsonValue.of("gold"))))
        assertFalse(Conditions.test("equals", JsonValue.of("gold"), listOf(JsonValue.of("silver"))))
        assertTrue(Conditions.test("equals", JsonValue.of(5), listOf(JsonValue.of(5))))
    }

    @Test
    fun stringOperators() {
        assertTrue(Conditions.test("contains", JsonValue.of("featureflow"), listOf(JsonValue.of("ature"))))
        assertFalse(Conditions.test("contains", JsonValue.of("featureflow"), listOf(JsonValue.of("zzz"))))
        assertTrue(Conditions.test("startsWith", JsonValue.of("featureflow"), listOf(JsonValue.of("feat"))))
        assertTrue(Conditions.test("endsWith", JsonValue.of("featureflow"), listOf(JsonValue.of("flow"))))
        assertTrue(
            Conditions.test(
                "matches",
                JsonValue.of("user@featureflow.io"),
                listOf(JsonValue.of(".*@featureflow\\.io$"))
            )
        )
        assertFalse(
            Conditions.test(
                "matches",
                JsonValue.of("user@other.io"),
                listOf(JsonValue.of(".*@featureflow\\.io$"))
            )
        )
    }

    /** An invalid pattern authored in the dashboard must not crash the app. */
    @Test
    fun invalidRegexIsFalseNotThrown() {
        assertFalse(Conditions.test("matches", JsonValue.of("x"), listOf(JsonValue.of("["))))
    }

    @Test
    fun nonStringFailsStringOperators() {
        assertFalse(Conditions.test("contains", JsonValue.of(42), listOf(JsonValue.of("4"))))
    }

    @Test
    fun inAndNotIn() {
        val values = listOf(JsonValue.of("admin"), JsonValue.of("beta"))
        assertTrue(Conditions.test("in", JsonValue.of("beta"), values))
        assertFalse(Conditions.test("in", JsonValue.of("guest"), values))
        assertTrue(Conditions.test("notIn", JsonValue.of("guest"), values))
        assertFalse(Conditions.test("notIn", JsonValue.of("admin"), values))
    }

    @Test
    fun numericComparisons() {
        assertTrue(Conditions.test("greaterThan", JsonValue.of(10), listOf(JsonValue.of(9))))
        assertFalse(Conditions.test("greaterThan", JsonValue.of(9), listOf(JsonValue.of(9))))
        assertTrue(Conditions.test("greaterThanOrEqual", JsonValue.of(9), listOf(JsonValue.of(9))))
        assertTrue(Conditions.test("lessThan", JsonValue.of(8), listOf(JsonValue.of(9))))
        assertTrue(Conditions.test("lessThanOrEqual", JsonValue.of(9), listOf(JsonValue.of(9))))
    }

    /** A version string compared numerically must not silently succeed. */
    @Test
    fun numericOperatorsRejectStrings() {
        assertFalse(
            Conditions.test("greaterThan", JsonValue.of("1.10.0"), listOf(JsonValue.of("1.9.0")))
        )
    }

    @Test
    fun dateComparisons() {
        val earlier = JsonValue.of("2026-01-01T00:00:00.000Z")
        val later = JsonValue.of("2026-06-01T00:00:00.000Z")
        assertTrue(Conditions.test("before", earlier, listOf(later)))
        assertFalse(Conditions.test("after", earlier, listOf(later)))
        assertTrue(Conditions.test("after", later, listOf(earlier)))
    }

    /** Dashboard-authored dates often lack fractional seconds. */
    @Test
    fun dateWithoutFractionalSeconds() {
        val earlier = JsonValue.of("2026-01-01T00:00:00Z")
        val later = JsonValue.of("2026-06-01T00:00:00Z")
        assertTrue(Conditions.test("before", earlier, listOf(later)))
    }

    /** An operator added to the server after this SDK shipped must fail closed, not crash. */
    @Test
    fun unknownOperatorIsFalse() {
        assertFalse(
            Conditions.test("someFutureOperator", JsonValue.of("a"), listOf(JsonValue.of("a")))
        )
    }

    @Test
    fun emptyConditionValues() {
        assertFalse(Conditions.test("equals", JsonValue.of("a"), emptyList()))
    }
}
