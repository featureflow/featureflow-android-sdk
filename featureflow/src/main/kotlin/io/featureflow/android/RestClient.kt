package io.featureflow.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** Version of this SDK. Sent as the `X-Featureflow-Client` header. */
object FeatureflowVersion {
    const val CURRENT: String = "0.1.1"
}

internal sealed class FeatureflowException(message: String) : Exception(message) {
    class Unauthorized : FeatureflowException(
        "Featureflow rejected the API key (401/403). Check it is the client key for this " +
            "environment — it starts with sdk-js-env-."
    )

    class RateLimited(val retryAfterSeconds: Long?) : FeatureflowException(
        "Featureflow rate limited this client (429)."
    )

    class Http(val status: Int) : FeatureflowException("Featureflow returned HTTP $status.")

    class Transport(cause: String) : FeatureflowException("Could not reach Featureflow: $cause")
}

/**
 * Talks to the two client-facing endpoints on sdk-server.
 *
 * Both are keyed by the API key **in the URL path** rather than an auth header — client keys are
 * public by design, and this shape is what makes the evaluate response cacheable at the CDN. The
 * same contract is served by `featureflow-edge-proxy`, so paths, headers and response bytes must
 * stay identical to the JavaScript SDK's.
 *
 * `HttpURLConnection` is used rather than OkHttp deliberately: it is part of the platform, so the
 * SDK adds no transitive dependency and cannot conflict with the host app's HTTP stack or its
 * version.
 */
internal open class RestClient(
    private val apiKey: String,
    private val config: FeatureflowConfig
) {

    // Validated once: an invalid tag warns at construction, not on every request. Write-only
    // telemetry — it must never affect response handling and never appears in a URL.
    private val application: String? = sanitiseApplication(config.application, config.logger)

    open fun evaluate(user: FeatureflowUser, keys: List<String> = emptyList()): Map<String, EvaluatedControl> {
        val encoded = base64UrlEncode(user)
        val query = if (keys.isEmpty()) "" else "?keys=" + keys.joinToString(",")
        val url = "${config.baseUrl.trimEnd('/')}/api/js/v1/evaluate/$apiKey/user/$encoded$query"

        val body = get(url)
        val json = JSONObject(body)
        val controls = mutableMapOf<String, EvaluatedControl>()
        for (key in json.keys()) {
            json.optJSONObject(key)?.let { controls[key] = EvaluatedControl.from(it) }
        }
        return controls
    }

    open fun postEvents(events: List<SdkEvent>) {
        val url = "${config.eventsUrl.trimEnd('/')}/api/js/v1/event/$apiKey"
        val array = JSONArray().also { json -> events.forEach { json.put(it.toJson()) } }
        post(url, array.toString())
    }

    // MARK: - Plumbing

    private fun get(url: String): String {
        val connection = open(url, "GET")
        try {
            check(connection)
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw FeatureflowException.Transport(e.message ?: e.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun post(url: String, body: String) {
        val connection = open(url, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            check(connection)
            connection.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw FeatureflowException.Transport(e.message ?: e.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        // URI().toURL() rather than URL(String): the latter is deprecated on modern JDKs,
        // and this parses identically on every Android API level the SDK supports.
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = config.timeoutMillis
            readTimeout = config.timeoutMillis
            setRequestProperty("X-Featureflow-Client", CLIENT_HEADER)
            application?.let { setRequestProperty("X-Featureflow-Application", it) }
        }

    private fun check(connection: HttpURLConnection) {
        when (val status = connection.responseCode) {
            in 200..299 -> return
            401, 403 -> throw FeatureflowException.Unauthorized()
            429 -> throw FeatureflowException.RateLimited(
                connection.getHeaderField("Retry-After")?.toLongOrNull()?.takeIf { it > 0 }
            )
            else -> throw FeatureflowException.Http(status)
        }
    }

    companion object {

        val CLIENT_HEADER: String = "AndroidClient/${FeatureflowVersion.CURRENT}"

        /**
         * The user travels as base64 in a **path segment**, so the URL-safe alphabet is used and
         * padding dropped: standard base64 emits `+` and `/`, and a `/` inside a path segment is
         * a path separator. The server decodes with a base64 reader that accepts both alphabets.
         *
         * `java.util.Base64` needs API 26, so the encoding is done by hand to keep minSdk at 21.
         */
        fun base64UrlEncode(user: FeatureflowUser): String =
            base64UrlEncode(user.toJson().toString().toByteArray(StandardCharsets.UTF_8))

        internal fun base64UrlEncode(bytes: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            var index = 0
            while (index + 2 < bytes.size) {
                val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                    (bytes[index + 2].toInt() and 0xFF)
                out.append(alphabet[(chunk ushr 18) and 0x3F])
                out.append(alphabet[(chunk ushr 12) and 0x3F])
                out.append(alphabet[(chunk ushr 6) and 0x3F])
                out.append(alphabet[chunk and 0x3F])
                index += 3
            }
            when (bytes.size - index) {
                1 -> {
                    val chunk = (bytes[index].toInt() and 0xFF) shl 16
                    out.append(alphabet[(chunk ushr 18) and 0x3F])
                    out.append(alphabet[(chunk ushr 12) and 0x3F])
                }
                2 -> {
                    val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                        ((bytes[index + 1].toInt() and 0xFF) shl 8)
                    out.append(alphabet[(chunk ushr 18) and 0x3F])
                    out.append(alphabet[(chunk ushr 12) and 0x3F])
                    out.append(alphabet[(chunk ushr 6) and 0x3F])
                }
            }
            return out.toString()
        }
    }
}
