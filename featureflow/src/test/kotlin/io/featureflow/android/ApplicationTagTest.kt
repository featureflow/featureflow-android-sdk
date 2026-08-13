package io.featureflow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Date

/**
 * The `application` tag (featureflow-client-sdk-testbed CONTRACT.md): a configured application
 * name rides as `X-Featureflow-Application` on **every** request — evaluate fetches and event
 * posts. It is write-only telemetry, so an invalid value drops the header rather than failing a
 * request, and no configured application means no header at all.
 *
 * The server here is a hand-rolled loopback [ServerSocket] — real `HttpURLConnection` traffic
 * without a new test dependency, matching the SDK's own no-OkHttp stance. (`com.sun.net.httpserver`
 * is absent from the android.jar stubs unit tests compile against, so it is not an option.)
 */
@RunWith(RobolectricTestRunner::class)
class ApplicationTagTest {

    /** Serves the two endpoints and records the application header each request carried. */
    private class RecordingServer : AutoCloseable {

        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

        /** One entry per request; null when the header was absent. */
        val evaluateHeaders = mutableListOf<String?>()
        val eventHeaders = mutableListOf<String?>()

        init {
            Thread {
                try {
                    while (true) socket.accept().use { handle(it) }
                } catch (_: Exception) {
                    // Server closed.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        private fun handle(client: Socket) {
            // ISO_8859_1 maps chars 1:1 to bytes, so header parsing and body draining stay exact.
            val input = client.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            var application: String? = null
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                when (name) {
                    "x-featureflow-application" -> application = value
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                }
            }
            drain(input, contentLength)

            val path = requestLine.split(" ").getOrElse(1) { "" }
            synchronized(this) {
                when {
                    path.startsWith("/api/js/v1/evaluate") -> evaluateHeaders.add(application)
                    path.startsWith("/api/js/v1/event") -> eventHeaders.add(application)
                    else -> Unit
                }
            }

            val body = "{}"
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
            client.getOutputStream().apply {
                write(response.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
        }

        private fun drain(input: BufferedReader, contentLength: Int) {
            var remaining = contentLength
            val buffer = CharArray(8192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size))
                if (read < 0) break
                remaining -= read
            }
        }

        override fun close() = socket.close()
    }

    private class RecordingLogger : FeatureflowLogger {
        val warnings = mutableListOf<String>()
        override fun log(level: FeatureflowLogLevel, message: String) {
            if (level == FeatureflowLogLevel.WARN) warnings.add(message)
        }
    }

    private fun rest(server: RecordingServer, application: String?, logger: FeatureflowLogger? = null) =
        RestClient(
            "sdk-js-env-test",
            FeatureflowConfig(
                baseUrl = server.url,
                eventsUrl = server.url,
                application = application,
                logger = logger
            )
        )

    private fun event() = SdkEvent.Evaluation(
        featureKey = "new-checkout",
        variant = "on",
        impressions = 1,
        user = FeatureflowUser("user-123"),
        timestamp = Iso8601.format(Date(0))
    )

    @Test
    fun evaluateCarriesApplicationHeader() = RecordingServer().use { server ->
        rest(server, application = "checkout-api").evaluate(FeatureflowUser("user-123"))

        assertEquals(listOf<String?>("checkout-api"), server.evaluateHeaders)
    }

    @Test
    fun eventPostCarriesApplicationHeader() = RecordingServer().use { server ->
        rest(server, application = "checkout-api").postEvents(listOf(event()))

        assertEquals(listOf<String?>("checkout-api"), server.eventHeaders)
    }

    /** Case is forgiven: a mixed-case value is lowercased, not dropped. */
    @Test
    fun mixedCaseApplicationIsLowercased() = RecordingServer().use { server ->
        rest(server, application = "Checkout-API").evaluate(FeatureflowUser("user-123"))

        assertEquals(listOf<String?>("checkout-api"), server.evaluateHeaders)
    }

    /** An invalid value warns and sends no header at all — never a mangled one. */
    @Test
    fun invalidApplicationWarnsAndSendsNoHeader() = RecordingServer().use { server ->
        val logger = RecordingLogger()
        rest(server, application = "checkout api!", logger = logger).evaluate(FeatureflowUser("user-123"))

        assertEquals(1, server.evaluateHeaders.size)
        assertNull(server.evaluateHeaders.single())
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().contains("checkout api!"))
    }

    /** No configured application → the request is byte-identical to the status quo. */
    @Test
    fun noApplicationSendsNoHeader() = RecordingServer().use { server ->
        val logger = RecordingLogger()
        val client = rest(server, application = null, logger = logger)
        client.evaluate(FeatureflowUser("user-123"))
        client.postEvents(listOf(event()))

        assertNull(server.evaluateHeaders.single())
        assertNull(server.eventHeaders.single())
        assertEquals(0, logger.warnings.size)
    }
}
