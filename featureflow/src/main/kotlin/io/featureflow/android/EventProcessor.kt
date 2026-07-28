package io.featureflow.android

import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Queues, summarises and flushes events.
 *
 * Evaluations are **summarised**: one pending entry per (feature, variant, user) carrying an
 * impression count, rather than one event per call. Without this, a flag read inside a Compose
 * composable — which recomposes whenever anything it reads changes — would post events at the
 * frame rate. Goals are sent raw, one event each, because each is a distinct conversion.
 *
 * All mutation happens on a single-threaded executor, which is both the lock and the timer.
 */
internal class EventProcessor(
    private val rest: RestClient,
    private val config: FeatureflowConfig
) {

    private data class SummaryKey(
        val featureKey: String,
        val variant: String,
        val userId: String
    )

    private data class SummaryEntry(
        val featureKey: String,
        val variant: String,
        val user: FeatureflowUser,
        var impressions: Int,
        val firstSeen: Date
    )

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "featureflow-events").apply { isDaemon = true }
        }

    private val summaries = mutableMapOf<SummaryKey, SummaryEntry>()
    private val goals = mutableListOf<SdkEvent>()
    private var timer: ScheduledFuture<*>? = null
    /** Set when the server has told us to back off (429). Nothing is sent until it passes. */
    private var suspendedUntil: Long = 0

    fun start() {
        if (config.disableEvents || config.offline) return
        execute {
            if (timer == null) {
                timer = executor.scheduleWithFixedDelay(
                    { flushNow() },
                    config.eventFlushIntervalMillis,
                    config.eventFlushIntervalMillis,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    fun recordEvaluation(featureKey: String, variant: String, user: FeatureflowUser) {
        if (config.disableEvents || config.offline) return
        execute {
            val key = SummaryKey(featureKey, variant, user.id)
            val existing = summaries[key]
            if (existing != null) {
                existing.impressions++
            } else if (totalQueued() < config.maxEventQueueSize) {
                summaries[key] = SummaryEntry(featureKey, variant, user, 1, Date())
            }
        }
    }

    fun recordGoal(
        goalKey: String,
        user: FeatureflowUser,
        value: Double?,
        data: Map<String, JsonValue>?
    ) {
        if (config.disableEvents || config.offline || goalKey.isEmpty()) return
        execute {
            if (totalQueued() < config.maxEventQueueSize) {
                goals.add(
                    SdkEvent.Goal(goalKey, user, value, data, Iso8601.format(Date()))
                )
            }
        }
    }

    /**
     * Flush now. Called on the timer, when the app backgrounds, and before the user changes — a
     * pending impression belongs to the user who was current when it was recorded.
     */
    fun flush() {
        if (config.disableEvents || config.offline) return
        execute { flushNow() }
    }

    fun stop() {
        execute {
            timer?.cancel(false)
            timer = null
        }
        executor.shutdown()
    }

    private fun totalQueued(): Int = summaries.size + goals.size

    private fun flushNow() {
        if (System.currentTimeMillis() < suspendedUntil) return

        val batch = summaries.values.map {
            SdkEvent.Evaluation(
                featureKey = it.featureKey,
                variant = it.variant,
                impressions = it.impressions,
                user = it.user,
                timestamp = Iso8601.format(it.firstSeen)
            )
        } + goals

        if (batch.isEmpty()) return
        summaries.clear()
        goals.clear()

        try {
            rest.postEvents(batch)
        } catch (e: FeatureflowException.RateLimited) {
            val backoff = (e.retryAfterSeconds ?: 60) * 1000
            suspendedUntil = System.currentTimeMillis() + backoff
            config.logger?.log(
                FeatureflowLogLevel.WARN,
                "Events rate limited; backing off for ${backoff / 1000}s"
            )
        } catch (e: Exception) {
            // The batch is already dropped. Events are diagnostic, not transactional, and
            // retrying risks double-counting impressions after a partial server-side write.
            config.logger?.log(FeatureflowLogLevel.DEBUG, "Event flush failed: ${e.message}")
        }
    }

    private fun execute(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    config.logger?.log(FeatureflowLogLevel.DEBUG, "Event task failed: ${e.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Already shut down. Losing diagnostic events at teardown is acceptable; throwing
            // from a flag SDK during app shutdown is not.
        }
    }
}
