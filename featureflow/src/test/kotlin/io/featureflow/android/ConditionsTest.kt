package io.featureflow.android

import org.junit.Assert.assertEquals
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

/**
 * Regression tests for the date-only format the dashboard's date picker emits.
 *
 * Found by running the harness against a real environment: a `lambda-redirect` rule targeting
 * `featureflow.date after 2026-07-03` evaluated to the fallback variant instead of the scheduled
 * one, because only full timestamps parsed. JavaScript's `Date.parse` accepts date-only and reads
 * it as UTC midnight, so the JS SDK and server matched where this did not.
 */
@RunWith(RobolectricTestRunner::class)
class DateOnlyConditionTest {

    @Test
    fun dateOnlyValuesParse() {
        assertTrue(
            Conditions.test("after", JsonValue.of("2026-07-29T00:00:00.000Z"), listOf(JsonValue.of("2026-07-03")))
        )
        assertFalse(
            Conditions.test("before", JsonValue.of("2026-07-29T00:00:00.000Z"), listOf(JsonValue.of("2026-07-03")))
        )
    }

    @Test
    fun dateOnlyOnBothSides() {
        assertTrue(Conditions.test("after", JsonValue.of("2026-07-29"), listOf(JsonValue.of("2026-07-03"))))
        assertTrue(Conditions.test("before", JsonValue.of("2026-07-03"), listOf(JsonValue.of("2026-07-29"))))
    }

    /** Read as UTC midnight, matching `Date.parse` — not local midnight. */
    @Test
    fun dateOnlyIsUtcMidnight() {
        assertTrue(
            Conditions.test("after", JsonValue.of("2026-07-03T00:00:01.000Z"), listOf(JsonValue.of("2026-07-03")))
        )
        assertFalse(
            Conditions.test("after", JsonValue.of("2026-07-02T23:59:59.000Z"), listOf(JsonValue.of("2026-07-03")))
        )
    }

    @Test
    fun stillRejectsNonDates() {
        assertFalse(Conditions.test("after", JsonValue.of("not-a-date"), listOf(JsonValue.of("2026-07-03"))))
    }
}

/**
 * The dashboard emits several date shapes. All must agree with `Date.parse`, which is what
 * sdk-server and the JavaScript SDK use.
 */
@RunWith(RobolectricTestRunner::class)
class DateFormatParityTest {

    private fun utc(date: java.util.Date) =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(date)

    @Test
    fun everyDashboardDateShapeParsesToTheSameInstantAsDateParse() {
        // value to expected UTC instant, as produced by JavaScript's Date.parse
        val cases = listOf(
            "2026-07-03" to "2026-07-03T00:00:00Z",
            "2026-07-29T02:03:00+04:00" to "2026-07-28T22:03:00Z",
            "2026-07-29T02:03:00.000Z" to "2026-07-29T02:03:00Z",
            "2026-07-29T02:03:00Z" to "2026-07-29T02:03:00Z"
        )
        cases.forEach { (value, expected) ->
            val parsed = Iso8601.parse(value)
            assertTrue("$value should parse", parsed != null)
            assertEquals(value, expected, utc(parsed!!))
        }
    }

    /** A timezone offset must not be silently dropped by the date-only pattern. */
    @Test
    fun offsetIsNotTruncatedByTheDateOnlyPattern() {
        val withOffset = Iso8601.parse("2026-07-29T02:03:00+04:00")!!
        val dateOnly = Iso8601.parse("2026-07-29")!!
        assertTrue("offset form must not collapse to midnight", withOffset.time != dateOnly.time)
    }

    @Test
    fun garbageStillReturnsNull() {
        assertEquals(null, Iso8601.parse("not-a-date"))
        assertEquals(null, Iso8601.parse("2026-07-29T02:03:00+04:00junk"))
    }
}
