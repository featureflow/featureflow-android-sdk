package io.featureflow.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the current evaluation and, optionally, persists it.
 *
 * The cache is what stops a returning user seeing a frame of default variants on every cold
 * start. It is keyed by API key *and* user id, so switching user (or logging out to an anonymous
 * id) does not serve the previous user's flags — a real correctness issue, not just tidiness,
 * because the previous user may have been entitled to something this one is not.
 */
internal class FeatureStore(
    private val apiKey: String,
    private val prefs: SharedPreferences?
) {

    private val controls = AtomicReference<Map<String, EvaluatedControl>>(emptyMap())

    val current: Map<String, EvaluatedControl> get() = controls.get()

    fun control(key: String): EvaluatedControl? = controls.get()[key]

    fun replace(next: Map<String, EvaluatedControl>) {
        controls.set(next)
    }

    fun persist(next: Map<String, EvaluatedControl>, userId: String) {
        val prefs = prefs ?: return
        val json = JSONObject().apply {
            next.forEach { (key, control) -> put(key, control.toJson()) }
        }
        prefs.edit().putString(cacheKey(userId), json.toString()).apply()
    }

    fun loadCached(userId: String): Map<String, EvaluatedControl>? {
        val prefs = prefs ?: return null
        val raw = prefs.getString(cacheKey(userId), null) ?: return null
        return try {
            val json = JSONObject(raw)
            buildMap {
                for (key in json.keys()) {
                    json.optJSONObject(key)?.let { put(key, EvaluatedControl.from(it)) }
                }
            }
        } catch (_: Exception) {
            // A cache written by an older SDK version is discarded rather than crashing on read.
            null
        }
    }

    // Hashed so a user id never lands in a preferences key verbatim.
    private fun cacheKey(userId: String): String =
        "io.featureflow.cache.${apiKey.takeLast(12)}.${stableHash(userId)}"

    private fun stableHash(value: String): String {
        // String.hashCode is stable across JVM runs, but this is explicit about that guarantee
        // being required — the key must survive a process restart.
        var hash = 5381L
        for (byte in value.toByteArray()) {
            hash = hash * 33 + byte
        }
        return java.lang.Long.toString(hash, 36)
    }

    companion object {
        const val PREFS_NAME = "io.featureflow.prefs"

        fun prefs(context: Context?): SharedPreferences? =
            context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * Persists the generated anonymous id.
 *
 * It must survive relaunches: a user who gets a new id on every launch is re-bucketed every
 * launch, so a percentage rollout would flicker on and off for them.
 */
internal class AnonymousIdStore(private val prefs: SharedPreferences?) {

    fun currentOrCreate(): String {
        val existing = prefs?.getString(KEY, null)
        if (existing != null) return existing
        return reset()
    }

    fun reset(): String {
        val generated = "anonymous:" + UUID.randomUUID().toString().lowercase()
        prefs?.edit()?.putString(KEY, generated)?.apply()
        return generated
    }

    private companion object {
        const val KEY = "io.featureflow.anonymousId"
    }
}
