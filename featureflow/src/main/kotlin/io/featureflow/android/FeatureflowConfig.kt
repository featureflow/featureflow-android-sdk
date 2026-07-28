package io.featureflow.android

/**
 * Client configuration. Every field has a working default; the only required input to the SDK is
 * the client API key.
 */
data class FeatureflowConfig(

    /** Where flag evaluations are fetched from. */
    val baseUrl: String = "https://app.featureflow.io",

    /** Where impression and goal events are posted. */
    val eventsUrl: String = "https://events.featureflow.io",

    /**
     * How often to re-fetch flags while the app is in the foreground, in milliseconds.
     *
     * This is the propagation latency for a flag change: at 60s, turning a kill switch off in
     * the dashboard takes up to a minute to reach this device. It is also the main driver of
     * request volume, which Featureflow bills on — lowering it costs more and raises nothing
     * else. 60s is a reasonable default for a mobile app; go lower only for flags you expect to
     * flip during a live incident.
     */
    val pollingIntervalMillis: Long = 60_000,

    /**
     * Polling interval while the app is backgrounded, in milliseconds. Zero disables background
     * polling.
     *
     * Defaults to zero. A backgrounded Android process is subject to Doze and App Standby, so a
     * timer here is not a schedule the platform will honour — it stops when the process is
     * frozen. Flags refresh when the app returns to the foreground. Apps that genuinely need
     * background updates should use WorkManager and call [FeatureflowClient.refresh].
     */
    val backgroundPollingIntervalMillis: Long = 0,

    /** Re-fetch flags when the app returns to the foreground, regardless of the poll timer. */
    val refreshOnForeground: Boolean = true,

    /** Connect and read timeout for flag fetches and event posts, in milliseconds. */
    val timeoutMillis: Int = 10_000,

    /** Serve [defaultVariants] and make no network calls at all. For tests and previews. */
    val offline: Boolean = false,

    /**
     * Variants served before the first fetch completes, and when the SDK is offline or has no
     * cached value. Anything not listed evaluates to `off`.
     *
     * Set these for any flag whose wrong-way default would be harmful — this is the mobile
     * equivalent of the failover variants the server SDKs register.
     */
    val defaultVariants: Map<String, String> = emptyMap(),

    /**
     * Persist the last evaluation to `SharedPreferences`, so a returning user gets real values
     * before the network responds instead of a frame of defaults.
     */
    val useCache: Boolean = true,

    /**
     * Stop sending impression and goal events while still fetching flags. Impressions are what
     * power flag usage insight and experiment results, so this mainly exists for tests.
     */
    val disableEvents: Boolean = false,

    /** How often queued events are flushed, in milliseconds. */
    val eventFlushIntervalMillis: Long = 30_000,

    /**
     * Maximum events held in memory between flushes. Beyond this, further events are dropped —
     * bounded so a long offline session cannot grow without limit.
     */
    val maxEventQueueSize: Int = 1000,

    /**
     * Emits SDK diagnostics. Null by default: an SDK should not write to a host app's logcat
     * uninvited.
     */
    val logger: FeatureflowLogger? = null
)

/** Log sink. Implement to route SDK diagnostics into the host app's logging. */
fun interface FeatureflowLogger {
    fun log(level: FeatureflowLogLevel, message: String)
}

enum class FeatureflowLogLevel { DEBUG, INFO, WARN, ERROR }
