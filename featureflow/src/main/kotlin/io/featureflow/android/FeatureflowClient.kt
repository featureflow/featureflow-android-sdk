package io.featureflow.android

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Featureflow client.
 *
 * Create one and share it — it holds a poll loop, an event queue and the current evaluation. A
 * second instance double-counts impressions and can disagree with the first about a flag.
 *
 * ```kotlin
 * val user = FeatureflowUser.Builder("user-123")
 *     .withAttribute("tier", "gold")
 *     .build()
 *
 * val featureflow = FeatureflowClient.initialize(
 *     context = applicationContext,
 *     apiKey = "sdk-js-env-…",
 *     user = user
 * )
 *
 * if (featureflow.evaluate("new-checkout").isOn()) {
 *     // …
 * }
 * ```
 *
 * The key is the **client** key, `sdk-js-env-…`, which is public by design. A server key
 * (`sdk-srv-env-…`) must never be shipped in an APK: it downloads the whole ruleset, including
 * every targeting rule and attribute name, and anyone can extract it from the artefact.
 */
class FeatureflowClient private constructor(
    private val apiKey: String,
    initialUser: FeatureflowUser,
    private val config: FeatureflowConfig,
    context: Context?,
    private val rest: RestClient
) {

    private val prefs = FeatureStore.prefs(context)
    private val store = FeatureStore(apiKey, if (config.useCache) prefs else null)
    private val events = EventProcessor(rest, config)
    private val anonymousIdStore = AnonymousIdStore(prefs)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userRef = AtomicReference(initialUser)
    private val readyFlag = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Map<String, String>) -> Unit>()
    private var pollJob: Job? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    private val _features = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Every feature and its current variant, updated whenever the evaluation changes.
     *
     * Collect this in Compose (`collectAsState`) or a ViewModel to have the UI follow a rollout
     * without a relaunch. Reading a flag through this flow does **not** record an impression —
     * call [evaluate] where the user is actually exposed to the feature.
     */
    val features: StateFlow<Map<String, String>> = _features.asStateFlow()

    /** The user flags are currently evaluated for. */
    val user: FeatureflowUser get() = userRef.get()

    /**
     * True once an evaluation has been applied, from the network or the cache.
     *
     * While false, every flag returns its configured default (or `off`). Gate flag-driven UI on
     * this rather than showing one variant and swapping it a moment later.
     */
    val isReady: Boolean get() = readyFlag.get()

    /**
     * The persisted anonymous id. Pass it to your backend — as the
     * `X-Featureflow-Anonymous-Id` header, say — to keep a signed-out user in the same bucket on
     * both sides of an experiment that spans app and server.
     */
    val anonymousId: String get() = anonymousIdStore.currentOrCreate()

    // MARK: - Evaluation

    /**
     * Evaluates a feature for the current user.
     *
     * Synchronous and cheap — it reads the already-fetched evaluation, so it is safe to call from
     * a composable or an adapter's `onBindViewHolder`. Each call records an impression, which is
     * summarised into a count rather than sent individually.
     */
    fun evaluate(featureKey: String): Evaluation {
        val evaluation = resolve(featureKey)
        events.recordEvaluation(featureKey, evaluation.value(), user)
        return evaluation
    }

    /**
     * Evaluates without recording an impression.
     *
     * For debug screens and diagnostics — anywhere a read does not mean a user was actually
     * exposed to the feature. Impressions drive experiment results and stale-flag detection, so
     * keeping non-exposures out of them matters.
     */
    fun peek(featureKey: String): Evaluation = resolve(featureKey)

    /** Every feature and its evaluated variant. Records no impressions. */
    fun allFeatures(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        store.current.forEach { (key, control) ->
            result[key] = RuleEvaluator.evaluate(control)?.variant?.lowercase()
                ?: fallbackVariant(key)
        }
        config.defaultVariants.forEach { (key, variant) ->
            result.putIfAbsentCompat(key, variant.lowercase())
        }
        return result
    }

    private fun resolve(featureKey: String): Evaluation {
        if (config.offline) return Evaluation(fallbackVariant(featureKey))
        val control = store.control(featureKey) ?: return Evaluation(fallbackVariant(featureKey))
        // Rules exist but none matched — for example every rule was time-gated and it is outside
        // every window. That is an `off`, not a missing flag.
        val resolved = RuleEvaluator.evaluate(control) ?: return Evaluation(fallbackVariant(featureKey))
        return Evaluation(resolved.variant, resolved.value)
    }

    private fun fallbackVariant(featureKey: String): String =
        config.defaultVariants[featureKey] ?: "off"

    // MARK: - Goals

    /**
     * Records a goal (conversion) event for the current user.
     *
     * Fire it where the conversion actually completes — after payment succeeds, not when the
     * button is tapped — and for **every** arm of an experiment including the control, or the
     * denominator is wrong.
     *
     * @param value the metric value, such as order total
     * @param data custom fields sent alongside
     */
    @JvmOverloads
    fun track(goalKey: String, value: Double? = null, data: Map<String, JsonValue>? = null) {
        events.recordGoal(goalKey, user, value, data)
    }

    // MARK: - User

    /**
     * Switches user and re-evaluates every flag.
     *
     * Call on login, on logout (back to an anonymous id), and whenever an attribute used in
     * targeting changes. Queued impressions are flushed first so they stay attributed to the user
     * who generated them.
     */
    suspend fun updateUser(user: FeatureflowUser): Boolean {
        events.flush()
        userRef.set(user)
        readyFlag.set(false)
        // The cache is per-user, so this is the previous session's values for *this* user, not
        // the outgoing user's.
        loadFromCache()
        return refreshIgnoringErrors()
    }

    /**
     * Issues a new anonymous id, discarding the current one.
     *
     * Use on logout when the signed-out user should not stay in the buckets the previous account
     * was in. Does not re-evaluate on its own — follow it with [updateUser].
     */
    fun resetAnonymousId(): String = anonymousIdStore.reset()

    // MARK: - Refresh

    /**
     * Re-fetches flags now. Returns false if the fetch failed; the previous values are kept.
     *
     * Safe to call from a `WorkManager` worker for apps that need background updates.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (config.offline) {
            markReady()
            return@withContext true
        }

        val current = user
        val controls = rest.evaluate(current)
        val previous = allFeatures()

        store.replace(controls)
        if (config.useCache) store.persist(controls, current.id)
        markReady()

        val next = allFeatures()
        if (next != previous) notify(next)
        true
    }

    private suspend fun refreshIgnoringErrors(): Boolean = try {
        refresh()
    } catch (e: Exception) {
        config.logger?.log(FeatureflowLogLevel.WARN, "Flag refresh failed: ${e.message}")
        // Ready either way: the app must not block on Featureflow being reachable. Values come
        // from the cache if there is one, defaults otherwise.
        markReady()
        false
    }

    private fun loadFromCache() {
        if (!config.useCache || config.offline) return
        val cached = store.loadCached(user.id) ?: return
        store.replace(cached)
        markReady()
        notify(allFeatures())
    }

    private fun markReady() {
        readyFlag.set(true)
    }

    // MARK: - Listeners

    /**
     * Called whenever the evaluated variants change, with the full current map.
     *
     * Prefer [features] in Compose or a ViewModel. This exists for plain View-based code.
     * Remember to [removeListener] in `onDestroy` — a listener holding a reference to an
     * Activity leaks it.
     */
    fun addListener(listener: (Map<String, String>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Map<String, String>) -> Unit) {
        listeners.remove(listener)
    }

    fun removeAllListeners() {
        listeners.clear()
    }

    private fun notify(features: Map<String, String>) {
        _features.value = features
        listeners.forEach { listener ->
            try {
                listener(features)
            } catch (e: Exception) {
                // A listener throwing must not stop the others, or the poll loop.
                config.logger?.log(FeatureflowLogLevel.WARN, "Flag listener threw: ${e.message}")
            }
        }
    }

    // MARK: - Polling and lifecycle

    private fun startPolling(intervalMillis: Long) {
        pollJob?.cancel()
        if (config.offline || intervalMillis <= 0) return
        pollJob = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                refreshIgnoringErrors()
            }
        }
    }

    /**
     * Refreshes on foreground and flushes on background.
     *
     * Backgrounding is the last reliable moment to send events — a backgrounded Android process
     * can be killed without further notice, taking unsent impressions and goals with it.
     */
    private fun observeLifecycle() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                startPolling(config.pollingIntervalMillis)
                if (config.refreshOnForeground) {
                    scope.launch { refreshIgnoringErrors() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                events.flush()
                startPolling(config.backgroundPollingIntervalMillis)
            }
        }
        lifecycleObserver = observer
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        } catch (e: Exception) {
            // ProcessLifecycleOwner needs its initialiser, which unit tests and some stripped
            // builds do not have. Polling still works; only foreground/background transitions
            // are missed.
            config.logger?.log(
                FeatureflowLogLevel.DEBUG,
                "Process lifecycle unavailable, foreground refresh disabled: ${e.message}"
            )
        }
    }

    /**
     * Stops polling and event delivery, flushing anything queued.
     *
     * The client is not usable afterwards. Most apps never need this — the client is meant to
     * live for the process — but tests and short-lived processes do.
     */
    fun close() {
        events.flush()
        events.stop()
        pollJob?.cancel()
        lifecycleObserver?.let { observer ->
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            } catch (_: Exception) {
                // Never registered; nothing to remove.
            }
        }
        scope.cancel()
        synchronized(FeatureflowClient::class.java) {
            if (instance === this) instance = null
        }
    }

    companion object {

        @Volatile
        private var instance: FeatureflowClient? = null

        /**
         * The instance created by [initialize], for callers that would rather not thread it
         * through themselves. Null until then.
         */
        @JvmStatic
        fun get(): FeatureflowClient? = instance

        /**
         * Creates a client and waits for the first evaluation.
         *
         * Awaiting this before rendering flag-driven UI is what avoids a visible variant swap on
         * launch. It never throws — if the fetch fails the client falls back to the on-disk cache
         * and then to [FeatureflowConfig.defaultVariants], because a flag service being
         * unreachable must never stop an app from starting.
         *
         * @param context an application context; the SDK holds no reference to an Activity
         * @param user omit for an anonymous, persisted id
         */
        @JvmStatic
        @JvmOverloads
        suspend fun initialize(
            context: Context,
            apiKey: String,
            user: FeatureflowUser? = null,
            config: FeatureflowConfig = FeatureflowConfig()
        ): FeatureflowClient {
            val prefs = FeatureStore.prefs(context)
            val resolvedUser = user ?: FeatureflowUser(AnonymousIdStore(prefs).currentOrCreate())

            val client = FeatureflowClient(
                apiKey = apiKey,
                initialUser = resolvedUser,
                config = config,
                context = context,
                rest = RestClient(apiKey, config)
            )

            client.loadFromCache()
            client.refreshIgnoringErrors()
            client.events.start()
            client.startPolling(config.pollingIntervalMillis)
            client.observeLifecycle()

            instance = client
            return client
        }

        /**
         * Builds a client with no Android context and an injectable transport.
         *
         * For unit tests: nothing is cached, no lifecycle is observed, and no polling starts.
         */
        internal fun forTesting(
            apiKey: String,
            user: FeatureflowUser,
            config: FeatureflowConfig,
            rest: RestClient
        ): FeatureflowClient = FeatureflowClient(apiKey, user, config, null, rest)
    }
}

/** `Map.putIfAbsent` needs API 24; this SDK supports 21. */
private fun MutableMap<String, String>.putIfAbsentCompat(key: String, value: String) {
    if (!containsKey(key)) put(key, value)
}
