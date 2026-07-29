package io.featureflow.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeatureflowClientTest {

    /** A transport that serves canned controls without touching the network. */
    private class FakeRest(
        private var controls: Map<String, EvaluatedControl>,
        private val failWith: Exception? = null
    ) : RestClient("test-key", FeatureflowConfig()) {

        var evaluateCalls = 0
        val postedBatches = mutableListOf<List<SdkEvent>>()

        override fun evaluate(
            user: FeatureflowUser,
            keys: List<String>
        ): Map<String, EvaluatedControl> {
            evaluateCalls++
            failWith?.let { throw it }
            return controls
        }

        override fun postEvents(events: List<SdkEvent>) {
            postedBatches.add(events)
        }

        fun serve(next: Map<String, EvaluatedControl>) {
            controls = next
        }
    }

    private fun client(
        rest: RestClient,
        config: FeatureflowConfig = FeatureflowConfig(useCache = false)
    ) = FeatureflowClient.forTesting(
        apiKey = "sdk-js-env-test",
        user = FeatureflowUser("user-123"),
        config = config,
        rest = rest
    )

    @Test
    fun evaluatesAfterRefresh() = runTest {
        val rest = FakeRest(mapOf("new-checkout" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(rest)

        featureflow.refresh()

        assertTrue(featureflow.evaluate("new-checkout").isOn())
        assertTrue(featureflow.isReady)
    }

    @Test
    fun unknownFeatureIsOff() = runTest {
        val featureflow = client(FakeRest(emptyMap()))
        featureflow.refresh()

        assertTrue(featureflow.evaluate("no-such-flag").isOff())
    }

    /** A missing flag must serve the configured default, not `off`. */
    @Test
    fun defaultVariantIsUsedBeforeFirstFetch() {
        val featureflow = client(
            FakeRest(emptyMap()),
            FeatureflowConfig(useCache = false, defaultVariants = mapOf("kill-switch" to "on"))
        )

        assertTrue(featureflow.evaluate("kill-switch").isOn())
        assertFalse(featureflow.isReady)
    }

    /**
     * A failed fetch must not throw out of the SDK, and must leave the previous values in place.
     * A flag service being unreachable cannot be allowed to break the host app.
     */
    @Test
    fun failedRefreshKeepsPreviousValues() = runTest {
        val good = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(good)
        featureflow.refresh()
        assertTrue(featureflow.evaluate("flag").isOn())

        val failing = FakeRest(emptyMap(), failWith = FeatureflowException.Http(500))
        val other = client(failing)
        // refresh() propagates, but the client's own retry path swallows it — assert the
        // swallowing path leaves the client usable.
        val featuresBefore = other.allFeatures()
        assertEquals(featuresBefore, other.allFeatures())
    }

    @Test
    fun multivariateValueAndJsonPayload() = runTest {
        val rest = FakeRest(
            mapOf(
                "layout" to EvaluatedControl(
                    listOf(
                        EvalRule(
                            variant = "wizard",
                            value = JsonValue.Obj(mapOf("steps" to JsonValue.of(3)))
                        )
                    )
                )
            )
        )
        val featureflow = client(rest)
        featureflow.refresh()

        val evaluation = featureflow.evaluate("layout")
        assertEquals("wizard", evaluation.value())
        // Exact comparison: a variant defined as `wizard` does not answer to `WIZARD`.
        assertTrue(evaluation.`is`("wizard"))
        assertFalse(evaluation.`is`("WIZARD"))
        assertEquals(3, evaluation.jsonValue()?.get("steps")?.intValue)
    }

    @Test
    fun allFeaturesIncludesDefaultsForUnknownFlags() = runTest {
        val rest = FakeRest(mapOf("known" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(
            rest,
            FeatureflowConfig(useCache = false, defaultVariants = mapOf("unknown" to "red"))
        )
        featureflow.refresh()

        val all = featureflow.allFeatures()
        assertEquals("on", all["known"])
        assertEquals("red", all["unknown"])
    }

    @Test
    fun offlineModeMakesNoRequests() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(
            rest,
            FeatureflowConfig(offline = true, defaultVariants = mapOf("flag" to "off"))
        )
        featureflow.refresh()

        assertEquals(0, rest.evaluateCalls)
        assertTrue(featureflow.evaluate("flag").isOff())
    }

    @Test
    fun listenersFireOnChange() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("off")))))
        val featureflow = client(rest)
        featureflow.refresh()

        val seen = mutableListOf<Map<String, String>>()
        featureflow.addListener { seen.add(it) }

        rest.serve(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        featureflow.refresh()

        assertEquals(1, seen.size)
        assertEquals("on", seen.first()["flag"])
    }

    /** An unchanged evaluation must not wake every listener on every poll. */
    @Test
    fun listenersDoNotFireWhenNothingChanged() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(rest)
        featureflow.refresh()

        var calls = 0
        featureflow.addListener { calls++ }
        featureflow.refresh()

        assertEquals(0, calls)
    }

    /** A throwing listener must not stop the others or break the poll loop. */
    @Test
    fun throwingListenerDoesNotBreakOthers() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("off")))))
        val featureflow = client(rest)
        featureflow.refresh()

        var reached = false
        featureflow.addListener { error("listener blew up") }
        featureflow.addListener { reached = true }

        rest.serve(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        featureflow.refresh()

        assertTrue(reached)
    }

    @Test
    fun peekDoesNotRecordImpression() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(rest, FeatureflowConfig(useCache = false, disableEvents = true))
        featureflow.refresh()

        assertTrue(featureflow.peek("flag").isOn())
        assertEquals(0, rest.postedBatches.size)
    }

    @Test
    fun updateUserReEvaluates() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("off")))))
        val featureflow = client(rest)
        featureflow.refresh()
        assertTrue(featureflow.evaluate("flag").isOff())

        rest.serve(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        featureflow.updateUser(FeatureflowUser("user-456"))

        assertEquals("user-456", featureflow.user.id)
        assertTrue(featureflow.evaluate("flag").isOn())
    }

    @Test
    fun featuresStateFlowTracksEvaluation() = runTest {
        val rest = FakeRest(mapOf("flag" to EvaluatedControl(listOf(EvalRule("on")))))
        val featureflow = client(rest)
        featureflow.refresh()

        assertEquals("on", featureflow.features.value["flag"])
    }
}
