package io.featureflow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class RuleEvaluatorTest {

    private fun date(iso: String): Date = requireNotNull(Iso8601.parse(iso))

    @Test
    fun fullyEvaluatedRuleIsTakenAsIs() {
        val control = EvaluatedControl(listOf(EvalRule("on")))
        assertEquals("on", RuleEvaluator.evaluate(control)?.variant)
    }

    @Test
    fun noRulesMeansNoMatch() {
        assertNull(RuleEvaluator.evaluate(EvaluatedControl(emptyList())))
    }

    @Test
    fun firstMatchingRuleWins() {
        val control = EvaluatedControl(listOf(EvalRule("first"), EvalRule("second")))
        assertEquals("first", RuleEvaluator.evaluate(control)?.variant)
    }

    @Test
    fun partialRuleInsideDateWindowMatches() {
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "on",
                    audience = EvalAudience(
                        listOf(
                            EvalCondition(
                                "featureflow.date",
                                "after",
                                listOf(JsonValue.of("2026-01-01T00:00:00.000Z"))
                            )
                        )
                    )
                )
            )
        )
        val now = date("2026-06-01T12:00:00.000Z")
        assertEquals("on", RuleEvaluator.evaluate(control, now)?.variant)
    }

    @Test
    fun partialRuleOutsideDateWindowIsSkipped() {
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "on",
                    audience = EvalAudience(
                        listOf(
                            EvalCondition(
                                "featureflow.date",
                                "after",
                                listOf(JsonValue.of("2026-12-01T00:00:00.000Z"))
                            )
                        )
                    )
                )
            )
        )
        val now = date("2026-06-01T12:00:00.000Z")
        assertNull(RuleEvaluator.evaluate(control, now))
    }

    /** A time-gated rule that fails must fall through to the next rule, not end evaluation. */
    @Test
    fun skippedPartialRuleFallsThroughToNextRule() {
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "scheduled",
                    audience = EvalAudience(
                        listOf(
                            EvalCondition(
                                "featureflow.date",
                                "after",
                                listOf(JsonValue.of("2026-12-01T00:00:00.000Z"))
                            )
                        )
                    )
                ),
                EvalRule("fallback")
            )
        )
        val now = date("2026-06-01T12:00:00.000Z")
        assertEquals("fallback", RuleEvaluator.evaluate(control, now)?.variant)
    }

    /** Conditions within an audience are ANDed. */
    @Test
    fun allConditionsMustPass() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "on",
                    audience = EvalAudience(
                        listOf(
                            EvalCondition(
                                "featureflow.hourofday",
                                "greaterThanOrEqual",
                                listOf(JsonValue.of(hour))
                            ),
                            EvalCondition(
                                "featureflow.hourofday",
                                "greaterThan",
                                listOf(JsonValue.of(23))
                            )
                        )
                    )
                )
            )
        )
        assertNull(RuleEvaluator.evaluate(control))
    }

    @Test
    fun hourOfDayUsesLocalClock() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "on",
                    audience = EvalAudience(
                        listOf(
                            EvalCondition(
                                "featureflow.hourofday",
                                "equals",
                                listOf(JsonValue.of(hour))
                            )
                        )
                    )
                )
            )
        )
        assertEquals("on", RuleEvaluator.evaluate(control)?.variant)
    }

    /**
     * A target the SDK does not supply locally is skipped, so the rule matches as it would have
     * before that target existed. This keeps an old app working against a newer server.
     */
    @Test
    fun unknownTargetIsSkippedNotFailed() {
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "on",
                    audience = EvalAudience(
                        listOf(
                            EvalCondition(
                                "featureflow.somethingNew",
                                "equals",
                                listOf(JsonValue.of("x"))
                            )
                        )
                    )
                )
            )
        )
        assertEquals("on", RuleEvaluator.evaluate(control)?.variant)
    }

    @Test
    fun variantJsonPayloadIsCarried() {
        val control = EvaluatedControl(
            listOf(
                EvalRule(
                    variant = "on",
                    value = JsonValue.Obj(
                        mapOf(
                            "colour" to JsonValue.of("#0066cc"),
                            "maxUploads" to JsonValue.of(10)
                        )
                    )
                )
            )
        )
        val resolved = RuleEvaluator.evaluate(control)
        assertEquals("#0066cc", resolved?.value?.get("colour")?.stringValue)
        assertEquals(10, resolved?.value?.get("maxUploads")?.intValue)
    }
}
